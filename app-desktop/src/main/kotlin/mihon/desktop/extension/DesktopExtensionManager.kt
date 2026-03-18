package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source

/**
 * Manages desktop extensions lifecycle: loading, tracking, and providing access to sources.
 */
class DesktopExtensionManager(
    private val loader: DesktopExtensionLoader = DesktopExtensionLoader(),
) {

    private val loadedExtensions = mutableListOf<LoadedExtension>()

    /**
     * Loads all extensions from the extensions directory.
     */
    fun loadAll() {
        loadedExtensions.clear()
        loadedExtensions.addAll(loader.loadExtensions())
    }

    /**
     * Returns all loaded sources.
     */
    fun getInstalledSources(): List<Source> {
        return loadedExtensions.map { it.source }
    }

    /**
     * Returns a source by its ID, or null if not found.
     */
    fun getSource(sourceId: Long): Source? {
        return loadedExtensions.find { it.source.id == sourceId }?.source
    }
}
