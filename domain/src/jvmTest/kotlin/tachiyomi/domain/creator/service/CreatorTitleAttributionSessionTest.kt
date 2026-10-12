package tachiyomi.domain.creator.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.UUID
import java.util.prefs.Preferences

class CreatorTitleAttributionSessionTest {
    @Test
    fun `opening previews the fixed work and cancelling does not enable the source`() = fixture {
            session,
            editor,
            _,
            writer,
        ->
        session.open(manga)
        assertTrue(session.state.value.open)
        assertEquals(listOf("Artist", "Circle"), session.state.value.preview.map { it.displayName })
        session.select(true)
        session.dismiss()
        assertFalse(session.state.value.open)
        assertFalse(editor.policy.isEnabled(10L))
        coVerify(exactly = 0) { writer.indexLibraryMangaBatch(any()) }
    }

    @Test
    fun `save button session invokes the real editor and closing clears the draft`() = fixture {
            session,
            editor,
            _,
            writer,
        ->
        session.open(manga)
        session.select(true)
        session.save().join()
        assertTrue(editor.policy.isEnabled(10L))
        assertFalse(session.state.value.open)
        assertEquals(CreatorTitleAttributionFeedback.ENABLED, session.state.value.feedback)
        coVerify(exactly = 1) {
            writer.indexLibraryMangaBatch(
                match {
                    it.single().mentions.map { mention -> mention.displayName } ==
                        listOf("Artist", "Circle")
                },
            )
        }
        session.open(manga)
        session.select(false)
        session.dismiss()
        session.open(manga)
        assertTrue(session.state.value.draftEnabled)
    }

    @Test
    fun `failed save keeps the dialog and draft while restoring the preference`() = fixture {
            session,
            editor,
            _,
            writer,
        ->
        coEvery { writer.indexLibraryMangaBatch(any()) } throws IllegalStateException("磁盘已满")
        session.open(manga)
        session.select(true)
        session.save().join()
        assertTrue(session.state.value.open)
        assertTrue(session.state.value.draftEnabled)
        assertFalse(session.state.value.busy)
        assertEquals("磁盘已满", session.state.value.error)
        assertFalse(editor.policy.isEnabled(10L))
    }

    @Test
    fun `reindex is explicit and requires saved source choice`() = fixture { session, _, source, writer ->
        coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } returns listOf(manga)
        session.open(manga)
        session.select(true)
        session.reindex().join()
        coVerify(exactly = 0) { writer.indexLibraryMangaBatch(any()) }
        session.save().join()
        session.open(manga)
        session.reindex().join()
        assertEquals(1, session.state.value.progress)
        assertEquals(CreatorTitleAttributionFeedback.REINDEXED, session.state.value.feedback)
        coVerify(exactly = 2) { writer.indexLibraryMangaBatch(any()) }
    }

    @Test
    fun `failed second batch retains a visible completed count`() = fixture { session, _, source, writer ->
        val first = (1L..250L).map { manga.copy(id = it) }
        coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } returns first
        coEvery { source.getLibraryMangaForCreatorIndex(250L, 250L) } returns listOf(manga.copy(id = 251L))
        coEvery { writer.indexLibraryMangaBatch(match { it.size == 1 }) } throws IllegalStateException("磁盘已满")
        session.open(manga)
        session.reindex().join()
        assertEquals(250, session.state.value.progress)
        assertEquals("磁盘已满", session.state.value.error)
        assertEquals("FAILED", session.state.value.feedback?.name)
        assertFalse(session.state.value.reindexing)
    }

    private val manga = Manga.create().copy(
        id = 8L,
        source = 10L,
        favorite = true,
        title = "[Circle (Artist)] Work",
        author = "Uploader",
    )

    private fun fixture(
        block: suspend (
            CreatorTitleAttributionSession,
            CreatorTitleAttributionEditor,
            CreatorLibraryMangaSource,
            CreatorLibraryIndexWriter,
        ) -> Unit,
    ) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-tests/title-ui-session-${UUID.randomUUID()}")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val preferences = CreatorDiscoveryPreferences(DesktopPreferenceStore(node))
            val policy = CreatorTitleAttributionPolicy(preferences)
            val repository = mockk<MangaRepository>()
            coEvery { repository.getMangaById(8L) } returns manga
            val source = mockk<CreatorLibraryMangaSource>()
            val writer = mockk<CreatorLibraryIndexWriter>(relaxed = true)
            val editor =
                CreatorTitleAttributionEditor(
                    preferences,
                    repository,
                    source,
                    writer,
                    ExtractCreatorsFromManga(policy),
                    policy,
                )
            block(CreatorTitleAttributionSession(editor, scope), editor, source, writer)
        } finally {
            scope.cancel()
            node.removeNode()
        }
    }
}
