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
import mihon.domain.source.model.SourceScreenAction
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenReducer
import mihon.domain.source.model.SourceScreenState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.FixedMainSourceMembershipProjection
import tachiyomi.domain.source.service.SourceMembershipCandidate
import tachiyomi.domain.source.service.SourceMembershipPreferences
import tachiyomi.domain.source.service.SourceMembershipProjection
import tachiyomi.domain.source.service.SourceManager

data class DesktopSourcesState(val sourceState: SourceScreenState = SourceScreenState(), val catalogueSources: List<CatalogueSource> = emptyList(), val enabledLanguages: Set<String> = emptySet())
class DesktopSourcesScreenModel(
    private val sourceManager: SourceManager, private val preferences: DesktopAppPreferences, coroutineScope: CoroutineScope? = null,
    private val reducer: SourceScreenReducer = SourceScreenReducer(),
    private val membershipProjection: SourceMembershipProjection = FixedMainSourceMembershipProjection,
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
                Triple(
                    installed,
                    installed.toDomainSources(
                        enabledLanguages = enabledLanguages,
                        disabledSourceIds = disabledSources,
                        pinnedSourceIds = pinnedSources,
                        lastUsedSourceId = lastUsedSource,
                    ),
                    enabledLanguages,
                )
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
        return DesktopSourcesState(
            reducer.loaded(
                SourceScreenState(),
                installed.toDomainSources(
                    enabledLanguages = enabled,
                    disabledSourceIds = preferences.disabledSources.get(),
                    pinnedSourceIds = preferences.pinnedSources.get(),
                    lastUsedSourceId = preferences.lastUsedSource.get(),
                ),
            ),
            installed,
            enabled,
        )
    }
    private fun List<CatalogueSource>.toDomainSources(
        enabledLanguages: Set<String>,
        disabledSourceIds: Set<String>,
        pinnedSourceIds: Set<String>,
        lastUsedSourceId: Long,
    ): List<Source> = membershipProjection.project(
        candidates = map { source ->
            SourceMembershipCandidate(
                source = Source(source.id, source.lang, source.name, source.supportsLatest, isStub = false),
                isLocal = source.id == LOCAL_SOURCE_ID,
            )
        },
        preferences = SourceMembershipPreferences(
            enabledLanguages = enabledLanguages,
            disabledSourceIds = disabledSourceIds,
            pinnedSourceIds = pinnedSourceIds,
            lastUsedSourceId = lastUsedSourceId,
        ),
    )

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
    }
}
