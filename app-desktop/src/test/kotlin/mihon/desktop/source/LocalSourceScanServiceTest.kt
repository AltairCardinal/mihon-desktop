package mihon.desktop.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSourceScanServiceTest {

    @TempDir
    lateinit var tmpDir: Path

    private val root: File get() = tmpDir.toFile()

    private fun prefs(): DesktopAppPreferences = DesktopAppPreferences(InMemoryPreferenceStore())

    private fun createZip(dest: File, entries: List<String>): File {
        ZipOutputStream(dest.outputStream()).use { zip ->
            for (name in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(10))
                zip.closeEntry()
            }
        }
        return dest
    }

    private fun createMangaDir(parent: File, name: String): File {
        val dir = File(parent, name).also { it.mkdirs() }
        val chapterDir = File(dir, "Chapter 1").also { it.mkdirs() }
        File(chapterDir, "001.jpg").writeBytes(ByteArray(10))
        return dir
    }

    /** A no-op watcher that never fires events. */
    private val noopWatcherFactory = FileWatcherFactory { _, _ -> Closeable {} }

    /** A watcher that captures the onChange callback for manual triggering. */
    private class FakeWatcher : FileWatcherFactory {
        var onChange: ((File) -> Unit)? = null
        var watchedDir: File? = null
        var closed = false

        override fun watch(directory: File, onChange: (File) -> Unit): Closeable {
            this.watchedDir = directory
            this.onChange = onChange
            return Closeable { closed = true }
        }

        fun fireChange(file: File) {
            onChange?.invoke(file)
        }
    }

    // ─── start with configured rootDir triggers scan ─────────────────────────────

    @Test
    fun `start with configured rootDir scans and populates mangaList`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        createMangaDir(root, "Manga")

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        val list = service.mangaList.value
        assertEquals(1, list.size)
        assertEquals("Manga", list[0].name)

        service.stop()
    }

    // ─── start with empty rootDir stays idle ─────────────────────────────────────

    @Test
    fun `start with empty rootDir stays idle with empty list`() = runTest {
        val p = prefs()

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        assertTrue(service.mangaList.value.isEmpty())
        assertEquals(LocalSourceScanService.ScanState.Idle, service.scanState.value)

        service.stop()
    }

    // ─── scan state transitions ──────────────────────────────────────────────────

    @Test
    fun `scan state reaches Watching after scan completes`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        createMangaDir(root, "Manga")

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        assertEquals(LocalSourceScanService.ScanState.Watching, service.scanState.value)

        service.stop()
    }

    // ─── maxDepth respected ──────────────────────────────────────────────────────

    @Test
    fun `scan respects maxDepth preference`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        p.localSourceMaxDepth.set(1)

        val group = File(root, "Group").also { it.mkdirs() }
        createMangaDir(group, "DeepManga")

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        assertTrue(service.mangaList.value.isEmpty(), "Manga at depth 2 not found with maxDepth=1")

        service.stop()
    }

    // ─── rescan refreshes the list ───────────────────────────────────────────────

    @Test
    fun `rescan picks up newly added manga`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        assertTrue(service.mangaList.value.isEmpty())

        createMangaDir(root, "NewManga")
        service.rescan()
        advanceUntilIdle()

        assertEquals(1, service.mangaList.value.size)
        assertEquals("NewManga", service.mangaList.value[0].name)

        service.stop()
    }

    // ─── file system change triggers rescan ──────────────────────────────────────

    @Test
    fun `file system change triggers debounced rescan`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        val fakeWatcher = FakeWatcher()

        val service = LocalSourceScanService(p, scope = this, watcherFactory = fakeWatcher)
        service.start()
        advanceUntilIdle()

        assertTrue(service.mangaList.value.isEmpty())

        createMangaDir(root, "WatchedManga")
        fakeWatcher.fireChange(File(root, "WatchedManga"))
        advanceUntilIdle()

        assertEquals(1, service.mangaList.value.size)
        assertEquals("WatchedManga", service.mangaList.value[0].name)

        service.stop()
    }

    // ─── stop cancels watching ───────────────────────────────────────────────────

    @Test
    fun `stop closes watcher and resets state to idle`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        val fakeWatcher = FakeWatcher()

        val service = LocalSourceScanService(p, scope = this, watcherFactory = fakeWatcher)
        service.start()
        advanceUntilIdle()

        service.stop()
        advanceUntilIdle()

        assertTrue(fakeWatcher.closed)
        assertEquals(LocalSourceScanService.ScanState.Idle, service.scanState.value)
    }

    // ─── cover resolution after scan ─────────────────────────────────────────────

    @Test
    fun `scan resolves cover for root-level archive after discovering it`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        // Create a zip with a real image entry (needs image extension for archiveHasImages + resolveCover)
        createZip(File(root, "OneShot.cbz"), listOf("001.jpg"))

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        val list = service.mangaList.value
        assertEquals(1, list.size)
        assertTrue(
            list[0].coverFile != null,
            "coverFile should be resolved after scan, got null for archive entry",
        )

        service.stop()
    }

    @Test
    fun `scan resolves cover for directory manga with cover image`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)
        val mangaDir = createMangaDir(root, "LongManga")
        // Place a cover image inside the manga directory
        File(mangaDir, "cover.jpg").writeBytes(ByteArray(10))

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        val list = service.mangaList.value
        assertEquals(1, list.size)
        assertTrue(
            list[0].coverFile != null,
            "coverFile should be resolved after scan for directory manga with cover.jpg",
        )

        service.stop()
    }

    // ─── rootDir preference change triggers rescan ───────────────────────────────

    @Test
    fun `changing rootDir preference triggers rescan with new directory`() = runTest {
        val p = prefs()
        p.localSourceRootDir.set(root.absolutePath)

        val service = LocalSourceScanService(p, scope = this, watcherFactory = noopWatcherFactory)
        service.start()
        advanceUntilIdle()

        assertTrue(service.mangaList.value.isEmpty())

        val newRoot = File(root, "newroot").also { it.mkdirs() }
        createMangaDir(newRoot, "OtherManga")
        p.localSourceRootDir.set(newRoot.absolutePath)
        advanceUntilIdle()

        assertEquals(1, service.mangaList.value.size)
        assertEquals("OtherManga", service.mangaList.value[0].name)

        service.stop()
    }
}
