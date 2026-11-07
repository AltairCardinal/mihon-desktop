package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderImageForegroundExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderImagePreparationQueueTest {
    @Test
    fun currentSlotsStartBeforeAnEarlierMountedNeighbourAndSlowCurrentDoesNotBlockFreeCapacity() = runTest {
        val pages = List(8) { ReaderPage(it) }
        val queue = ReaderImagePreparationQueue(this)
        queue.updateWindow(
            listOf(pages[3], pages[4], pages[5], pages[6], pages[2], pages[1]),
            setOf(pages[3], pages[4]),
        )
        val neighbour = async { queue.acquire(pages[5]) }
        runCurrent()
        assertFalse(neighbour.isCompleted)
        val current = listOf(3, 4).map { async { queue.acquire(pages[it]) } }
        runCurrent()
        assertTrue(current.all { it.isCompleted })
        assertFalse(neighbour.isCompleted)
        current[1].await().close()
        runCurrent()
        assertTrue(neighbour.isCompleted)
        neighbour.await().close()
        current[0].await().close()
        queue.close()
    }

    @Test
    fun reversingReordersOnlyPendingWorkAndRetainsTheActualAcceptedNativeRunnableUntilExit() = runTest {
        val pages = List(8) { ReaderPage(it) }
        val queue = ReaderImagePreparationQueue(this)
        queue.updateWindow(
            listOf(pages[3], pages[4], pages[5], pages[6], pages[2], pages[1]),
            setOf(pages[3], pages[4]),
        )
        val current = listOf(3, 4).map { async { queue.acquire(pages[it]) } }
        val pending = listOf(5, 6, 2, 1).associateWith { async { queue.acquire(pages[it]) } }
        runCurrent()
        val accepted = mutableListOf<Runnable>()
        val lease = current[0].await()
        val native = ReaderImageForegroundExecutor(Executor { accepted += it }, lease::retainWork)
        native.execute {}
        lease.close()
        queue.updateWindow(
            listOf(pages[3], pages[4], pages[2], pages[1], pages[5], pages[6]),
            setOf(pages[3], pages[4]),
        )
        runCurrent()
        assertTrue(pending.values.none { it.isCompleted })
        accepted.single().run()
        runCurrent()
        assertTrue(pending.getValue(2).isCompleted)
        assertFalse(pending.getValue(5).isCompleted)
        pending.getValue(2).await().close()
        runCurrent()
        assertTrue(pending.getValue(1).isCompleted)
        assertFalse(pending.getValue(6).isCompleted)
        pending.getValue(1).await().close()
        current[1].await().close()
        runCurrent()
        pending.values.forEach { it.await().close() }
        queue.close()
    }

    @Test
    fun cancelledQueuedRequestAndClosedWindowLeaveNoUnboundedWaiter() = runTest {
        val pages = List(4) { ReaderPage(it) }
        val queue = ReaderImagePreparationQueue(this)
        queue.updateWindow(pages, pages.take(2).toSet())
        val pending = async { queue.acquire(pages[3]) }
        runCurrent()
        pending.cancel()
        runCurrent()
        queue.close()
        assertTrue(pending.isCancelled)
        assertEquals(0, eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork.value)
    }
}
