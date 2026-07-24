package mihon.desktop.ui.browse

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.selectEnabledCatalogueSourceCandidates
import mihon.domain.source.model.SourceScreenAction
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenReducer
import mihon.domain.source.model.SourceScreenState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager

data class DesktopSourcesState(val sourceState: SourceScreenState = SourceScreenState(), val catalogueSources: List<CatalogueSource> = emptyList(), val enabledLanguages: Set<String> = emptySet())
class DesktopSourcesScreenModel(
    private val sourceManager: SourceManager, private val preferences: DesktopAppPreferences, coroutineScope: CoroutineScope? = null,
    private val reducer: SourceScreenReducer = SourceScreenReducer(),
) : ScreenModel {
    private val ownerJob = SupervisorJob(coroutineScope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope((coroutineScope?.coroutineContext ?: Dispatchers.Default) + ownerJob)
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<DesktopSourcesState> = mutableState.asStateFlow()
    private var observeJob: Job? = null
    init { observe() }
    fun togglePin(source: CatalogueSource) {
        val id = source.id.toString()
        runCatching {
            val pinned = id in preferences.pinnedSources.get()
            preferences.pinnedSources.getAndSet { current -> if (pinned) current - id else current + id }
            !pinned
        }.onSuccess { pinned ->
            reduce { reducer.pinned(it, source.id, pinned) }
        }.onFailure {
            reduce { state -> reducer.actionFailed(state, SourceScreenAction.PIN, "source pin failed") }
        }
    }
    fun toggleLanguage(language: String) {
        preferences.enabledLanguages.getAndSet { enabled ->
            if (language in enabled) enabled - language else enabled + language
        }
    }
    fun consumeEvent(eventId: Long) {
        reduce { reducer.consumeEvent(it, eventId) }
    }
    fun retry() {
        if (state.value.sourceState.content !is SourceScreenContent.Failure) return
        reduce(reducer::loading)
        observe()
    }
    override fun onDispose() {
        ownerJob.cancel()
    }
    private fun observe() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                sourceManager.catalogueSources,
                preferences.enabledLanguages.changes(),
                preferences.disabledSources.changes(),
                preferences.pinnedSources.changes(),
                preferences.lastUsedSource.changes(),
            ) { installed, enabledLanguages, disabledSources, pinnedSources, lastUsedSource ->
                val candidates = selectEnabledCatalogueSourceCandidates(installed, enabledLanguages, disabledSources)
                Triple(installed, candidates.toDomainSources(pinnedSources, lastUsedSource), enabledLanguages)
            }
                .catch { error ->
                    if (error is CancellationException) throw error
                    reduce { reducer.loadFailed(it, error.message ?: "source load failed") }
                }
                .collect { (installed, sources, enabledLanguages) ->
                    mutableState.update {
                        it.copy(
                            sourceState = reducer.loaded(it.sourceState, sources),
                            catalogueSources = installed,
                            enabledLanguages = enabledLanguages,
                        )
                    }
                }
        }
    }
    private fun reduce(transform: (SourceScreenState) -> SourceScreenState) {
        mutableState.update { current ->
            val reduced = transform(current.sourceState)
            if (reduced === current.sourceState) current else current.copy(sourceState = reduced)
        }
    }
    private fun initialState(): DesktopSourcesState {
        val installed = sourceManager.getCatalogueSources()
        val enabled = preferences.enabledLanguages.get()
        val candidates = selectEnabledCatalogueSourceCandidates(installed, enabled, preferences.disabledSources.get())
        return DesktopSourcesState(reducer.loaded(SourceScreenState(), candidates.toDomainSources(preferences.pinnedSources.get(), preferences.lastUsedSource.get())), installed, enabled)
    }
    private fun List<CatalogueSource>.toDomainSources(
        pinnedSourceIds: Set<String>,
        lastUsedSourceId: Long,
    ): List<Source> = sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).flatMap { source ->
        val pin = if (source.id.toString() in pinnedSourceIds) Pins.pinned else Pins.unpinned
        val item = Source(source.id, source.lang, source.name, source.supportsLatest, isStub = false, pin = pin)
        buildList {
            add(item)
            if (source.id == lastUsedSourceId) add(item.copy(isUsedLast = true, pin = item.pin - Pin.Actual))
        }
    }
}
