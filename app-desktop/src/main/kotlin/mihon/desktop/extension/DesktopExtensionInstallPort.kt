package mihon.desktop.extension

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.PreparedExtensionInstallToken

internal typealias DesktopArtifactProvider = suspend (ExtensionArtifact, File) -> Unit

interface DesktopExtensionFileSystem {
    fun createDirectories(directory: File)
    fun copy(source: File, destination: File)
    fun replaceFromSnapshot(snapshot: File, destination: File)
    fun delete(file: File)
    fun deleteTree(directory: File)
}

internal class NioDesktopExtensionFileSystem(
    private val atomicMove: (Path, Path) -> Unit = { source, destination ->
        Files.move(
            source,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Unit
    },
) : DesktopExtensionFileSystem {
    override fun createDirectories(directory: File) {
        Files.createDirectories(directory.toPath())
    }

    override fun copy(source: File, destination: File) {
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun replaceFromSnapshot(snapshot: File, destination: File) {
        val replacement = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.replace.tmp")
        try {
            Files.copy(snapshot.toPath(), replacement.toPath(), StandardCopyOption.REPLACE_EXISTING)
            atomicMove(replacement.toPath(), destination.toPath())
        } finally {
            Files.deleteIfExists(replacement.toPath())
        }
    }

    override fun delete(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    override fun deleteTree(directory: File) {
        if (!directory.exists()) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

internal object DefaultDesktopExtensionFileSystem : DesktopExtensionFileSystem by NioDesktopExtensionFileSystem()

internal class DesktopExtensionLifecycleGate {
    private val permit = Semaphore(1, true)
    private val activityLock = ReentrantLock(true)
    private val idle = activityLock.newCondition()
    private var activeTransactions = 0
    private var closing = false

    fun transactionStarted() {
        activityLock.withLock {
            if (closing) throw CancellationException("Extension manager is closing")
            activeTransactions++
        }
    }

    fun transactionEnded() {
        activityLock.withLock {
            check(activeTransactions > 0) { "No active extension transaction to finish" }
            activeTransactions--
            if (activeTransactions == 0) idle.signalAll()
        }
    }

    fun closeAndAwait(cancelInstalls: () -> Unit) {
        activityLock.withLock { closing = true }
        cancelInstalls()
        activityLock.withLock {
            while (activeTransactions > 0) idle.awaitUninterruptibly()
        }
    }

    fun enterInstallWindow() {
        permit.acquireUninterruptibly()
    }

    fun exitInstallWindow() {
        permit.release()
    }

    fun <T> withPublicOperation(operation: () -> T): T {
        permit.acquireUninterruptibly()
        return try {
            activityLock.withLock {
                check(!closing) { "Extension manager is closed" }
            }
            operation()
        } finally {
            permit.release()
        }
    }

    fun <T> withShutdownOperation(operation: () -> T): T {
        permit.acquireUninterruptibly()
        return try {
            operation()
        } finally {
            permit.release()
        }
    }
}

internal fun requireValidExtensionPackageName(packageName: String) {
    val segments = packageName.split('.')
    val valid = packageName.isNotBlank() &&
        segments.all { segment ->
            segment.isNotEmpty() &&
                Character.isJavaIdentifierStart(segment.first()) &&
                segment.drop(1).all(Character::isJavaIdentifierPart)
        }
    if (!valid || '/' in packageName || '\\' in packageName || ':' in packageName) {
        failMalformed("Invalid extension package name: $packageName")
    }
}

internal fun extensionArtifactFile(directory: File, packageName: String, suffix: String): File {
    requireValidExtensionPackageName(packageName)
    val root = directory.toPath().toAbsolutePath().normalize()
    val resolved = root.resolve("$packageName.$suffix").normalize()
    if (!resolved.startsWith(root)) failMalformed("Extension path escapes installation directory")
    return resolved.toFile()
}

internal class DesktopExtensionInstallPort(
    private val extensionsDirectory: File,
    private val artifactProvider: DesktopArtifactProvider,
    private val apkConverter: ApkToJarConverter,
    private val loader: DesktopExtensionLoader,
    private val releaseRuntime: (String) -> Unit,
    private val reloadRuntime: (String, Set<Long>?) -> Unit,
    private val fileSystem: DesktopExtensionFileSystem = DefaultDesktopExtensionFileSystem,
    private val lifecycleGate: DesktopExtensionLifecycleGate = DesktopExtensionLifecycleGate(),
) : ExtensionInstallPort {
    private val prepared = ConcurrentHashMap<String, PreparedInstall>()
    private val rollbacks = ConcurrentHashMap<String, PreparedInstall>()

    override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
        lifecycleGate.transactionStarted()
        var handedOff = false
        try {
            return storageBoundary {
                requireValidExtensionPackageName(request.artifact.packageName)
                fileSystem.createDirectories(extensionsDirectory)
                val id = UUID.randomUUID().toString()
                val transactionDirectory = containedTransactionDirectory(id)
                fileSystem.createDirectories(transactionDirectory)
                val download = File(transactionDirectory, "download.bin")
                try {
                    artifactProvider(request.artifact, download)
                    prepared[id] = PreparedInstall(
                        id = id,
                        artifact = request.artifact,
                        transactionDirectory = transactionDirectory,
                        download = download,
                        recoveryArchive = containedRecoveryArchive(id),
                    )
                    PreparedExtensionInstallToken(id).also { handedOff = true }
                } catch (error: Throwable) {
                    val cleanupFailure = withContext(NonCancellable) {
                        runCatching { fileSystem.deleteTree(transactionDirectory) }.exceptionOrNull()
                    }
                    if (cleanupFailure != null) {
                        throw ExtensionInstallFailure(
                            AppError.PartialFailure(
                                failures = listOf(error.toInstallError(), cleanupFailure.toStorageError()),
                                cause = cleanupFailure,
                            ),
                        )
                    }
                    throw error
                }
            }
        } finally {
            if (!handedOff) lifecycleGate.transactionEnded()
        }
    }

    override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken = storageBoundary {
        val install = prepared[token.value] ?: failStorage("Unknown prepared extension token")
        val declared = install.artifact.declaredSha256
        if (declared != null && !declared.equals(install.download.sha256(), ignoreCase = true)) {
            failMalformed("Downloaded extension digest mismatch")
        }

        val content = inspect(install.download)
        val candidate = File(install.transactionDirectory, "candidate.jar")
        when {
            content.hasJvmClasses -> fileSystem.copy(install.download, candidate)
            content.hasDex -> {
                install.extensionClass = ManifestClassExtractor.extractFromApk(install.download)
                    ?.takeIf(String::isNotBlank)
                    ?: failMalformed("APK manifest does not declare an extension provider")
                validateDeclaredClass(install.artifact, install.extensionClass.orEmpty().split(':'))
                val converted = apkConverter.convert(install.download, install.transactionDirectory)
                    ?: failMalformed("APK convert failed: could not translate DEX bytecode to JVM")
                fileSystem.copy(converted, candidate)
            }
            else -> failMalformed("Android-only extension: artifact contains no JVM classes or DEX")
        }

        install.candidate = candidate
        install.destination = extensionArtifactFile(extensionsDirectory, install.artifact.packageName, "jar")
        install.metadata = extensionArtifactFile(extensionsDirectory, install.artifact.packageName, "meta.json")
        if (!install.windowHeld) {
            lifecycleGate.enterInstallWindow()
            install.windowHeld = true
        }
        currentCoroutineContext().ensureActive()
        install.jarExisted = install.destination.isFile
        install.metaExisted = install.metadata.isFile
        writeRecoveryArchive(install)

        writeExtensionMeta(
            candidate,
            ExtensionMeta(
                pkgName = install.artifact.packageName,
                versionCode = install.artifact.versionCode,
                versionName = install.artifact.versionName,
                iconUrl = install.artifact.iconUrl,
                repoUrl = install.artifact.repository.baseUrl,
                repoName = install.artifact.repository.name,
                repoFingerprint = install.artifact.repository.signingKeyFingerprint,
                installedAt = System.currentTimeMillis(),
                artifactSha256 = candidate.sha256(),
                source = if (content.hasDex) ExtensionOrigin.CONVERTED_APK else ExtensionOrigin.COMPILED_JAR,
                extensionClass = install.extensionClass,
            ),
        )
        install.stagedMetadata = File(install.transactionDirectory, "candidate.meta.json")
        validateRuntimeProvider(install)

        val rollback = UUID.randomUUID().toString()
        install.rollbackReady = true
        rollbacks[rollback] = install
        ExtensionInstallRollbackToken(rollback)
    }

    override suspend fun commit(token: PreparedExtensionInstallToken) = storageBoundary {
        val install = prepared[token.value] ?: failStorage("Unknown prepared extension token")
        currentCoroutineContext().ensureActive()
        releaseRuntime(install.artifact.packageName)
        fileSystem.replaceFromSnapshot(install.candidate, install.destination)
        fileSystem.replaceFromSnapshot(install.stagedMetadata, install.metadata)
    }

    override suspend fun reload(packageName: String) = storageBoundary {
        val install = prepared.values.singleOrNull { it.artifact.packageName == packageName }
            ?: failStorage("Missing prepared extension for $packageName")
        val expected = if (install.restoring) null else install.artifact.sources.map { it.id }.toSet()
        reloadRuntime(packageName, expected)
        currentCoroutineContext().ensureActive()
    }

    override suspend fun rollback(token: ExtensionInstallRollbackToken) = storageBoundary {
        val install = rollbacks[token.value] ?: failStorage("Unknown rollback token")
        install.restoring = true
        releaseRuntime(install.artifact.packageName)
        val recovery = readRecoveryArchive(install)
        restore(recovery.jar, install.destination, recovery.jarExisted)
        restore(recovery.metadata, install.metadata, recovery.metaExisted)
    }

    override suspend fun cleanup(token: PreparedExtensionInstallToken) = storageBoundary {
        val install = prepared[token.value] ?: return@storageBoundary
        try {
            fileSystem.deleteTree(install.transactionDirectory)
            currentCoroutineContext().ensureActive()
            fileSystem.delete(install.recoveryArchive)
        } catch (error: Throwable) {
            if (install.restoring || !install.rollbackReady) finishInstall(install)
            throw error
        }
        finishInstall(install)
    }

    private fun finishInstall(install: PreparedInstall) {
        prepared.remove(install.id)
        rollbacks.entries.removeAll { it.value === install }
        if (install.windowHeld) {
            install.windowHeld = false
            lifecycleGate.exitInstallWindow()
        }
        lifecycleGate.transactionEnded()
    }

    private fun validateRuntimeProvider(install: PreparedInstall) {
        val loaded = loader.loadFromSingleJar(install.candidate)
        try {
            if (loaded.isEmpty()) failMalformed("Extension runtime contains no loadable Source provider")
            val packagePrefix = "${install.artifact.packageName}."
            if (loaded.any { it.source.javaClass.name != install.artifact.packageName && !it.source.javaClass.name.startsWith(packagePrefix) }) {
                failMalformed("Extension Source provider does not match ${install.artifact.packageName}")
            }
            val expected = install.artifact.sources.map { it.id }.toSet()
            val actual = loaded.map { it.source.id }.toSet()
            if (expected.isNotEmpty() && !actual.containsAll(expected)) {
                failMalformed("Extension runtime is missing declared Source providers")
            }
        } finally {
            loaded.map { it.classLoader }.distinct().forEach { (it as? AutoCloseable)?.close() }
        }
    }

    private fun inspect(file: File): ArtifactContent = try {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            ArtifactContent(
                hasJvmClasses = names.any { it.endsWith(".class") },
                hasDex = names.any { it.matches(Regex("classes\\d*\\.dex")) },
            )
        }
    } catch (error: Exception) {
        failMalformed("Corrupt extension ZIP", error)
    }

    private fun validateDeclaredClass(artifact: ExtensionArtifact, classNames: List<String>) {
        val prefix = "${artifact.packageName}."
        if (classNames.isEmpty() || classNames.any { it != artifact.packageName && !it.startsWith(prefix) }) {
            failMalformed("Extension package does not match ${artifact.packageName}")
        }
    }

    private fun writeRecoveryArchive(install: PreparedInstall) {
        DataOutputStream(install.recoveryArchive.outputStream().buffered()).use { output ->
            output.writeInt(RECOVERY_MAGIC)
            output.writeSnapshot(install.destination, install.jarExisted)
            output.writeSnapshot(install.metadata, install.metaExisted)
        }
    }

    private fun readRecoveryArchive(install: PreparedInstall): RecoveryMaterial {
        fileSystem.createDirectories(install.transactionDirectory)
        return DataInputStream(install.recoveryArchive.inputStream().buffered()).use { input ->
            if (input.readInt() != RECOVERY_MAGIC) throw IOException("Invalid extension recovery archive")
            val jar = input.readSnapshot(File(install.transactionDirectory, "rollback.jar.snapshot"))
            val metadata = input.readSnapshot(File(install.transactionDirectory, "rollback.meta.snapshot"))
            RecoveryMaterial(jar.first, metadata.first, jar.second, metadata.second)
        }
    }

    private fun DataOutputStream.writeSnapshot(source: File, existed: Boolean) {
        writeBoolean(existed)
        if (!existed) return
        val bytes = source.readBytes()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSnapshot(destination: File): Pair<Boolean, File?> {
        val existed = readBoolean()
        if (!existed) return false to null
        val size = readInt()
        if (size < 0 || size > MAX_RECOVERY_ENTRY_BYTES) throw IOException("Invalid extension recovery entry size")
        val bytes = readNBytes(size)
        if (bytes.size != size) throw IOException("Truncated extension recovery archive")
        destination.writeBytes(bytes)
        return true to destination
    }

    private fun restore(snapshot: File?, destination: File, existed: Boolean) {
        if (existed) fileSystem.replaceFromSnapshot(checkNotNull(snapshot), destination) else fileSystem.delete(destination)
    }

    private fun containedTransactionDirectory(id: String): File {
        val root = extensionsDirectory.toPath().toAbsolutePath().normalize()
        val transaction = root.resolve(".install-$id.tmp").normalize()
        if (!transaction.startsWith(root) || transaction == root) failStorage("Invalid transaction directory")
        return transaction.toFile()
    }

    private fun containedRecoveryArchive(id: String): File {
        val root = extensionsDirectory.toPath().toAbsolutePath().normalize()
        val recovery = root.resolve(".recovery-$id.bin").normalize()
        if (!recovery.startsWith(root) || recovery == root) failStorage("Invalid recovery archive")
        return recovery.toFile()
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ArtifactContent(
        val hasJvmClasses: Boolean,
        val hasDex: Boolean,
    )

    private data class RecoveryMaterial(
        val jarExisted: Boolean,
        val metaExisted: Boolean,
        val jar: File?,
        val metadata: File?,
    )

    private data class PreparedInstall(
        val id: String,
        val artifact: ExtensionArtifact,
        val transactionDirectory: File,
        val download: File,
        val recoveryArchive: File,
        var candidate: File = download,
        var destination: File = download,
        var metadata: File = download,
        var stagedMetadata: File = download,
        var jarExisted: Boolean = false,
        var metaExisted: Boolean = false,
        var extensionClass: String? = null,
        var restoring: Boolean = false,
        var rollbackReady: Boolean = false,
        var windowHeld: Boolean = false,
    )

    private companion object {
        const val RECOVERY_MAGIC = 0x4D484E52
        const val MAX_RECOVERY_ENTRY_BYTES = 512 * 1024 * 1024
    }
}

private fun Throwable.toInstallError(): AppError = when (this) {
    is ExtensionInstallFailure -> error
    is CancellationException -> AppError.Cancelled
    else -> AppError.Unknown(this)
}

private fun Throwable.toStorageError(): AppError.Storage = when (this) {
    is ExtensionInstallFailure -> error as? AppError.Storage ?: AppError.Storage(this)
    else -> AppError.Storage(this)
}

private suspend inline fun <T> storageBoundary(crossinline block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ExtensionInstallFailure) {
    throw error
} catch (error: IOException) {
    throw ExtensionInstallFailure(AppError.Storage(error))
} catch (error: SecurityException) {
    throw ExtensionInstallFailure(AppError.Storage(error))
}

private fun failMalformed(message: String, cause: Throwable? = null): Nothing =
    throw ExtensionInstallFailure(AppError.MalformedData(cause ?: IllegalArgumentException(message)))

private fun failStorage(message: String): Nothing =
    throw ExtensionInstallFailure(AppError.Storage(IllegalStateException(message)))
