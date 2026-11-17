package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DesktopReadDownloadArtifactReleaseTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `archive release failure still releases every completed artifact usage and preserves the original error`() {
        listOf(false, true).forEach { closeAll ->
            val adapter = DesktopReaderContentAdapter()
            val usage = mihon.desktop.download.DesktopDownloadArtifactUsage()
            val files = listOf(tempDir.resolve("first-$closeAll.cbz"), tempDir.resolve("second-$closeAll.cbz"))
            files.forEachIndexed { index, file -> adapter.retainDownloadArtifact(index + 1L, 0, file, usage) }
            val failure = IllegalStateException("native archive close failed")
            val releases = AtomicInteger()
            installArchiveRelease(adapter, 1) { throw failure }
            installArchiveRelease(adapter, 2) {
                releases.incrementAndGet()
                Unit
            }
            val error = runCatching { if (closeAll) adapter.close() else adapter.releaseChapter(1) }.exceptionOrNull()
            assertTrue(error === failure)
            val reservation = usage.reserveDeletion(if (closeAll) files else listOf(files.first()))
            assertNotNull(reservation, "failed native close must not leak artifact usage")
            reservation?.close()
            if (closeAll) assertEquals(1, releases.get()) else adapter.close()
        }
    }

    private fun installArchiveRelease(adapter: DesktopReaderContentAdapter, chapterId: Long, release: () -> Unit) {
        val leaseType = Class.forName("mihon.desktop.reader.ArchiveLease")
        val lease = java.lang.reflect.Proxy.newProxyInstance(leaseType.classLoader, arrayOf(leaseType)) {
                _,
                method,
                _,
            ->
            if (method.name == "release") release() else null
        }
        val bindingType = Class.forName("mihon.desktop.reader.ArchiveLeaseBinding")
        val constructor = bindingType.declaredConstructors.single().apply { isAccessible = true }
        val binding = constructor.newInstance(lease, emptyList<String>(), 0L, null)
        val field = DesktopReaderContentAdapter::class.java.getDeclaredField("archiveLeases").apply {
            isAccessible =
                true
        }
        @Suppress("UNCHECKED_CAST")
        (field.get(adapter) as MutableMap<Long, Any>)[chapterId] = binding
    }
}
