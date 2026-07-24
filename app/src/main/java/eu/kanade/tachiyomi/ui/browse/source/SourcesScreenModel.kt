package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.presentation.browse.SourceUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.domain.source.model.SourceScreenAction
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenReducer
import mihon.domain.source.model.SourceScreenState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeMap

class SourcesScreenModel(
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
    private val toggleSourcePin: ToggleSourcePin = Injekt.get(),
    private val sourceReducer: SourceScreenReducer = SourceScreenReducer(),
) : StateScreenModel<SourcesScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()
    private val _sourceState = MutableStateFlow(SourceScreenState())
    val sourceState = _sourceState.asStateFlow()

    init {
        observeSources()
    }

    private fun observeSources() {
        screenModelScope.launchIO {
            getEnabledSources.subscribe()
                .catch { throwable ->
                    logcat(LogPriority.ERROR, throwable)
                    _sourceState.update { sourceReducer.loadFailed(it, throwable.message ?: "source load failed") }
                    mutableState.update { it.copy(isLoading = false) }
                    _events.send(Event.FailedFetchingSources)
                }
                .collectLatest(::collectLatestSources)
        }
    }

    private fun collectLatestSources(sources: List<Source>) {
        _sourceState.update { sourceReducer.loaded(it, sources) }
        val reducedSources = (sourceState.value.content as? SourceScreenContent.Content)?.sources.orEmpty()
        mutableState.update { state ->
            val map = TreeMap<String, MutableList<Source>> { d1, d2 ->
                // Sources without a lang defined will be placed at the end
                when {
                    d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                    d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                    d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                    d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                    d1 == "" && d2 != "" -> 1
                    d2 == "" && d1 != "" -> -1
                    else -> d1.compareTo(d2)
                }
            }
            val byLang = reducedSources.groupByTo(map) {
                when {
                    it.isUsedLast -> LAST_USED_KEY
                    Pin.Actual in it.pin -> PINNED_KEY
                    else -> it.lang
                }
            }

            state.copy(
                isLoading = false,
                items = byLang
                    .flatMap {
                        listOf(
                            SourceUiModel.Header(it.key),
                            *it.value.map { source ->
                                SourceUiModel.Item(source)
                            }.toTypedArray(),
                        )
                    }
                    .toImmutableList(),
            )
        }
    }

    fun toggleSource(source: Source) {
        runCatching { toggleSource.await(source, enable = false) }
            .onSuccess { _sourceState.update { sourceReducer.disabled(it, source.id, disabled = true) } }
            .onFailure { actionFailed(SourceScreenAction.DISABLE, it) }
    }

    fun togglePin(source: Source) {
        runCatching { toggleSourcePin.await(source) }
            .onSuccess { _sourceState.update { sourceReducer.pinned(it, source.id, Pin.Actual !in source.pin) } }
            .onFailure { actionFailed(SourceScreenAction.PIN, it) }
    }

    fun consumeSourceEvent(eventId: Long) {
        _sourceState.update { sourceReducer.consumeEvent(it, eventId) }
    }

    fun retrySources() {
        if (sourceState.value.content !is SourceScreenContent.Failure) return
        _sourceState.update(sourceReducer::loading)
        mutableState.update { it.copy(isLoading = true) }
        observeSources()
    }

    private fun actionFailed(action: SourceScreenAction, throwable: Throwable) {
        logcat(LogPriority.ERROR, throwable)
        _sourceState.update { sourceReducer.actionFailed(it, action, throwable.message ?: "source action failed") }
        _events.trySend(Event.FailedFetchingSources)
    }

    fun showSourceDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    data class Dialog(val source: Source)

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: ImmutableList<SourceUiModel> = persistentListOf(),
    ) {
        val isEmpty = items.isEmpty()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}
