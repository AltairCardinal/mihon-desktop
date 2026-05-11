package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NsfwFilterTest {

    private fun ext(name: String, isNsfw: Boolean) = DesktopAvailableExtension(
        name = name,
        pkgName = name,
        versionName = "1.0",
        versionCode = 1L,
        lang = "en",
        isNsfw = isNsfw,
        jarUrl = "",
        iconUrl = "",
        repoUrl = "",
    )

    @Test
    fun `showNsfw=true returns all extensions`() {
        val exts = listOf(ext("Clean", false), ext("Adult", true))
        assertEquals(2, filterAvailableByNsfw(exts, showNsfw = true).size)
    }

    @Test
    fun `showNsfw=false excludes NSFW extensions`() {
        val exts = listOf(ext("Clean", false), ext("Adult", true))
        val result = filterAvailableByNsfw(exts, showNsfw = false)
        assertEquals(1, result.size)
        assertEquals("Clean", result[0].name)
    }

    @Test
    fun `showNsfw=false on empty list returns empty`() {
        assertTrue(filterAvailableByNsfw(emptyList(), showNsfw = false).isEmpty())
    }

    @Test
    fun `showNsfw=false with only clean extensions returns all`() {
        val exts = listOf(ext("A", false), ext("B", false))
        assertEquals(2, filterAvailableByNsfw(exts, showNsfw = false).size)
    }
}
