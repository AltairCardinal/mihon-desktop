package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaDetailChapterListItemsTest {

    @Test
    fun `missing chapter count text is Chinese`() {
        assertEquals("缺少 1 话", missingChapterCountText(1))
        assertEquals("缺少 5 话", missingChapterCountText(5))
    }
}
