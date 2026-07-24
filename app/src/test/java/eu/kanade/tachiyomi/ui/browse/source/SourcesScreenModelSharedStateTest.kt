package eu.kanade.tachiyomi.ui.browse.source

import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source

class SourcesScreenModelSharedStateTest {
    private val source = Source(7, "en", "Example", true, false)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `production ScreenModel renders reducer output and consumes action results once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val getSources = mockk<GetEnabledSources>()
        val toggleSource = mockk<ToggleSource>()
        val togglePin = mockk<ToggleSourcePin>()
        every { getSources.subscribe() } returns flowOf(listOf(source))
        every { toggleSource.await(source, false) } returns Unit
        every { toggleSource.await(source, true) } throws AssertionError("enabled source must be disabled")
        every { togglePin.await(any()) } returns Unit
        val model = SourcesScreenModel(getSources, toggleSource, togglePin)

        val content = awaitContent(model) { it is SourceScreenContent.Content } as SourceScreenContent.Content
        assertEquals(listOf(source), content.sources)
        awaitItems(model)

        model.toggleSource(source)
        val disabled = model.sourceState.value.pendingEvent as SourceScreenEvent.Disabled
        assertTrue(disabled.disabled)
        model.consumeSourceEvent(disabled.id)
        val consumed = model.sourceState.value
        model.consumeSourceEvent(disabled.id)
        assertSame(consumed, model.sourceState.value)

        model.togglePin(source)
        val pinned = model.sourceState.value.pendingEvent as SourceScreenEvent.Pinned
        assertTrue(pinned.pinned)
        verify(exactly = 1) { toggleSource.await(source, false) }
        verify(exactly = 0) { toggleSource.await(source, true) }
        verify(exactly = 1) { togglePin.await(source) }
    }

    @Test
    fun `production failure is retryable through the same reducer`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val getSources = mockk<GetEnabledSources>()
        every { getSources.subscribe() } returnsMany listOf(
            flow { throw IllegalStateException("offline") },
            flowOf(listOf(source)),
        )
        val model = SourcesScreenModel(
            getEnabledSources = getSources,
            toggleSource = mockk(relaxed = true),
            toggleSourcePin = mockk(relaxed = true),
        )

        val failed = awaitContent(model) { it is SourceScreenContent.Failure } as SourceScreenContent.Failure
        assertTrue(failed.retryable)
        assertInstanceOf(SourceScreenEvent.ActionFailed::class.java, model.sourceState.value.pendingEvent)
        model.consumeSourceEvent(requireNotNull(model.sourceState.value.pendingEvent).id)
        assertNull(model.sourceState.value.pendingEvent)

        model.retrySources()
        awaitContent(model) { it is SourceScreenContent.Content }
        awaitItems(model)
    }

    private suspend fun awaitContent(
        model: SourcesScreenModel,
        predicate: (SourceScreenContent) -> Boolean,
    ) = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { model.sourceState.map { it.content }.first(predicate) }
    }

    private suspend fun awaitItems(model: SourcesScreenModel) =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { model.state.first { it.items.size == 2 } }
        }
}
