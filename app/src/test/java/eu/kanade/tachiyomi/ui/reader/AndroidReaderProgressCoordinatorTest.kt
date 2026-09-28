package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import java.util.Date

class AndroidReaderProgressCoordinatorTest {
    @Test
    fun `closing before the writer runs still drains the accepted command and cleanup`() = runTest {
        val calls = mutableListOf<String>()
        val session = RecordReadingProgress(object : ReadingProgressRepository {
            override suspend fun record(event: ReadingProgressEvent) {
                calls += "write"
            }
        }).openSession(2)
        val coordinator = AndroidReaderProgressCoordinator(
            onCommitted = {},
            onClosed = { calls += "close" },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        val reader = coordinator.openReader(1)
        val accepted = reader.submit(AcceptedReaderProgress(event(1), session))
        reader.close()
        assertTrue(calls.isEmpty())

        advanceUntilIdle()
        assertTrue(requireNotNull(accepted.receipt).await().isPersisted)
        assertEquals(listOf("write", "close"), calls)
    }

    @Test
    fun `failed transaction does not stop the next accepted write or dispatch completion`() = runBlocking {
        val writes = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val session = RecordReadingProgress(object : ReadingProgressRepository {
            override suspend fun record(event: ReadingProgressEvent) {
                if (event.lastPageRead == 1) error("storage failed")
                writes += event.lastPageRead
            }
        }).openSession(2)
        val coordinator = AndroidReaderProgressCoordinator(
            onCommitted = { completed += it.event.lastPageRead },
            onClosed = {},
        )
        val reader = coordinator.openReader(1)
        val completion = completion()
        val failed = reader.submit(AcceptedReaderProgress(event(1), session, completion))
        val successful = reader.submit(AcceptedReaderProgress(event(2), session, completion))

        withTimeout(5_000) {
            assertFalse(requireNotNull(failed.receipt).await().isPersisted)
            assertTrue(requireNotNull(successful.receipt).await().isPersisted)
        }
        assertEquals(listOf(2), writes)
        assertEquals(listOf(2), completed)
        val reopened = coordinator.openReader(1)
        assertEquals(1, reopened.awaitPrevious().size)
    }

    @Test
    fun `completion failure leaves the transaction saved and does not stop later writes`() = runBlocking {
        val writes = mutableListOf<Int>()
        val session = RecordReadingProgress(object : ReadingProgressRepository {
            override suspend fun record(event: ReadingProgressEvent) {
                writes += event.lastPageRead
            }
        }).openSession(2)
        val coordinator = AndroidReaderProgressCoordinator(
            onCommitted = { error("completion failed") },
            onClosed = {},
        )
        val reader = coordinator.openReader(1)
        val first = reader.submit(AcceptedReaderProgress(event(1), session, completion()))
        val second = reader.submit(AcceptedReaderProgress(event(2), session))

        withTimeout(5_000) {
            val firstResult = requireNotNull(first.receipt).await()
            assertTrue(firstResult.isPersisted)
            assertTrue(firstResult.completionError is IllegalStateException)
            assertTrue(requireNotNull(second.receipt).await().isPersisted)
        }
        assertEquals(listOf(1, 2), writes)
    }

    @Test
    fun `accepted writes survive close and preserve same manga submission order`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val repository = object : ReadingProgressRepository {
            override suspend fun record(event: ReadingProgressEvent) {
                if (event.lastPageRead == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                writes += event.lastPageRead
            }
        }
        val session = RecordReadingProgress(repository).openSession(2)
        val coordinator = AndroidReaderProgressCoordinator(
            onCommitted = {},
            onClosed = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val reader = coordinator.openReader(1)
        val first = reader.submit(AcceptedReaderProgress(event(1), session))
        withTimeout(5_000) { entered.await() }
        val second = reader.submit(AcceptedReaderProgress(event(2), session))
        reader.close()
        assertFalse(reader.submit(AcceptedReaderProgress(event(3), session)).accepted)

        val reopened = coordinator.openReader(1)
        val barrier = async { reopened.awaitPrevious() }
        assertFalse(barrier.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) {
            assertTrue(requireNotNull(first.receipt).await().isPersisted)
            assertTrue(requireNotNull(second.receipt).await().isPersisted)
            barrier.await()
        }
        assertEquals(listOf(1, 2), writes)
    }

    private fun event(page: Int) = ReadingProgressEvent(
        chapterId = 2,
        lastPageRead = page,
        totalPages = 5,
        readAt = Date(1),
        sessionReadDuration = 0,
        idempotencyKey = "progress-$page",
    )

    private fun completion() = ReaderProgressCompletionPlan(
        manga = Manga.create().copy(id = 1),
        completedChapter = Chapter.create().copy(id = 2, mangaId = 1),
        markDuplicates = false,
        deleteCandidate = null,
        updateTracking = false,
    )
}
