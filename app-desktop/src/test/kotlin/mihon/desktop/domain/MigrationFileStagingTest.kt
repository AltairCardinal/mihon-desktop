package mihon.desktop.domain

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class MigrationFileStagingTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `file appearing after the destination check is not overwritten by an atomic move`() = runTest {
        val original = File(root, "chapter.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var collision: File? = null
        val staging = MigrationFileStaging(File(root, "operation")) { _, target ->
            if (collision == null) {
                target.writeBytes(byteArrayOf(99, 98))
                collision = target
            }
        }
        val snapshot = staging.capture(listOf(original), null)
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { staging.execute(snapshot, null) { } }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
        assertArrayEquals(byteArrayOf(99, 98), requireNotNull(collision).readBytes())
    }

    @Test
    fun `migration uses the actual cover store reservation against manual write and delete`() = runTest {
        val store = DesktopCustomCoverStore(File(root, "covers"))
        store.write(2L, byteArrayOf(4, 5, 6))
        store.reserveMigrationCovers(setOf(2L)).use {
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { store.write(2L, byteArrayOf(7, 8, 9)) }
            }
            assertThrows(IllegalStateException::class.java) { store.deleteCustomCover(2L) }
            store.write(3L, byteArrayOf(10))
            assertArrayEquals(byteArrayOf(4, 5, 6), store.getCustomCoverFile(2L).readBytes())
        }
        store.write(2L, byteArrayOf(7, 8, 9))
        assertTrue(store.deleteCustomCover(2L))
    }

    @Test
    fun `preparation rejection preserves untouched target cover with the same bytes as accepted source`() = runTest {
        val original = File(root, "chapter.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sourceCover = File(root, "source-cover").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val targetCover = File(root, "target-cover").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val staging = MigrationFileStaging(File(root, "operation"))
        val snapshot = staging.capture(listOf(original), sourceCover)
        original.writeBytes(byteArrayOf(4, 5, 6))
        var committed = false
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { staging.execute(snapshot, targetCover) { committed = true } }
        }
        assertFalse(committed)
        assertTrue(targetCover.isFile, "An untouched identical cover remains owned by its original target")
        assertArrayEquals(byteArrayOf(7, 8, 9), targetCover.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), original.readBytes())
    }

    @Test
    fun `migration reservation refuses active reads and blocks later leases without retiring them`() {
        val chapter = File(root, "chapter").apply { mkdirs() }
        val page = File(chapter, "1.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val coordinator = mihon.desktop.download.PartialDownloadArtifactLifecycleCoordinator()
        val candidate = mihon.domain.reader.partial.PartialReaderPageCandidate(1L, 0, 0, page.absolutePath, 1L)
        assertTrue(coordinator.registerCommittedPage(7L, candidate))
        val active = requireNotNull(coordinator.acquire(candidate))
        try {
            assertThrows(IllegalStateException::class.java) { coordinator.reserveMigrationArtifacts(listOf(chapter)) }
        } finally {
            active.close()
        }
        coordinator.reserveMigrationArtifacts(listOf(chapter)).use {
            org.junit.jupiter.api.Assertions.assertNull(coordinator.acquire(candidate))
        }
        requireNotNull(coordinator.acquire(candidate)).close()
    }

    @Test
    fun `accepted files are staged before SQL and restored on rejected commit`() = runTest {
        val original = File(root, "chapter.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oldCover = File(root, "source-cover").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val targetCover = File(root, "target-cover").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val staging = MigrationFileStaging(File(root, "operation"))
        val snapshot = staging.capture(listOf(original), oldCover)
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                staging.execute(snapshot, targetCover) {
                    assertFalse(original.exists(), "A confirmed original is held in reversible staging before SQL")
                    assertArrayEquals(byteArrayOf(7, 8, 9), targetCover.readBytes())
                    throw IOException("SQL rejected")
                }
            }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), targetCover.readBytes())
    }

    @Test
    fun `accepted cover and download snapshot excludes later files and source cover edits`() = runTest {
        val original = File(root, "chapter.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oldCover = File(root, "source-cover").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val staging = MigrationFileStaging(File(root, "operation"))
        val snapshot = staging.capture(listOf(original), oldCover)
        val late = File(root, "later.cbz").apply { writeBytes(byteArrayOf(10, 11)) }
        oldCover.writeBytes(byteArrayOf(12, 13))
        val targetCover = File(root, "target-cover")
        staging.execute(snapshot, targetCover) { }
        assertFalse(original.exists(), "Only the accepted original is removed")
        assertTrue(late.exists())
        assertArrayEquals(byteArrayOf(7, 8, 9), targetCover.readBytes(), "The accepted cover survives an HTTP wait")
        assertArrayEquals(byteArrayOf(12, 13), oldCover.readBytes())
    }
}
