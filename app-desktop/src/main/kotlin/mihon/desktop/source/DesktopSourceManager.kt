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
import tachiyomi.core.common.preference.getAndSet
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
        return builtins + extensions
    }

    override fun getCatalogueSources(): List<CatalogueSource> {
        return (builtinSources + extensionManager.getInstalledSources().filterIsInstance<CatalogueSource>())
    }

    /** Discovery/search candidates; disabled sources remain available through [get]. */
    fun getEnabledCatalogueSources(): List<CatalogueSource> = preferences
        ?.let { getEnabledCatalogueSourceCandidates(it) }
        ?: getCatalogueSources()

    /** Online discovery/search candidates; disabled sources remain available through [get]. */
    fun getEnabledOnlineSources(): List<HttpSource> = preferences
        ?.let { getEnabledOnlineSourceCandidates(it) }
        ?: getOnlineSources()

    override fun getStubSources(): List<StubSource> = emptyList()

    fun isSourceEnabled(sourceId: Long): Boolean = sourceId.toString() !in preferences?.disabledSources?.get().orEmpty()

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        preferences?.disabledSources?.getAndSet { disabled ->
            if (enabled) disabled - sourceId.toString() else disabled + sourceId.toString()
        }
    }
}

/** Fixed-main discovery policy; source resolution itself intentionally remains unfiltered. */
fun SourceManager.getEnabledCatalogueSourceCandidates(preferences: DesktopAppPreferences): List<CatalogueSource> {
    return selectEnabledCatalogueSourceCandidates(
        sources = getCatalogueSources(),
        enabledLanguages = preferences.enabledLanguages.get(),
        disabledSources = preferences.disabledSources.get(),
    )
}

fun selectEnabledCatalogueSourceCandidates(
    sources: List<CatalogueSource>,
    enabledLanguages: Set<String>,
    disabledSources: Set<String>,
): List<CatalogueSource> = sources.filter { source ->
    source.lang in enabledLanguages && source.id.toString() !in disabledSources
}

/** Online-source variant of [getEnabledCatalogueSourceCandidates]. */
fun SourceManager.getEnabledOnlineSourceCandidates(preferences: DesktopAppPreferences): List<HttpSource> {
    val enabledLanguages = preferences.enabledLanguages.get()
    val disabledSources = preferences.disabledSources.get()
    return getOnlineSources().filter { source ->
        source.lang in enabledLanguages && source.id.toString() !in disabledSources
    }
}
