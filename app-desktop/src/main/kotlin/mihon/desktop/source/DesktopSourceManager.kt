package mihon.desktop.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Desktop implementation of [SourceManager] backed by [DesktopExtensionManager].
 *
 * Includes built-in sources (MangaDex) plus any extension JARs in ~/.mihon/extensions/.
 */
class DesktopSourceManager(
    private val extensionManager: DesktopExtensionManager,
    private val preferences: DesktopAppPreferences? = null,
    private val builtinSources: List<CatalogueSource> = listOf(MangaDexSource()),
) : SourceManager {

    private val _isInitialized = MutableStateFlow(true)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    override val catalogueSources: Flow<List<CatalogueSource>>
        get() = flowOf(getCatalogueSources())

    override fun get(sourceKey: Long): Source? {
        if (!isSourceEnabled(sourceKey)) return null
        return builtinSources.find { it.id == sourceKey }
            ?: extensionManager.getSource(sourceKey)
    }

    override fun getOrStub(sourceKey: Long): Source {
        return get(sourceKey)
            ?: StubSource(id = sourceKey, lang = "", name = "Stub($sourceKey)")
    }

    override fun getOnlineSources(): List<HttpSource> {
        val builtins = builtinSources.filterIsInstance<HttpSource>()
        val extensions = extensionManager.getInstalledSources().filterIsInstance<HttpSource>()
        return (builtins + extensions).filter { isSourceEnabled(it.id) }
    }

    override fun getCatalogueSources(): List<CatalogueSource> {
        return (builtinSources + extensionManager.getInstalledSources().filterIsInstance<CatalogueSource>())
            .filter { isSourceEnabled(it.id) }
    }

    override fun getStubSources(): List<StubSource> = emptyList()

    fun isSourceEnabled(sourceId: Long): Boolean = sourceId !in disabledSourceIds()

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        val ids = disabledSourceIds().toMutableSet()
        if (enabled) ids.remove(sourceId) else ids.add(sourceId)
        preferences?.disabledSourceIds?.set(ids.sorted().joinToString(","))
    }

    private fun disabledSourceIds(): Set<Long> = preferences?.disabledSourceIds?.get()
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .toSet()
}
