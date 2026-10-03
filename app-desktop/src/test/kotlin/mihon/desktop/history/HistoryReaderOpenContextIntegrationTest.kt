package mihon.desktop.history

import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.test.http.HistoryCatalogTestFixture
import mihon.desktop.test.http.HistoryCatalogTestSource
import mihon.desktop.test.http.HistoryTestModeBridge
import mihon.desktop.test.http.HistoryTestModeController
import mihon.desktop.test.http.testHttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Isolated
class HistoryReaderOpenContextIntegrationTest {
    @Test
    fun `history adopts same selected unfinished chapter page and atomic snapshot`(@TempDir folder: File) = scenario(folder, false)

    @Test
    fun `read history target opens official next chapter with fresh baseline`(@TempDir folder: File) = scenario(folder, true)

    @Test
    fun `detail selected unread chapter receives same atomic resume baseline`(@TempDir folder: File) = scenario(folder, false, detail = true)

    @Test
    fun `detail selected read chapter receives a fresh atomic baseline without old page`(@TempDir folder: File) = scenario(folder, true, detail = true)

    private fun scenario(folder: File, read: Boolean, detail: Boolean = false) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}"
        val fixture = HistoryCatalogTestFixture(folder, Injekt.get(), Injekt.get(), Injekt.get<NetworkHelper>().client, base, verifyProfile = { check(folder.isDirectory) })
        val controller = HistoryTestModeController(HistoryScreenModelFactory.create(), fixture)
        HistoryTestModeBridge.install(controller)
        val model = HistoryScreenModelFactory.create()
        try {
            fixture.execute("seed")
            assertTrue(folder.resolve("history-catalog-fixture/sender.db").isFile)
            val manga = requireNotNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(HistoryCatalogTestSource.MANGA_URL, HistoryCatalogTestSource.SOURCE_ID))
            val chapters = Injekt.get<ChapterRepository>()
            val middle = chapters.getChapterByMangaId(manga.id).single()
            val selected = if (read) {
                chapters.update(ChapterUpdate(middle.id, read = true))
                chapters.addAll(listOf(middle.copy(id = -1, url = "/chapter/3", name = "Chapter 3", chapterNumber = 3.0, sourceOrder = -1, read = true, lastPageRead = 9))).single()
            } else {
                middle
            }
            model.loadHistory()
            val item = model.state.value.items.single()
            val expected = requireNotNull(Injekt.get<RecordReadingProgress>().openChapter(ReaderChapterIdentity(manga.id, manga.source, manga.url, selected.id, selected.url)))
            val opened = requireNotNull(
                if (detail) {
                    mihon.desktop.ui.library.MangaDetailScreenModel(manga.id, readingProgress = Injekt.get())
                        .readerRequest(manga, chapters.getChapterByMangaId(manga.id), selected)
                } else {
                    model.readerRequestFor(item)
                },
            )
            assertEquals(selected.id, opened.chapterId)
            assertEquals(if (read) 0 else 1, opened.initialPage)
            assertEquals(expected.snapshot, opened.resumeSnapshot)
            assertEquals(0, fixture.chapterCalls.get(), "History may not request source directory")
        } finally {
            model.onDispose()
            controller.close()
            HistoryTestModeBridge.clear(controller)
            fixture.close()
            server.stop(0, 0)
            context.closeAndJoin()
        }
    }
}
