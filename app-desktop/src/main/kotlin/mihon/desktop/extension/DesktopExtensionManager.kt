package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallState

/**
 * Manages desktop extensions lifecycle: loading, tracking, and providing access to sources.
 */
class DesktopExtensionManager(
    private val loader: DesktopExtensionLoader = DesktopExtensionLoader(),
    artifactProvider: DesktopArtifactProvider = { _, _ ->
        error("This extension manager was created without an artifact provider")
    },
    apkConverter: ApkToJarConverter = ApkToJarConverter(),
    fileSystem: DesktopExtensionFileSystem = DefaultDesktopExtensionFileSystem,
) : AutoCloseable {

    private val loadedExtensions = mutableListOf<LoadedExtension>()
    private val runtimeLock = Any()
    private val lifecycleGate = DesktopExtensionLifecycleGate()
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installPort = DesktopExtensionInstallPort(
        extensionsDirectory = loader.extensionsDirectory,
        artifactProvider = artifactProvider,
        apkConverter = apkConverter,
        loader = loader,
        releaseRuntime = ::releaseRuntime,
        reloadRuntime = ::reloadRuntime,
        fileSystem = fileSystem,
        lifecycleGate = lifecycleGate,
    )
    private val installCoordinator = ExtensionInstallCoordinator(installPort, installScope)
    private val mutableInstalledExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installedExtensions: StateFlow<List<InstalledExtension>> = mutableInstalledExtensions.asStateFlow()

    /** Loads all extensions from the extensions directory. */
    fun loadAll() {
        lifecycleGate.withPublicOperation {
            val replacements = loader.loadExtensions()
            val previous = synchronized(runtimeLock) {
                loadedExtensions.toList().also {
                    loadedExtensions.clear()
                    loadedExtensions.addAll(replacements)
                }
            }
            closeLoaders(previous)
            publishInstalledExtensions()
        }
    }

    /** Returns all loaded sources. */
    fun getInstalledSources(): List<Source> = synchronized(runtimeLock) { loadedExtensions.map { it.source } }

    /** Returns a source by its ID, or null if not found. */
    fun getSource(sourceId: Long): Source? =
        synchronized(runtimeLock) { loadedExtensions.find { it.source.id == sourceId }?.source }

    /** Returns the installed extension package that owns [sourceId]. */
    fun getExtensionPackage(sourceId: Long): String? = synchronized(runtimeLock) {
        loadedExtensions.find { it.source.id == sourceId }?.jarFile?.nameWithoutExtension
    }

    /**
     * Returns installed extensions grouped by JAR file.
     * Each entry represents one JAR that may expose multiple sources.
     * Version info is read from the sidecar meta file when available.
     */
    fun getInstalledExtensions(): List<InstalledExtension> = installedExtensions.value

    private fun snapshotInstalledExtensions(): List<InstalledExtension> =
        synchronized(runtimeLock) { loadedExtensions.toList() }
            .groupBy { it.jarFile }
            .map { (jarFile, exts) ->
                val meta = readExtensionMeta(jarFile)
                InstalledExtension(
                    jarFile = jarFile,
                    sources = exts.map { it.source },
                    versionCode = meta?.versionCode ?: 0L,
                    versionName = meta?.versionName ?: "",
                    iconUrl = meta?.iconUrl ?: "",
                    repoUrl = meta?.repoUrl ?: "",
                    repoName = meta?.repoName ?: "",
                    repoFingerprint = meta?.repoFingerprint ?: "",
                    installedAt = meta?.installedAt ?: 0L,
                    artifactSha256 = meta?.artifactSha256 ?: "",
                    origin = meta?.source ?: ExtensionOrigin.COMPILED_JAR,
                    displayName = meta?.name.orEmpty(),
                    language = meta?.language.orEmpty(),
                    isNsfw = meta?.isNsfw == true,
                )
            }

    private fun publishInstalledExtensions() {
        mutableInstalledExtensions.value = snapshotInstalledExtensions()
    }

    /**
     * Deletes the JAR file for [extension] and removes its sources from the loaded list.
     * @return true if the JAR was deleted successfully.
     */
    fun removeExtension(extension: InstalledExtension): Boolean {
        val removed = lifecycleGate.withPublicOperation {
            releaseRuntime(extension.pkgName)
            extension.jarFile.delete().also { deleted ->
                if (!deleted) reloadRuntime(extension.pkgName, extension.sources.map { it.id }.toSet())
            }
        }
        publishInstalledExtensions()
        return removed
    }

    /**
     * Deletes the JAR file and its meta sidecar for [extension].
     * @return true if the JAR was deleted successfully.
     */
    fun removeExtensionWithMeta(extension: InstalledExtension): Boolean {
        val removed = lifecycleGate.withPublicOperation {
            releaseRuntime(extension.pkgName)
            extension.jarFile.delete().also { deleted ->
                if (deleted) {
                    deleteExtensionMeta(extension.jarFile)
                } else {
                    reloadRuntime(extension.pkgName, extension.sources.map { it.id }.toSet())
                }
            }
        }
        publishInstalledExtensions()
        return removed
    }

    /** Re-scans the extensions directory and reloads all extensions. */
    fun reloadAll() = loadAll()

    internal suspend fun installExtension(artifact: ExtensionArtifact): ExtensionInstallState =
        installExtensionStates(artifact).last()

    internal fun installExtensionStates(artifact: ExtensionArtifact): Flow<ExtensionInstallState> =
        installCoordinator.install(ExtensionInstallRequest(artifact))
            .onCompletion { publishInstalledExtensions() }

    private fun releaseRuntime(packageName: String) {
        val previous = synchronized(runtimeLock) {
            loadedExtensions.filter { it.jarFile.nameWithoutExtension == packageName }
                .also { loadedExtensions.removeAll(it.toSet()) }
        }
        closeLoaders(previous)
    }

    private fun reloadRuntime(packageName: String, expectedSourceIds: Set<Long>?) {
        val replacements = loader.loadPackage(packageName)
        val jarExists = java.io.File(loader.extensionsDirectory, "$packageName.jar").isFile
        val valid = when {
            expectedSourceIds == null -> !jarExists || replacements.isNotEmpty()
            replacements.isEmpty() -> false
            expectedSourceIds.isEmpty() -> true
            else -> replacements.map { it.source.id }.toSet().containsAll(expectedSourceIds)
        }
        if (!valid) {
            closeLoaders(replacements)
            throw ExtensionInstallFailure(
                AppError.MalformedData(IllegalStateException("Extension runtime reload failed for $packageName")),
            )
        }

        val previous = synchronized(runtimeLock) {
            loadedExtensions.filter { it.jarFile.nameWithoutExtension == packageName }.also {
                loadedExtensions.removeAll(it.toSet())
                loadedExtensions.addAll(replacements)
            }
        }
        closeLoaders(previous)
    }

    override fun close() {
        lifecycleGate.closeAndAwait { installScope.cancel() }
        lifecycleGate.withShutdownOperation {
            val previous = synchronized(runtimeLock) {
                loadedExtensions.toList().also { loadedExtensions.clear() }
            }
            closeLoaders(previous)
        }
    }

    private fun closeLoaders(extensions: List<LoadedExtension>) {
        extensions.map { it.classLoader }.distinct().forEach { (it as? AutoCloseable)?.close() }
    }

    /** Returns the directory where extensions should be placed. */
    val extensionsDirectory get() = loader.extensionsDirectory
}

/**
 * An installed extension: one JAR file containing one or more [Source] implementations.
 * Version info is populated from the sidecar meta file when available.
 */
data class InstalledExtension(
    val jarFile: java.io.File,
    val sources: List<Source>,
    val versionCode: Long = 0L,
    val versionName: String = "",
    val iconUrl: String = "",
    val repoUrl: String = "",
    val repoName: String = "",
    val repoFingerprint: String = "",
    val installedAt: Long = 0L,
    val artifactSha256: String = "",
    val origin: ExtensionOrigin = ExtensionOrigin.COMPILED_JAR,
    val displayName: String = "",
    val language: String = "",
    val isNsfw: Boolean = false,
) {
    val name: String get() = displayName.ifBlank { jarFile.nameWithoutExtension }
    val pkgName: String get() = jarFile.nameWithoutExtension
}
