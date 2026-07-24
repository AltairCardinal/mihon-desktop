package mihon.domain.source.model

import tachiyomi.domain.source.model.Source

data class SourceScreenState(
    val content: SourceScreenContent = SourceScreenContent.Loading,
    val pendingEvent: SourceScreenEvent? = null,
    val nextEventId: Long = 1,
)

sealed interface SourceScreenContent {
    data object Loading : SourceScreenContent

    data class Content(val sources: List<Source>) : SourceScreenContent

    data object Empty : SourceScreenContent

    data class Failure(
        val message: String,
        val retryable: Boolean = true,
    ) : SourceScreenContent
}

enum class SourceScreenAction {
    LOAD,
    DISABLE,
    PIN,
}

sealed interface SourceScreenEvent {
    val id: Long

    data class Disabled(
        override val id: Long,
        val sourceId: Long,
        val disabled: Boolean,
    ) : SourceScreenEvent

    data class Pinned(
        override val id: Long,
        val sourceId: Long,
        val pinned: Boolean,
    ) : SourceScreenEvent

    data class ActionFailed(
        override val id: Long,
        val action: SourceScreenAction,
        val message: String,
        val retryable: Boolean = true,
    ) : SourceScreenEvent
}

class SourceScreenReducer {
    fun loading(state: SourceScreenState) =
        state.copy(content = SourceScreenContent.Loading, pendingEvent = null)

    fun loaded(state: SourceScreenState, sources: List<Source>) =
        state.copy(
            content =
            sources.takeIf(List<Source>::isNotEmpty)?.let { SourceScreenContent.Content(it.toList()) }
                ?: SourceScreenContent.Empty,
        )

    fun loadFailed(state: SourceScreenState, message: String): SourceScreenState {
        val id = state.nextEventId
        return state.copy(
            content = SourceScreenContent.Failure(message),
            pendingEvent = SourceScreenEvent.ActionFailed(id, SourceScreenAction.LOAD, message),
            nextEventId = id + 1,
        )
    }

    fun disabled(state: SourceScreenState, sourceId: Long, disabled: Boolean) =
        event(state) { SourceScreenEvent.Disabled(it, sourceId, disabled) }

    fun pinned(state: SourceScreenState, sourceId: Long, pinned: Boolean) =
        event(state) { SourceScreenEvent.Pinned(it, sourceId, pinned) }

    fun actionFailed(state: SourceScreenState, action: SourceScreenAction, message: String) =
        event(state) { SourceScreenEvent.ActionFailed(it, action, message) }

    fun consumeEvent(state: SourceScreenState, eventId: Long) =
        if (state.pendingEvent?.id == eventId) state.copy(pendingEvent = null) else state

    private fun event(state: SourceScreenState, create: (Long) -> SourceScreenEvent): SourceScreenState {
        val id = state.nextEventId
        return state.copy(pendingEvent = create(id), nextEventId = id + 1)
    }
}
