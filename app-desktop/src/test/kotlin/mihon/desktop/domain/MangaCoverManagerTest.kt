package mihon.desktop.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class MangaCoverManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `getCustomCoverFile returns file under covers dir`() {
        val manager = DesktopMangaCoverManager(tempDir)
        val file = manager.getCustomCoverFile(mangaId = 42L)
        assertEquals(File(tempDir, "42"), file)
    }

    @Test
    fun `customCoverExists returns false when no file present`() {
        val manager = DesktopMangaCoverManager(tempDir)
        assertFalse(manager.customCoverExists(mangaId = 1L))
    }

    @Test
    fun `setCustomCover copies file and customCoverExists returns true`() {
        val manager = DesktopMangaCoverManager(tempDir)
        val source = File(tempDir, "input.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }

        manager.setCustomCover(mangaId = 5L, source = source)

        assertTrue(manager.customCoverExists(mangaId = 5L))
    }

    @Test
    fun `deleteCustomCover removes the file`() {
        val manager = DesktopMangaCoverManager(tempDir)
        val source = File(tempDir, "input.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        manager.setCustomCover(mangaId = 7L, source = source)
        assertTrue(manager.customCoverExists(mangaId = 7L))

        manager.deleteCustomCover(mangaId = 7L)

        assertFalse(manager.customCoverExists(mangaId = 7L))
    }

    @Test
    fun `resolveModel returns file path when custom cover exists`() {
        val manager = DesktopMangaCoverManager(tempDir)
        val source = File(tempDir, "input.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        manager.setCustomCover(mangaId = 10L, source = source)

        val model = manager.resolveModel(mangaId = 10L, fallbackUrl = "https://example.com/cover.jpg")

        assertEquals(manager.getCustomCoverFile(10L).absolutePath, model)
    }

    @Test
    fun `resolveModel returns fallback URL when no custom cover`() {
        val manager = DesktopMangaCoverManager(tempDir)

        val model = manager.resolveModel(mangaId = 99L, fallbackUrl = "https://example.com/cover.jpg")

        assertEquals("https://example.com/cover.jpg", model)
    }

    @Test
    fun `resolveModel returns null when no custom cover and fallback is null`() {
        val manager = DesktopMangaCoverManager(tempDir)

        val model = manager.resolveModel(mangaId = 99L, fallbackUrl = null)

        assertNull(model)
    }
}
