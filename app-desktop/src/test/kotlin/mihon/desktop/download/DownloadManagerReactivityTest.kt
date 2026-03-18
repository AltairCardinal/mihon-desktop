package mihon.desktop.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Verifies that items enqueued AFTER start() are still processed.
 *
 * The worker uses StateFlow.collect so items added at any time are picked up.
 * In tests the worker runs in the main TestScope — advanceUntilIdle() drives it.
 * The returned Job is cancelled at the end of each test to prevent
 * UncompletedCoroutinesError (the collect loop is infinite by design).
 *
 * An item with no URLs and no source will quickly reach ERROR status —
 * still NOT QUEUED, which proves the worker picked it up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerReactivityTest {

    @TempDir
    lateinit var tempDir: File

    private fun manager(scope: TestScope) = DesktopDownloadManager(
        provider = DesktopDownloadProvider(baseDir = tempDir),
        networkHelper = null, // no HTTP — download will reach ERROR, not stay QUEUED
        workerScope = scope,  // main TestScope: advanceUntilIdle() drives the worker
    )

    @Test
    fun `item enqueued after start transitions away from QUEUED`() = runTest {
        val mgr = manager(this)
        val workerJob = mgr.start()
        advanceUntilIdle() // empty queue — collector suspends at filter

        mgr.enqueue(
            DownloadItem(
                sourceId = 1L,
                mangaTitle = "Test",
                chapterName = "Ch 1",
                chapterId = 10L,
                pageUrls = emptyList(), // no URLs → will reach ERROR quickly
                chapterUrl = "",
            ),
        )

        advanceUntilIdle()

        // Item either removed (success) or status != QUEUED (error/downloading)
        val item = mgr.queue.first().find { it.chapterId == 10L }
        if (item != null) {
            assertNotEquals(
                DownloadStatus.QUEUED,
                item.status,
                "Item must leave QUEUED after start() was already called — reactive worker must pick it up",
            )
        }
        // item == null means it was removed after successful download — also correct

        workerJob.cancel() // prevent UncompletedCoroutinesError — collect loop is infinite
    }

    @Test
    fun `three items enqueued after start all leave QUEUED state`() = runTest {
        val mgr = manager(this)
        val workerJob = mgr.start()
        advanceUntilIdle()

        repeat(3) { i ->
            mgr.enqueue(
                DownloadItem(
                    sourceId = 1L,
                    mangaTitle = "Test",
                    chapterName = "Ch ${i + 1}",
                    chapterId = (i + 1).toLong(),
                    pageUrls = emptyList(),
                    chapterUrl = "",
                ),
            )
        }

        advanceUntilIdle()

        val stillQueued = mgr.queue.first().count { it.status == DownloadStatus.QUEUED }
        assertEquals(0, stillQueued, "No item should remain QUEUED — reactive worker must process all")

        workerJob.cancel()
    }
}
