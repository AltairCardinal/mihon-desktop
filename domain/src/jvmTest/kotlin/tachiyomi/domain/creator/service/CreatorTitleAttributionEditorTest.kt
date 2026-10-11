package tachiyomi.domain.creator.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

class CreatorTitleAttributionEditorTest {
    @Test
    fun `preference flush failure restores the previous source opt in`() = withEditor {
            _,
            repository,
            writer,
            source,
            store,
        ->
        val guarded = object : tachiyomi.core.common.preference.PreferenceStore by store {
            override fun getStringSet(
                key: String,
                defaultValue: Set<String>,
            ): tachiyomi.core.common.preference.Preference<Set<String>> {
                val backing = store.getStringSet(key, defaultValue)
                return object : tachiyomi.core.common.preference.Preference<Set<String>> by backing {
                    override fun set(value: Set<String>) {
                        backing.set(value)
                        if ("10" in value) error("Preference flush failed")
                    }
                }
            }
        }
        val preferences = CreatorDiscoveryPreferences(guarded)
        val policy = CreatorTitleAttributionPolicy(preferences)
        val editor = CreatorTitleAttributionEditor(
            preferences,
            repository,
            source,
            writer,
            ExtractCreatorsFromManga(policy),
            policy,
        )
        coEvery { repository.getMangaById(8L) } returns Manga.create().copy(id = 8L, source = 10L)
        assertThrows(IllegalStateException::class.java) { runBlocking { editor.setEnabled(8L, 10L, true) } }
        assertFalse(policy.isEnabled(10L))
    }

    @Test
    fun `source opt in persists and indexes only the fixed current manga`() = withEditor {
            editor,
            repository,
            writer,
            _,
            store,
        ->
        val manga = Manga.create().copy(
            id = 8L,
            source = 10L,
            favorite = true,
            title = "[Circle (Artist)] Work",
            author = "Uploader",
        )
        coEvery { repository.getMangaById(8L) } returns manga
        assertEquals(listOf("Artist", "Circle"), editor.preview(manga).map { it.displayName })
        assertFalse(editor.policy.isEnabled(10L))
        val change = editor.setEnabled(8L, 10L, true)
        assertEquals(CreatorTitleAttributionChange(true, 1), change)
        assertTrue(CreatorTitleAttributionPolicy(CreatorDiscoveryPreferences(store)).isEnabled(10L))
        assertFalse(editor.policy.isEnabled(11L))
        coVerify(exactly = 1) {
            writer.indexLibraryMangaBatch(
                match {
                    it.single().manga == manga &&
                        it.single().mentions.map { mention -> mention.displayName } == listOf("Artist", "Circle")
                },
            )
        }
    }

    @Test
    fun `stale source and cancelled indexing do not keep a changed preference`() = withEditor {
            editor,
            repository,
            writer,
            _,
            _,
        ->
        val manga = Manga.create().copy(id = 8L, source = 10L, favorite = true)
        coEvery { repository.getMangaById(8L) } returns manga
        assertThrows(IllegalStateException::class.java) { runBlocking { editor.setEnabled(8L, 11L, true) } }
        coEvery { writer.indexLibraryMangaBatch(any()) } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { runBlocking { editor.setEnabled(8L, 10L, true) } }
        assertFalse(editor.policy.isEnabled(10L))
    }

    @Test
    fun `explicit rebuild streams bounded pages and skips other sources`() = withEditor {
            editor,
            _,
            writer,
            source,
            store,
        ->
        CreatorDiscoveryPreferences(store).titleAttributionSources().set(setOf("10"))
        val first = Manga.create().copy(id = 1L, source = 10L, favorite = true, title = "[Circle (Artist)] Work")
        val second = first.copy(id = 2L, source = 11L)
        coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } returns listOf(first, second)
        val progress = mutableListOf<Int>()
        assertEquals(1, editor.reindexLibrary(10L, progress::add))
        assertEquals(listOf(1), progress)
        coVerify(exactly = 1) { writer.indexLibraryMangaBatch(match { it.size == 1 && it.single().manga.id == 1L }) }
    }

    private fun withEditor(
        block: suspend (
            CreatorTitleAttributionEditor,
            MangaRepository,
            CreatorLibraryIndexWriter,
            CreatorLibraryMangaSource,
            DesktopPreferenceStore,
        ) -> Unit,
    ) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-tests/title-attribution-${UUID.randomUUID()}")
        try {
            val store = DesktopPreferenceStore(node)
            val preferences = CreatorDiscoveryPreferences(store)
            val policy = CreatorTitleAttributionPolicy(preferences)
            val repository = mockk<MangaRepository>()
            val writer = mockk<CreatorLibraryIndexWriter>(relaxed = true)
            val source = mockk<CreatorLibraryMangaSource>()
            val editor = CreatorTitleAttributionEditor(
                preferences,
                repository,
                source,
                writer,
                ExtractCreatorsFromManga(policy),
                policy,
            )
            block(editor, repository, writer, source, store)
        } finally {
            node.removeNode()
        }
    }
}
