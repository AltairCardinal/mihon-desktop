package mihon.desktop.tracking

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.desktop.compat.AndroidCompat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.service.EnhancedTrackerContext
import tachiyomi.domain.track.service.EnhancedTrackerContextProvider

/** Resolves enhanced tracker configuration from the same installed source instances used for browsing. */
class DesktopEnhancedTrackerContextProvider(
    sourceManager: SourceManager? = null,
) : EnhancedTrackerContextProvider {
    private var sourceManager: SourceManager? = sourceManager
    private val mutableContexts = MutableStateFlow<List<EnhancedTrackerContext>>(emptyList())
    override val contexts: StateFlow<List<EnhancedTrackerContext>> = mutableContexts

    init {
        refresh()
    }

    override fun refresh() {
        mutableContexts.value = sourceManager?.getCatalogueSources().orEmpty().mapNotNull(::contextFor)
    }

    fun attach(sourceManager: SourceManager) {
        this.sourceManager = sourceManager
        refresh()
    }

    fun sourceClient(sourceId: Long) = (sourceManager?.get(sourceId) as? HttpSource)?.client

    private fun contextFor(source: Source): EnhancedTrackerContext? {
        val className = source::class.qualifiedName ?: source.javaClass.name
        val trackerId = desktopEnhancedTrackerId(className) ?: return null
        val httpSource = source as? HttpSource ?: return null
        val preferences = (source as? ConfigurableSource)?.let {
            AndroidCompat.context.getSharedPreferences("source_${source.id}", 0).getAll()
        }.orEmpty()
        val normalized = preferences.mapKeys { it.key.uppercase() }.mapValues { it.value?.toString().orEmpty() }
        val baseUrl = when (trackerId) {
            8L -> normalized["APIURL"].orEmpty().trimEnd('/')
            else -> httpSource.baseUrl.trimEnd('/')
        }
        val apiKey = normalized["APIKEY"]
        val credentialKeys = normalized.keys.filter { it.contains("USER") || it.contains("PASSWORD") }
        val credentialsComplete = credentialKeys.isEmpty() || credentialKeys.all { !normalized[it].isNullOrBlank() }
        if (baseUrl.isBlank() || !credentialsComplete || (trackerId == 8L && apiKey.isNullOrBlank())) return null
        return EnhancedTrackerContext(
            trackerId = trackerId,
            sourceId = source.id,
            sourceClassName = className,
            baseUrl = baseUrl,
            apiKey = apiKey,
            deleteDownloadsOnServer = normalized["TRACKER DELETE"]?.toBooleanStrictOrNull() ?: false,
        )
    }

}

internal fun desktopEnhancedTrackerId(sourceClassName: String): Long? = when (sourceClassName) {
    "eu.kanade.tachiyomi.extension.all.komga.Komga" -> 6L
    "eu.kanade.tachiyomi.extension.all.kavita.Kavita" -> 8L
    "eu.kanade.tachiyomi.extension.all.tachidesk.Tachidesk" -> 9L
    else -> null
}

internal fun desktopEnhancedTrackerContext(
    contexts: List<EnhancedTrackerContext>,
    trackerId: Long,
    sourceId: Long,
): EnhancedTrackerContext? = contexts.firstOrNull { context ->
    context.trackerId == trackerId &&
        context.sourceId == sourceId &&
        desktopEnhancedTrackerId(context.sourceClassName) == trackerId &&
        context.configured &&
        (trackerId != 8L || !context.apiKey.isNullOrBlank())
}
