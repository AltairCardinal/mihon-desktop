package mihon.desktop.history

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.externalChapterUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class HistoryNavigationPolicyIntegrationTest {
    @Test
    fun `history refs honor scanlator exclusion external targets and all shared skip policies`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-history-policy-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            val remote = listOf("external", "4", "3", "2b", "2", "1").map { key ->
                SChapter.create().apply {
                    url = if (key == "external") externalChapterUrl("https://fixture.invalid/external") else "/$key"
                    name = "Chapter $key"
                    chapter_number = key.removeSuffix("b").toFloatOrNull() ?: 5f
                    scanlator = if (key in setOf("1", "2")) "Excluded" else "Other"
                }
            }
            val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                SManga.create().apply {
                    url = "/policies"
                    title = "Policy work"
                },
                42,
                remote,
            )
            val repository = Injekt.get<ChapterRepository>()
            val chapters = repository.getChapterByMangaId(manga.id).associateBy { it.url }
            repository.update(ChapterUpdate(chapters.getValue("/4").id, read = true, bookmark = true))
            repository.update(ChapterUpdate(chapters.getValue("/1").id, read = true))
            Injekt.get<MangaRepository>().update(MangaUpdate(manga.id, chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED))
            val current = chapters.getValue("/2")
            Injekt.get<UpsertHistory>().await(HistoryUpdate(current.id, Date(), 1))
            Injekt.get<DatabaseHandler>().await { excluded_scanlatorsQueries.insert(manga.id, "Excluded") }
            val model = HistoryScreenModelFactory.create()
            model.loadHistory()
            val item = model.state.value.items.single()
            val request = requireNotNull(model.readerRequestFor(item))
            assertEquals(current.id, request.chapterId)
            assertEquals(listOf("/4", "/3", "/2b", "/2"), request.chapters.map { it.url })
            val duplicate = request.chapters.single { it.url == "/2b" }
            assertTrue(duplicate.isDuplicate && duplicate.isFiltered)
            assertTrue(request.chapters.single { it.id == current.id }.isFiltered)
            fun navigator(read: Boolean = false, filtered: Boolean = false, duplicate: Boolean = false) = ReaderNavigator(request.chapters, request.currentChapterIndex, read, filtered, duplicate)
            assertEquals("/2b", navigator().nextToRead?.url)
            assertEquals("/3", navigator(duplicate = true).nextToRead?.url)
            assertEquals("/4", navigator(filtered = true).nextToRead?.url)
            assertNull(navigator(read = true, filtered = true).nextToRead)
            assertNull(navigator().previousRead)
            Injekt.get<DatabaseHandler>().await { excluded_scanlatorsQueries.insert(manga.id, "Other") }
            model.cancelRead()
            val isolated = requireNotNull(model.readerRequestFor(item))
            assertEquals(listOf(current.id), isolated.chapters.map { it.id })
            assertNull(ReaderNavigator(isolated.chapters, isolated.currentChapterIndex).nextToRead)
            assertNull(ReaderNavigator(isolated.chapters, isolated.currentChapterIndex).previousRead)
            val external = chapters.values.single { it.url.startsWith("external:") }
            Injekt.get<UpsertHistory>().await(HistoryUpdate(external.id, Date(System.currentTimeMillis() + 10), 1))
            model.loadHistory()
            model.cancelRead()
            assertNull(model.readerRequestFor(model.state.value.items.single { it.chapterId == external.id }))
            assertEquals(HistoryReadFailure.TARGET_MISSING, model.state.value.readStatus?.failure)
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }
}
