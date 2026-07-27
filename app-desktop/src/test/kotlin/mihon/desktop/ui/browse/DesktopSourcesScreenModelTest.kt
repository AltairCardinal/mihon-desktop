package mihon.desktop.ui.browse

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import mihon.domain.source.model.SourceScreenAction
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceMembershipCandidate
import tachiyomi.domain.source.service.SourceMembershipPreferences
import tachiyomi.domain.source.service.SourceMembershipProjection
import java.util.prefs.Preferences

class DesktopSourcesScreenModelTest {
    @Test
    fun `production model delegates installed extension membership and local marker to shared projection`() = runTest {
        val root = Preferences.userRoot().node("/mihon/desktop-source-shared-projection/${System.nanoTime()}")
        try {
            val local = FakeSource(0, "fr", "Local")
            val installed = FakeSource(1, "en", "Installed")
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(root)).apply {
                enabledLanguages.set(setOf("en"))
                disabledSources.set(setOf("9"))
                pinnedSources.set(setOf(installed.id.toString()))
                lastUsedSource.set(installed.id)
            }
            val candidates = slot<List<SourceMembershipCandidate>>()
            val membership = slot<SourceMembershipPreferences>()
            val projected = Source(99, "en", "Shared projection result", false, false)
            val projection = mockk<SourceMembershipProjection> {
                every { project(capture(candidates), capture(membership)) } returns listOf(projected)
            }

            val model = DesktopSourcesScreenModel(
                sourceManager = FakeDesktopSourceManager(listOf(installed, local)),
                preferences = preferences,
                coroutineScope = this,
                membershipProjection = projection,
            )

            val content = awaitContent(model) { it is SourceScreenContent.Content } as SourceScreenContent.Content
            assertEquals(listOf(projected), content.sources)
            assertEquals(listOf(installed.id, local.id), candidates.captured.map { it.source.id })
            assertTrue(candidates.captured.single { it.source.id == local.id }.isLocal)
            assertEquals(setOf("en"), membership.captured.enabledLanguages)
            assertEquals(setOf("9"), membership.captured.disabledSourceIds)
            assertEquals(setOf(installed.id.toString()), membership.captured.pinnedSourceIds)
            assertEquals(installed.id, membership.captured.lastUsedSourceId)
            verify(atLeast = 1) { projection.project(any(), any()) }
            model.onDispose()
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `production model reduces candidates and consumes pin result exactly once`() = runTest {
        val root = Preferences.userRoot().node("/mihon/desktop-source-model/${System.nanoTime()}")
        try {
            val alpha = FakeSource(1, "en", "Alpha")
            val pinned = FakeSource(2, "en", "Pinned")
            val hidden = FakeSource(3, "en", "Hidden")
            val french = FakeSource(4, "fr", "French")
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(root)).apply {
                enabledLanguages.set(setOf("en"))
                disabledSources.set(setOf(hidden.id.toString()))
                pinnedSources.set(setOf(pinned.id.toString()))
                lastUsedSource.set(alpha.id)
            }
            val model = DesktopSourcesScreenModel(FakeDesktopSourceManager(listOf(french, hidden, pinned, alpha)), preferences, this)
            val content = awaitContent(model) { it is SourceScreenContent.Content } as SourceScreenContent.Content
            assertEquals(listOf(alpha.id, alpha.id, pinned.id), content.sources.map { it.id })
            assertTrue(content.sources[1].isUsedLast)
            assertTrue(Pin.Actual in content.sources[2].pin)
            model.togglePin(pinned)
            val event = model.state.value.sourceState.pendingEvent as SourceScreenEvent.Pinned
            assertEquals(false, event.pinned)
            assertEquals(emptySet<String>(), preferences.pinnedSources.get())
            val wrongId = model.state.value
            model.consumeEvent(event.id + 1)
            assertSame(wrongId, model.state.value)
            model.consumeEvent(event.id)
            assertNull(model.state.value.sourceState.pendingEvent)
            val consumed = model.state.value
            model.consumeEvent(event.id)
            assertSame(consumed, model.state.value)
            root.removeNode()
            model.togglePin(pinned)
            val failure = model.state.value.sourceState.pendingEvent as SourceScreenEvent.ActionFailed
            assertEquals(SourceScreenAction.PIN, failure.action)
            assertEquals("source pin failed", failure.message)
            model.onDispose()
        } finally {
            runCatching { root.removeNode() }
        }
    }
    @Test
    fun `production model publishes retryable failure and retries the source flow`() = runTest {
        val root = Preferences.userRoot().node("/mihon/desktop-source-retry/${System.nanoTime()}")
        try {
            val source = FakeSource(5, "en", "Retry")
            var attempts = 0
            val sourceFlow = flow {
                if (attempts++ == 0) throw IllegalStateException("offline")
                emit(listOf(source))
            }
            val sourceManager = FakeDesktopSourceManager(emptyList(), sourceFlow)
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(root)).apply { enabledLanguages.set(setOf("en")) }
            val model = DesktopSourcesScreenModel(sourceManager, preferences, coroutineScope = this)
            val failure = awaitContent(model) { it is SourceScreenContent.Failure }
            assertTrue((failure as SourceScreenContent.Failure).retryable)
            assertInstanceOf(SourceScreenEvent.ActionFailed::class.java, model.state.value.sourceState.pendingEvent)
            model.retry()
            val content = awaitContent(model) { it is SourceScreenContent.Content } as SourceScreenContent.Content
            assertEquals(listOf(source.id), content.sources.map { it.id })
            model.onDispose()
        } finally {
            root.removeNode()
        }
    }
    private suspend fun awaitContent(model: DesktopSourcesScreenModel, predicate: (SourceScreenContent) -> Boolean) =
        withTimeout(5_000) { model.state.map { it.sourceState.content }.first(predicate) }
}
