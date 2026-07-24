package mihon.domain.source.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source

class SourceScreenStateTest {
    private val reducer = SourceScreenReducer()
    private val source = Source(7, "en", "Example", true, false)

    @Test
    fun `loading content empty failure and retry are immutable reducer states`() {
        val initial = SourceScreenState()
        assertInstanceOf(SourceScreenContent.Loading::class.java, initial.content)

        val input = mutableListOf(source)
        val content = reducer.loaded(initial, input)
        input.clear()
        assertEquals(listOf(source), (content.content as SourceScreenContent.Content).sources)
        assertInstanceOf(SourceScreenContent.Empty::class.java, reducer.loaded(content, emptyList()).content)

        val failed = reducer.loadFailed(content, "offline")
        val failure = failed.content as SourceScreenContent.Failure
        assertEquals("offline", failure.message)
        assertTrue(failure.retryable)
        assertInstanceOf(SourceScreenContent.Loading::class.java, reducer.loading(failed).content)
    }

    @Test
    fun `disabled and pinned results are consumed exactly once`() {
        val disabled = reducer.disabled(SourceScreenState(), source.id, disabled = true)
        val disabledEvent = disabled.pendingEvent as SourceScreenEvent.Disabled
        assertEquals(source.id, disabledEvent.sourceId)
        assertTrue(disabledEvent.disabled)

        val wrongId = reducer.consumeEvent(disabled, disabledEvent.id + 100)
        assertSame(disabled, wrongId)
        assertSame(disabledEvent, wrongId.pendingEvent)
        val consumed = reducer.consumeEvent(wrongId, disabledEvent.id)
        assertNull(consumed.pendingEvent)
        assertSame(consumed, reducer.consumeEvent(consumed, disabledEvent.id))

        val pinned = reducer.pinned(consumed, source.id, pinned = true)
        val pinnedEvent = pinned.pendingEvent as SourceScreenEvent.Pinned
        assertEquals(disabledEvent.id + 1, pinnedEvent.id)
        assertTrue(pinnedEvent.pinned)
    }

    @Test
    fun `load and action failures publish retryable one shot events`() {
        val loadFailed = reducer.loadFailed(SourceScreenState(), "network")
        val loadEvent = loadFailed.pendingEvent as SourceScreenEvent.ActionFailed
        assertEquals(SourceScreenAction.LOAD, loadEvent.action)
        assertTrue(loadEvent.retryable)

        val actionFailed = reducer.actionFailed(
            reducer.consumeEvent(loadFailed, loadEvent.id),
            SourceScreenAction.PIN,
            "denied",
        )
        val actionEvent = actionFailed.pendingEvent as SourceScreenEvent.ActionFailed
        assertEquals(SourceScreenAction.PIN, actionEvent.action)
        assertEquals("denied", actionEvent.message)
        assertEquals(loadEvent.id + 1, actionEvent.id)
    }
}
