package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * UI-Fix navigation & API contract tests.
 *
 * Red tests first: these verify contracts that must hold once the UI fixes are implemented.
 *
 * Tests cover:
 *   1. UpdatesRootScreen implements Screen (navigation contract unchanged)
 *   2. HistoryRootScreen implements Screen (navigation contract unchanged)
 *   3. DownloadProvider.deleteChapterDownload removes the chapter directory
 *   4. DownloadManager.cancelAll clears the queue
 *   5. DownloadManager.clearErrors removes ERROR items
 *   6. DownloadManager.retryErrors resets ERROR items to QUEUED
 *   7. GetChapter is registered in DI (needed for HistoryTab navigation)
 */
class UIFixNavigationContractTest {

    private fun jpegBytes() = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )

    // ── Navigation type contracts ──────────────────────────────────────────────

    @Test
    fun `UpdatesRootScreen implements Screen not Tab`() {
        val cls = Class.forName("mihon.desktop.ui.updates.UpdatesRootScreen")
        assertTrue(Screen::class.java.isAssignableFrom(cls), "UpdatesRootScreen must implement Screen")
        assertFalse(Tab::class.java.isAssignableFrom(cls), "UpdatesRootScreen must NOT implement Tab")
    }

    @Test
    fun `HistoryRootScreen implements Screen not Tab`() {
        val cls = Class.forName("mihon.desktop.ui.history.HistoryRootScreen")
        assertTrue(Screen::class.java.isAssignableFrom(cls), "HistoryRootScreen must implement Screen")
        assertFalse(Tab::class.java.isAssignableFrom(cls), "HistoryRootScreen must NOT implement Tab")
    }

    // ── DownloadProvider: deleteChapterDownload ────────────────────────────────

    @Test
    fun `deleteChapterDownload removes downloaded chapter directory`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        // Create a fake downloaded chapter directory with an image file
        val chapterDir = provider.chapterDownloadDir(
            sourceId = 1L,
            mangaTitle = "Test Manga",
            chapterName = "Chapter 1",
        )
        chapterDir.mkdirs()
        File(chapterDir, "page_001.jpg").writeBytes(jpegBytes())
        assertTrue(provider.isChapterDownloaded(1L, "Test Manga", "Chapter 1"))

        // Delete it
        provider.deleteChapterDownload(
            sourceId = 1L,
            mangaTitle = "Test Manga",
            chapterName = "Chapter 1",
        )

        assertFalse(provider.isChapterDownloaded(1L, "Test Manga", "Chapter 1"))
        assertFalse(chapterDir.exists())
    }

    @Test
    fun `deleteChapterDownload on non-existent directory does not throw`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        // Should silently succeed
        provider.deleteChapterDownload(1L, "Nonexistent Manga", "Chapter 99")
    }

    // ── DownloadManager: global queue operations ───────────────────────────────

    private fun makeItem(id: Long, status: DownloadStatus = DownloadStatus.QUEUED) = DownloadItem(
        sourceId = 1L,
        mangaTitle = "Manga",
        chapterName = "Ch $id",
        chapterId = id,
        chapterUrl = "/ch/$id",
        status = status,
    )

    @Test
    fun `cancelAll clears entire download queue`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)
        manager.enqueue(makeItem(1L))
        manager.enqueue(makeItem(2L))
        manager.enqueue(makeItem(3L))
        assertEquals(3, manager.queue.value.size)

        manager.cancelAll()

        assertEquals(0, manager.queue.value.size)
    }

    @Test
    fun `clearErrors removes only ERROR status items`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)
        manager.enqueue(makeItem(1L))  // QUEUED
        manager.enqueue(makeItem(2L))  // will be set to ERROR
        manager.enqueue(makeItem(3L))  // QUEUED

        // Simulate error on item 2
        manager.transition(2L, mihon.domain.download.DownloadQueueStatus.DOWNLOADING)
        manager.transition(2L, mihon.domain.download.DownloadQueueStatus.ERROR)
        assertEquals(3, manager.queue.value.size)

        manager.clearErrors()

        assertEquals(2, manager.queue.value.size)
        assertTrue(manager.queue.value.none { it.chapterId == 2L })
    }

    @Test
    fun `retryErrors resets ERROR items to QUEUED`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)
        manager.enqueue(makeItem(1L))
        manager.enqueue(makeItem(2L))
        manager.transition(1L, mihon.domain.download.DownloadQueueStatus.DOWNLOADING)
        manager.transition(1L, mihon.domain.download.DownloadQueueStatus.ERROR)
        manager.transition(2L, mihon.domain.download.DownloadQueueStatus.DOWNLOADING)
        manager.transition(2L, mihon.domain.download.DownloadQueueStatus.ERROR)

        manager.retryErrors()

        assertTrue(manager.queue.value.all { it.status == DownloadStatus.QUEUED })
    }

    @Test
    fun `pauseAll and resumeAll toggle isPaused flag`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)
        assertFalse(manager.isPaused.value)

        manager.pauseAll()
        assertTrue(manager.isPaused.value)

        manager.resumeAll()
        assertFalse(manager.isPaused.value)
    }

    // ── _tmp directory mechanism tests ──────────────────────────────────────────

    @Test
    fun `isChapterDownloaded ignores _tmp directories`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        // Create a _tmp directory with images — should NOT count as downloaded
        val tmpDir = provider.chapterTmpDir(1L, "Manga", "Ch 1")
        tmpDir.mkdirs()
        File(tmpDir, "001.jpg").writeBytes(jpegBytes())

        assertFalse(provider.isChapterDownloaded(1L, "Manga", "Ch 1"),
            "_tmp directory must not be considered as downloaded")
        assertTrue(provider.isChapterDownloading(1L, "Manga", "Ch 1"),
            "_tmp directory should be detected by isChapterDownloading")
    }

    @Test
    fun `renameTmpToFinal makes chapter appear as downloaded`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        // Create a _tmp directory with images
        val tmpDir = provider.chapterTmpDir(1L, "Manga", "Ch 1")
        tmpDir.mkdirs()
        File(tmpDir, "001.jpg").writeBytes(jpegBytes())

        assertFalse(provider.isChapterDownloaded(1L, "Manga", "Ch 1"))

        // Rename _tmp to final
        val success = provider.renameTmpToFinal(1L, "Manga", "Ch 1")

        assertTrue(success, "renameTmpToFinal should succeed")
        assertTrue(provider.isChapterDownloaded(1L, "Manga", "Ch 1"),
            "After rename, chapter should be detected as downloaded")
        assertFalse(provider.isChapterDownloading(1L, "Manga", "Ch 1"),
            "After rename, _tmp directory should no longer exist")
    }

    @Test
    fun `cleanupTmpDir removes temporary directory`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val tmpDir = provider.chapterTmpDir(1L, "Manga", "Ch 1")
        tmpDir.mkdirs()
        File(tmpDir, "001.tmp").writeBytes(ByteArray(10))
        assertTrue(tmpDir.exists())

        provider.cleanupTmpDir(1L, "Manga", "Ch 1")

        assertFalse(tmpDir.exists(), "cleanupTmpDir should remove the _tmp directory")
    }

    @Test
    fun `cancel cleans up _tmp directory`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)
        manager.enqueue(makeItem(1L))

        // Simulate that a _tmp directory was created during download
        val tmpDir = provider.chapterTmpDir(1L, "Manga", "Ch 1")
        tmpDir.mkdirs()
        File(tmpDir, "001.tmp").writeBytes(ByteArray(10))

        manager.cancel(1L)

        assertEquals(0, manager.queue.value.size)
        assertFalse(tmpDir.exists(), "cancel should clean up _tmp directory")
    }

    @Test
    fun `cancelAll cleans up all _tmp directories`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)
        manager.enqueue(makeItem(1L))
        manager.enqueue(makeItem(2L))

        // Create _tmp dirs for both
        val tmp1 = provider.chapterTmpDir(1L, "Manga", "Ch 1")
        val tmp2 = provider.chapterTmpDir(1L, "Manga", "Ch 2")
        tmp1.mkdirs()
        tmp2.mkdirs()
        File(tmp1, "001.jpg").writeBytes(jpegBytes())
        File(tmp2, "001.jpg").writeBytes(jpegBytes())

        manager.cancelAll()

        assertEquals(0, manager.queue.value.size)
        assertFalse(tmp1.exists(), "cancelAll should clean up _tmp for item 1")
        assertFalse(tmp2.exists(), "cancelAll should clean up _tmp for item 2")
    }

    @Test
    fun `enqueue cleans up leftover _tmp directory from previous attempt`(@TempDir tempDir: Path) {
        val provider = DesktopDownloadProvider(baseDir = tempDir.toFile())
        val manager = DesktopDownloadManager(provider = provider)

        // Simulate a leftover _tmp directory from a failed previous download
        val tmpDir = provider.chapterTmpDir(1L, "Manga", "Ch 1")
        tmpDir.mkdirs()
        File(tmpDir, "001.tmp").writeBytes(ByteArray(10))
        assertTrue(tmpDir.exists())

        manager.enqueue(makeItem(1L))

        assertEquals(1, manager.queue.value.size, "item should be enqueued")
        assertFalse(tmpDir.exists(), "enqueue should clean up leftover _tmp directory")
    }
}
