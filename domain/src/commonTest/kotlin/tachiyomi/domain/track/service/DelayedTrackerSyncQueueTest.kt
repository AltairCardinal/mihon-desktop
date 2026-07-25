package tachiyomi.domain.track.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track

class DelayedTrackerSyncQueueTest {
    @Test
    fun `sync filters tracks and executes provider workflow in parallel`() = runTest {
        val store = MemoryStore((1L..4L).map { item(it, 1.0) }.toMutableList())
        val started = mutableListOf<Long>()
        val bothStarted = CompletableDeferred<Unit>()
        val port = port()
        val queue = DelayedTrackerSyncQueue(
            persistence = store,
            session = { TrackerProviderSession(it, loggedIn = it == 9L) },
            execute = { request ->
                started += request.track.id
                if (started.size == 2) bothStarted.complete(Unit)
                bothStarted.await()
                TrackerProviderWorkflow(clock = { 10L }).execute(port, request)
            },
        )

        val report = queue.sync(
            listOf(track(1), track(2), track(3, trackerId = 8), track(4, chapter = 7.0)),
            chapterNumber = 5.0,
        )

        assertEquals(setOf(1L, 2L), started.toSet())
        assertEquals(listOf("refresh:1", "refresh:2", "update:1", "update:2"), port.events.sorted())
        (1L..2L).forEach {
            assertTrue(port.events.indexOf("refresh:$it") < port.events.indexOf("update:$it"))
        }
        assertEquals(2, report.succeeded)
        assertEquals(listOf(3L, 4L), store.items.map { it.trackId }.sorted())
    }

    @Test
    fun `failure keeps monotonic highest progress and stable reason`() = runTest {
        val store = MemoryStore(mutableListOf(item(1, 8.0, "NETWORK")))
        val attempted = mutableListOf<Double>()
        val queue = DelayedTrackerSyncQueue(
            persistence = store,
            session = { TrackerProviderSession(it, true) },
            execute = {
                attempted += requireNotNull(it.edit.lastChapterRead)
                TrackerProviderResult.Failure(
                    TrackerProviderError(
                        TrackerProviderOperation.EDIT,
                        TrackerProviderErrorKind.SERVER,
                        statusCode = 503,
                    ),
                )
            },
        )

        queue.sync(listOf(track(1)), 5.0)
        queue.sync(listOf(track(1)), 10.0)

        assertEquals(listOf(8.0, 10.0), attempted)
        assertEquals(10.0, store.items.single().lastChapterRead)
        assertEquals("SERVER:503", store.items.single().failureReason)
    }

    @Test
    fun `drain cleans missing and completed items but preserves retry evidence`() = runTest {
        val store = MemoryStore(
            mutableListOf(item(1, 3.0), item(2, 4.0), item(3, 5.0)),
        )
        val tracks = mapOf(1L to track(1, chapter = 3.0), 3L to track(3, trackerId = 8))
        val queue = DelayedTrackerSyncQueue(
            persistence = store,
            session = { TrackerProviderSession(it, loggedIn = false) },
            execute = { error("must be filtered") },
        )

        queue.drain(tracks::get)
        queue.markRetryExhausted(trackId = 3)

        assertEquals(listOf(3L), store.items.map { it.trackId })
        assertEquals("RETRY_EXHAUSTED", store.items.single().failureReason)
    }

    @Test
    fun `cancellation propagates without queueing`() {
        val store = MemoryStore()
        val queue = DelayedTrackerSyncQueue(
            persistence = store,
            session = { TrackerProviderSession(it, true) },
            execute = { throw CancellationException("cancel") },
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest { queue.sync(listOf(track(1)), 2.0) }
        }
        assertEquals(emptyList<DelayedTrackerSyncItem>(), store.items)
    }

    @Test
    fun `success only cleans progress up to its target across queue instances`() = runTest {
        val store = MemoryStore()
        val lowStarted = CompletableDeferred<Unit>()
        val finishLow = CompletableDeferred<Unit>()
        val lowQueue = DelayedTrackerSyncQueue(store, { TrackerProviderSession(it, true) }) {
            lowStarted.complete(Unit)
            finishLow.await()
            TrackerProviderResult.Success(it.track.copy(lastChapterRead = 5.0))
        }
        val highQueue = DelayedTrackerSyncQueue(store, { TrackerProviderSession(it, true) }) {
            TrackerProviderResult.Failure(
                TrackerProviderError(TrackerProviderOperation.EDIT, TrackerProviderErrorKind.NETWORK),
            )
        }

        val low = async { lowQueue.sync(listOf(track(1)), 5.0) }
        lowStarted.await()
        highQueue.sync(listOf(track(1)), 10.0)
        finishLow.complete(Unit)
        low.await()

        assertEquals(10.0, store.items.single().lastChapterRead)
    }

    @Test
    fun `failed updates atomically retain highest progress across queue instances`() = runTest {
        val store = RacingStore()
        val bothExecuting = CompletableDeferred<Unit>()
        var executing = 0
        fun queue() = DelayedTrackerSyncQueue(store, { TrackerProviderSession(it, true) }) {
            if (++executing == 2) bothExecuting.complete(Unit)
            bothExecuting.await()
            TrackerProviderResult.Failure(
                TrackerProviderError(TrackerProviderOperation.EDIT, TrackerProviderErrorKind.NETWORK),
            )
        }

        listOf(
            async { queue().sync(listOf(track(1)), 5.0) },
            async { queue().sync(listOf(track(1)), 10.0) },
        ).awaitAll()

        assertEquals(10.0, store.items.single().lastChapterRead)
    }

    private fun port(): RecordingPort = RecordingPort()

    private fun track(id: Long, trackerId: Long = 9, chapter: Double = 0.0) = Track(
        id, 3, trackerId, 10, null, "Manga", chapter, 10, 1, 0.0, "", 0, 0, false,
    )

    private fun item(id: Long, chapter: Double, reason: String? = null) =
        DelayedTrackerSyncItem(id, 3, 9, chapter, reason)

    private class MemoryStore(
        val items: MutableList<DelayedTrackerSyncItem> = mutableListOf(),
    ) : DelayedTrackerSyncPersistence {
        private val mutex = Mutex()
        override suspend fun getItems() = items.toList()
        override suspend fun upsertMax(item: DelayedTrackerSyncItem) = mutex.withLock {
            val current = items.firstOrNull { it.trackId == item.trackId }
            val merged = if (current == null || item.lastChapterRead >= current.lastChapterRead) item else current
            items.removeAll { it.trackId == item.trackId }
            items += merged
            merged
        }
        override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double) = mutex.withLock {
            val removed = items.removeAll { it.trackId == trackId && it.lastChapterRead <= lastChapterRead }
            removed
        }
    }

    private class RacingStore : DelayedTrackerSyncPersistence {
        val items = mutableListOf<DelayedTrackerSyncItem>()
        private val mutex = Mutex()

        override suspend fun getItems() = mutex.withLock { items.toList() }
        override suspend fun upsertMax(item: DelayedTrackerSyncItem) = mutex.withLock {
            val current = items.firstOrNull { it.trackId == item.trackId }
            val merged = if (current == null || item.lastChapterRead >= current.lastChapterRead) item else current
            items.removeAll { it.trackId == item.trackId }
            items += merged
            merged
        }
        override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double) = false
    }

    private class RecordingPort : TrackerProviderPort {
        val events = mutableListOf<String>()
        override val configuration = TrackerProviderCatalog.configuration(9)
        override val session = TrackerProviderSession(9, true)
        override suspend fun refresh(track: Track) = track.also { events += "refresh:${it.id}" }
        override suspend fun update(track: Track) = track.also { events += "update:${it.id}" }
        override suspend fun delete(track: Track) = Unit
    }
}
