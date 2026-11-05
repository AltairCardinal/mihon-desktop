package mihon.desktop.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceRegistration

/**
 * Desktop implementation of [SourceManager] backed by [DesktopExtensionManager].
 *
 * Includes built-in sources (MangaDex) plus any extension JARs in ~/.mihon/extensions/.
 */
class DesktopSourceManager(
    private val extensionManager: DesktopExtensionManager,
    private val preferences: DesktopAppPreferences? = null,
    private val builtinSources: List<CatalogueSource> = listOf(MangaDexSource()),
    private val additionalCatalogueSources: () -> List<CatalogueSource> = { emptyList() },
    private val additionalSourceRegistrations: () -> List<SourceRegistration> = { emptyList() },
) : SourceManager {
    private val builtinRegistrations = builtinSources.map { SourceRegistration(it, it.id) }
    private val registrationLock = Any()
    private var rawAdditionalRegistrations = emptyList<SourceRegistration>()

    private fun registerAdditionalSnapshot(
        stopAtId: Long? = null,
        known: List<SourceRegistration>,
    ): List<SourceRegistration> = synchronized(registrationLock) {
        val sources = additionalCatalogueSources()
        val previous = rawAdditionalRegistrations.filter { registered -> sources.any { it === registered.source } }
        rawAdditionalRegistrations = previous
        val observed = mutableListOf<SourceRegistration>()
        for (source in sources) {
            val registration = observed.firstOrNull { it.source === source }
                ?: previous.firstOrNull { it.source === source }
                ?: known.firstOrNull { it.source === source }
                ?: SourceRegistration(source, source.id)
            observed += registration
            if (rawAdditionalRegistrations.none { it.source === source }) {
                rawAdditionalRegistrations = rawAdditionalRegistrations + registration
            }
            if (stopAtId != null && registration.sourceId == stopAtId) break
        }
        observed
    }

    override fun getRegisteredSourceId(source: Source): Long? {
        builtinRegistrations.firstOrNull { it.source === source }?.let { return it.sourceId }
        synchronized(registrationLock) {
            rawAdditionalRegistrations.firstOrNull { it.source === source }?.let { return it.sourceId }
        }
        additionalSourceRegistrations().firstOrNull { it.source === source }?.let { return it.sourceId }
        return extensionManager.getRegisteredSourceId(source)
    }

    // The extension sources arrive in the background, so the manager reports the same
    // initialization state as the extension manager that backs it.
    override val isInitialized: StateFlow<Boolean> = extensionManager.isInitialized

    override val catalogueSources: Flow<List<CatalogueSource>> =
        extensionManager.installedExtensions.map(::canonicalCatalogueSources)

    override val querySources: Flow<List<Source>> = extensionManager.installedExtensions.map(::canonicalQuerySources)

    override fun get(sourceKey: Long): Source? {
        builtinRegistrations.firstOrNull { it.sourceId == sourceKey }?.let { return it.source }
        val trusted = additionalSourceRegistrations()
        val installed = extensionManager.installedExtensions.value.flatMap { it.registeredSources }
        val additional = registerAdditionalSnapshot(sourceKey, builtinRegistrations + trusted + installed) + trusted
        return additional.firstOrNull { it.sourceId == sourceKey }?.source ?: extensionManager.getSource(sourceKey)
    }

    override fun getOrStub(sourceKey: Long): Source {
        return get(sourceKey)
            ?: StubSource(id = sourceKey, lang = "", name = "Stub($sourceKey)")
    }

    override fun getOnlineSources(): List<HttpSource> {
        return getCatalogueSources().filterIsInstance<HttpSource>()
    }

    override fun getCatalogueSources(): List<CatalogueSource> {
        return canonicalCatalogueSources(extensionManager.installedExtensions.value)
    }

    private fun canonicalCatalogueSources(extensions: List<InstalledExtension>): List<CatalogueSource> {
        return canonicalQuerySources(extensions).filterIsInstance<CatalogueSource>()
    }

    override fun getQuerySources(): List<Source> = canonicalQuerySources(extensionManager.installedExtensions.value)

    private fun canonicalQuerySources(extensions: List<InstalledExtension>): List<Source> {
        val trusted = additionalSourceRegistrations()
        val installed = extensions.flatMap { it.registeredSources }
        val additional = registerAdditionalSnapshot(known = builtinRegistrations + trusted + installed) + trusted
        val registrations = builtinRegistrations + additional + installed
        val candidates = builtinSources + additional.map { it.source } + extensions.flatMap { it.sources }
        val ids = mutableSetOf<Long>()
        val unknown = mutableListOf<Source>()
        return candidates.filter { source ->
            val id = registrations.firstOrNull { it.source === source }?.sourceId
            if (id != null) {
                ids.add(id)
            } else {
                if (unknown.any { it === source }) {
                    false
                } else {
                    unknown += source
                    true
                }
            }
        }
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

fun <T : Source> selectEnabledCatalogueSourceCandidates(
    sources: List<T>,
    enabledLanguages: Set<String>,
    disabledSources: Set<String>,
): List<T> = sources.filter { source ->
    source.lang in enabledLanguages && source.id.toString() !in disabledSources
}

/** Uses the same eligibility policy as legacy catalogue discovery. */
fun SourceManager.getEnabledQuerySourceCandidates(preferences: DesktopAppPreferences): List<Source> =
    selectEnabledCatalogueSourceCandidates(
        getQuerySources(),
        preferences.enabledLanguages.get(),
        preferences.disabledSources.get(),
    )

/** Online-source variant of [getEnabledCatalogueSourceCandidates]. */
fun SourceManager.getEnabledOnlineSourceCandidates(preferences: DesktopAppPreferences): List<HttpSource> {
    val enabledLanguages = preferences.enabledLanguages.get()
    val disabledSources = preferences.disabledSources.get()
    return getOnlineSources().filter { source ->
        source.lang in enabledLanguages && source.id.toString() !in disabledSources
    }
}
