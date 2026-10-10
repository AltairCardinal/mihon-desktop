package mihon.desktop.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderChapterRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderSessionSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.ReaderCatalogPreparation
import tachiyomi.domain.reader.model.ReaderOpenContext
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot

class ReaderCatalogLifetimeTest {
    @Test
    fun `post fetch mapper failure remains background failure and preserves reader`() = runBlocking {
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val done = CompletableDeferred<Unit>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                failures.add(error)
                done.complete(Unit)
            },
        )
        val context = DesktopReaderChapterContext(1, 7, "/1", "Work", "One", 1.0, 0, 0, false, mangaId = 5)
        val model = ReaderScreenModel(initialSessionState = DesktopReaderSessionState(context, ReaderSessionSnapshot.initial(ReaderChapterId(1))), ownedRuntimeScope = scope)
        val refs = listOf(ReaderChapterRef(1, "/1", "One"))
        val chapter = Chapter.create().copy(id = 1, mangaId = 5, url = "/1")
        val opened = ReaderOpenContext(
            Manga.create().copy(id = 5, source = 7, url = "/work"),
            chapter,
            0,
            ReadingSyncSnapshot(ReadingSyncScope("space", 1, "actor", 1)),
            true,
        )
        val mapped = CompletableDeferred<Unit>()
        try {
            model.attachCatalog(refs, opened, ReaderCatalogPreparation { listOf(chapter) }) {
                mapped.complete(Unit)
                error("download metadata unavailable")
            }
            withTimeout(5_000) { mapped.await() }
            scope.coroutineContext[Job]!!.children.forEach { it.join() }
            // Job completion calls the exception handler synchronously on the worker.
            assertTrue(failures.isEmpty(), "Mapper failure escaped Reader background boundary: $failures")
            assertEquals(refs, model.state.value.chapterRefs)
            assertEquals(context, model.state.value.context)
        } finally {
            model.onDispose()
            scope.cancel()
        }
    }

    @Test
    fun `late directory cannot attach after navigating away and back to the same chapter`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = DesktopReaderChapterContext(
            chapterId = 1, mangaId = 5, sourceId = 7, chapterUrl = "/1",
            mangaTitle = "Work", chapterTitle = "One", chapterNumber = 1.0, chapterIndex = 0, initialPage = 0, wasRead = false,
        )
        lateinit var model: ReaderScreenModel
        model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(context, ReaderSessionSnapshot.initial(ReaderChapterId(1))),
            ownedRuntimeScope = scope,
            onChapterActivated = { next -> DesktopReaderSessionState(next, ReaderSessionSnapshot.initial(ReaderChapterId(next.chapterId))) },
        )
        val initial = listOf(ReaderChapterRef(id = 1, url = "/1", name = "One"))
        val chapter = Chapter.create().copy(id = 1, mangaId = 5, url = "/1")
        val opened = ReaderOpenContext(
            Manga.create().copy(id = 5, source = 7, url = "/work"),
            chapter,
            0,
            ReadingSyncSnapshot(ReadingSyncScope("space", 1, "actor", 1)),
            true,
        )
        val finished = CompletableDeferred<Unit>()
        try {
            model.attachCatalog(
                initial,
                opened,
                ReaderCatalogPreparation {
                    entered.complete(Unit)
                    release.await()
                    listOf(chapter)
                },
            ) {
                finished.complete(Unit)
                initial + ReaderChapterRef(id = 2, url = "/2", name = "Two")
            }
            withTimeout(5_000) { entered.await() }
            model.activateChapter(context.copy(chapterId = 2, chapterUrl = "/2"))
            model.activateChapter(context)
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }
            scope.coroutineContext[Job]!!.children.forEach { it.join() }
            assertEquals(initial, model.state.value.chapterRefs)
        } finally {
            release.complete(Unit)
            model.onDispose()
            scope.cancel()
        }
    }
}
