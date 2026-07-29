package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class MangaDetailChapterListItemsTest {

    @Test
    fun `missing chapter count text is Chinese`() {
        val locale = Locale.forLanguageTag("zh-CN")
        assertEquals("缺少 1 话", missingChapterCountText(1, locale))
        assertEquals("缺少 5 话", missingChapterCountText(5, locale))
    }
}
