package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source

/**
 * Manages desktop extensions lifecycle: loading, tracking, and providing access to sources.
 */
class DesktopExtensionManager(
    private val loader: DesktopExtensionLoader = DesktopExtensionLoader(),
) {

    private val loadedExtensions = mutableListOf<LoadedExtension>()

    /** Loads all extensions from the extensions directory. */
    fun loadAll() {
        loadedExtensions.clear()
        loadedExtensions.addAll(loader.loadExtensions())
    }

    /** Returns all loaded sources. */
    fun getInstalledSources(): List<Source> = loadedExtensions.map { it.source }

    /** Returns a source by its ID, or null if not found. */
    fun getSource(sourceId: Long): Source? =
        loadedExtensions.find { it.source.id == sourceId }?.source

    /**
     * Returns installed extensions grouped by JAR file.
     * Each entry represents one JAR that may expose multiple sources.
     * Version info is read from the sidecar meta file when available.
     */
    fun getInstalledExtensions(): List<InstalledExtension> =
        loadedExtensions
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
                )
            }

    /**
     * Deletes the JAR file for [extension] and removes its sources from the loaded list.
     * @return true if the JAR was deleted successfully.
     */
    fun removeExtension(extension: InstalledExtension): Boolean {
        loadedExtensions.removeAll { it.jarFile == extension.jarFile }
        return extension.jarFile.delete()
    }

    /**
     * Deletes the JAR file and its meta sidecar for [extension].
     * @return true if the JAR was deleted successfully.
     */
    fun removeExtensionWithMeta(extension: InstalledExtension): Boolean {
        loadedExtensions.removeAll { it.jarFile == extension.jarFile }
        deleteExtensionMeta(extension.jarFile)
        return extension.jarFile.delete()
    }

    /** Re-scans the extensions directory and reloads all extensions. */
    fun reloadAll() = loadAll()

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
) {
    val name: String get() = jarFile.nameWithoutExtension
    val pkgName: String get() = jarFile.nameWithoutExtension
}
