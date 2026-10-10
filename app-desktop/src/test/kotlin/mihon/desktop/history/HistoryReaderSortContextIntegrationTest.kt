package mihon.desktop.history

import kotlinx.coroutines.runBlocking
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.ui.library.MangaDetailScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date

@Isolated
class HistoryReaderSortContextIntegrationTest {
    @Test fun `actual history context honors every shared reader sort without changing the selected chapter`(@TempDir folder: File) = scenario(folder, false)

    @Test fun `actual detail context honors every shared reader sort without changing the selected chapter`(@TempDir folder: File) = scenario(folder, true)

    private fun scenario(folder: File, detail: Boolean) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        try {
            val cases = listOf(
                Manga.CHAPTER_SORTING_SOURCE to listOf("Alpha", "Bravo", "Charlie"),
                Manga.CHAPTER_SORTING_NUMBER to listOf("Bravo", "Charlie", "Alpha"),
                Manga.CHAPTER_SORTING_UPLOAD_DATE to listOf("Charlie", "Alpha", "Bravo"),
                Manga.CHAPTER_SORTING_ALPHABET to listOf("Charlie", "Bravo", "Alpha"),
            )
            cases.forEachIndexed { index, (sorting, expected) ->
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 42,
                            url = "/sorting-$index",
                            title = "Sort $index",
                            chapterFlags = sorting,
                        ),
                    ),
                ).single()
                val chapters = Injekt.get<ChapterRepository>().addAll(
                    listOf(
                        Chapter.create().copy(mangaId = manga.id, url = "/charlie", name = "Charlie", sourceOrder = 30, chapterNumber = 2.0, dateUpload = 30),
                        Chapter.create().copy(mangaId = manga.id, url = "/alpha", name = "Alpha", sourceOrder = 10, chapterNumber = 1.0, dateUpload = 20, lastPageRead = 4),
                        Chapter.create().copy(mangaId = manga.id, url = "/bravo", name = "Bravo", sourceOrder = 20, chapterNumber = 3.0, dateUpload = 10),
                    ),
                )
                val target = chapters.single { it.name == "Alpha" }
                Injekt.get<UpsertHistory>().await(HistoryUpdate(target.id, Date(), 1))
                val model = HistoryScreenModelFactory.create()
                try {
                    model.loadHistory()
                    val request = requireNotNull(
                        if (detail) {
                            MangaDetailScreenModel(manga.id, readingProgress = Injekt.get()).readerRequest(manga, chapters, target)
                        } else {
                            model.readerRequestFor(model.state.value.items.single { it.mangaId == manga.id })
                        },
                    )
                    assertEquals(expected, request.chapters.map { it.name }, "Shared reader sort $sorting")
                    assertEquals(target.id, request.chapterId)
                    assertEquals(expected.indexOf("Alpha"), request.currentChapterIndex)
                    assertEquals(4, request.initialPage)
                    assertNotNull(request.resumeSnapshot)
                } finally {
                    model.onDispose()
                }
            }
        } finally {
            context.closeAndJoin()
        }
    }
}
