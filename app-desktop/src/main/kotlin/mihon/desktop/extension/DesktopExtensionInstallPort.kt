package mihon.desktop.extension

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.PreparedExtensionInstallToken

internal typealias DesktopArtifactProvider = suspend (ExtensionArtifact, File) -> Unit

internal class DesktopExtensionInstallPort(
    private val extensionsDirectory: File,
    private val artifactProvider: DesktopArtifactProvider,
    private val apkConverter: ApkToJarConverter,
    private val reloadRuntime: (String, Set<Long>?) -> Unit,
) : ExtensionInstallPort {
    private val prepared = mutableMapOf<String, PreparedInstall>()
    private val rollbacks = mutableMapOf<String, PreparedInstall>()

    override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
        extensionsDirectory.mkdirs()
        val id = UUID.randomUUID().toString()
        val download = File(extensionsDirectory, "${request.artifact.packageName}.$id.tmp")
        return try {
            artifactProvider(request.artifact, download)
            prepared[id] = PreparedInstall(id, request.artifact, download)
            PreparedExtensionInstallToken(id)
        } catch (error: Throwable) {
            download.delete()
            throw error
        }
    }

    override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
        val install = prepared[token.value] ?: failStorage("Unknown prepared extension token")
        val declared = install.artifact.declaredSha256
        if (declared != null && !declared.equals(install.download.sha256(), ignoreCase = true)) {
            failMalformed("Downloaded extension digest mismatch")
        }

        val content = inspect(install.download)
        val candidate = File(extensionsDirectory, "${install.artifact.packageName}.${install.id}.candidate.tmp")
        when {
            content.hasJvmClasses -> {
                validatePackage(install.artifact, content.classNames)
                Files.copy(install.download.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            content.hasDex -> {
                install.extensionClass = ManifestClassExtractor.extractFromApk(install.download)
                install.extensionClass?.let { validatePackage(install.artifact, listOf(it)) }
                val converted = apkConverter.convert(install.download, extensionsDirectory)
                    ?: failMalformed("APK convert failed: could not translate DEX bytecode to JVM")
                Files.move(converted.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            else -> failMalformed("Android-only extension: artifact contains no JVM classes or DEX")
        }
        inspect(candidate)
        install.candidate = candidate
        install.destination = File(extensionsDirectory, "${install.artifact.packageName}.jar")
        install.metadata = File(extensionsDirectory, "${install.artifact.packageName}.meta.json")
        install.jarExisted = install.destination.exists()
        install.metaExisted = install.metadata.exists()
        install.jarBackup = File(extensionsDirectory, "${install.destination.name}.backup")
        install.metaBackup = File(extensionsDirectory, "${install.metadata.name}.backup")
        if (install.jarExisted) Files.copy(install.destination.toPath(), install.jarBackup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (install.metaExisted) Files.copy(install.metadata.toPath(), install.metaBackup.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val stageAnchor = File(extensionsDirectory, "${install.artifact.packageName}.${install.id}.metadata.jar")
        writeExtensionMeta(
            stageAnchor,
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
        install.stagedMetadata = File(extensionsDirectory, "${stageAnchor.nameWithoutExtension}.meta.json")
        val rollback = UUID.randomUUID().toString()
        rollbacks[rollback] = install
        return ExtensionInstallRollbackToken(rollback)
    }

    override suspend fun commit(token: PreparedExtensionInstallToken) {
        val install = prepared[token.value] ?: failStorage("Unknown prepared extension token")
        atomicMove(install.candidate, install.destination)
        atomicMove(install.stagedMetadata, install.metadata)
    }

    override suspend fun reload(packageName: String) {
        val install = prepared.values.singleOrNull { it.artifact.packageName == packageName }
            ?: failStorage("Missing prepared extension for $packageName")
        val expected = if (install.restoring) null else install.artifact.sources.map { it.id }.toSet()
        reloadRuntime(packageName, expected)
    }

    override suspend fun rollback(token: ExtensionInstallRollbackToken) {
        val install = rollbacks[token.value] ?: failStorage("Unknown rollback token")
        restore(install.jarBackup, install.destination, install.jarExisted)
        restore(install.metaBackup, install.metadata, install.metaExisted)
        install.restoring = true
    }

    override suspend fun cleanup(token: PreparedExtensionInstallToken) {
        val install = prepared[token.value] ?: return
        extensionsDirectory.listFiles().orEmpty()
            .filter { it.name.contains(install.id) || it == install.jarBackup || it == install.metaBackup }
            .forEach { file ->
                if (file.exists() && !file.delete()) failStorage("Unable to clean ${file.name}")
            }
        prepared.remove(token.value)
        rollbacks.entries.removeAll { it.value === install }
    }

    private fun inspect(file: File): ArtifactContent = try {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            ArtifactContent(
                hasJvmClasses = names.any { it.endsWith(".class") },
                hasDex = names.any { it.matches(Regex("classes\\d*\\.dex")) },
                classNames = names.filter { it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') },
            )
        }
    } catch (error: Exception) {
        failMalformed("Corrupt extension ZIP", error)
    }

    private fun validatePackage(artifact: ExtensionArtifact, classNames: List<String>) {
        if (classNames.isNotEmpty() && classNames.none { it == artifact.packageName || it.startsWith("${artifact.packageName}.") }) {
            failMalformed("Extension package does not match ${artifact.packageName}")
        }
    }

    private fun atomicMove(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun restore(backup: File, destination: File, existed: Boolean) {
        if (existed) {
            Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            destination.delete()
        }
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

    private fun failMalformed(message: String, cause: Throwable? = null): Nothing =
        throw ExtensionInstallFailure(AppError.MalformedData(cause ?: IllegalArgumentException(message)))

    private fun failStorage(message: String): Nothing =
        throw ExtensionInstallFailure(AppError.Storage(IllegalStateException(message)))

    private data class ArtifactContent(
        val hasJvmClasses: Boolean,
        val hasDex: Boolean,
        val classNames: List<String>,
    )

    private data class PreparedInstall(
        val id: String,
        val artifact: ExtensionArtifact,
        val download: File,
        var candidate: File = download,
        var destination: File = download,
        var metadata: File = download,
        var stagedMetadata: File = download,
        var jarBackup: File = download,
        var metaBackup: File = download,
        var jarExisted: Boolean = false,
        var metaExisted: Boolean = false,
        var extensionClass: String? = null,
        var restoring: Boolean = false,
    )
}
