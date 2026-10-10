package tachiyomi.domain.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.ReaderCatalogCompletion
import tachiyomi.domain.reader.interactor.ReaderCatalogPreparation
import tachiyomi.domain.reader.model.ReaderOpenContext
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot

abstract class ReaderCatalogCompletionContract {
    @Test
    fun `catalog attempt is bounded and timeout cancels its source silently`() = kotlinx.coroutines.test.runTest {
        var stopped = false
        val completion = ReaderCatalogCompletion(
            opened,
            ReaderCatalogPreparation {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    stopped = true
                }
            },
        )
        val result = async { completion.await() }
        try {
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertTrue(result.isCompleted, "Directory preparation exceeded its bound")
            assertNull(result.await())
            assertTrue(stopped)
            assertNull(completion.await())
        } finally {
            result.cancel()
        }
    }

    private val manga = Manga.create().copy(id = 31, source = 7, url = "/work", title = "Work")
    private val chapter = Chapter.create().copy(id = 41, mangaId = 31, url = "/work/one", name = "One")
    private val opened =
        ReaderOpenContext(manga, chapter, 3, ReadingSyncSnapshot(ReadingSyncScope("space", 1, "actor", 1)), true)

    @Test
    fun `synced same chapter resume prepares precise target once while first request is gated`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val completion = ReaderCatalogCompletion(
            opened,
            ReaderCatalogPreparation { target ->
                calls++
                assertEquals(31L, target.mangaId)
                assertEquals(7L, target.sourceId)
                assertEquals("/work", target.mangaUrl)
                assertEquals(41L, target.chapterId)
                assertEquals("/work/one", target.chapterUrl)
                gate.await()
                listOf(chapter)
            },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            assertEquals(1, calls)
            assertNull(completion.await())
            gate.complete(Unit)
            assertEquals(listOf(chapter), first.await())
            assertNull(completion.await())
            assertEquals(1, calls)
        } finally {
            gate.complete(Unit)
            first.cancel()
        }
    }

    @Test
    fun `ordinary open and resume without sync do not prepare a directory`() = runBlocking {
        var calls = 0
        val port = ReaderCatalogPreparation {
            calls++
            listOf(chapter)
        }
        assertNull(ReaderCatalogCompletion(opened.copy(resumedWithinChapter = false), port).await())
        assertNull(ReaderCatalogCompletion(opened.copy(snapshot = ReadingSyncSnapshot()), port).await())
        assertEquals(0, calls)
    }

    @Test
    fun `background source failure is silent and does not retry in this reader`() = runBlocking {
        var calls = 0
        val completion = ReaderCatalogCompletion(
            opened,
            ReaderCatalogPreparation {
                calls++
                error("source failed")
            },
        )
        assertNull(completion.await())
        assertNull(completion.await())
        assertEquals(1, calls)
    }
}
