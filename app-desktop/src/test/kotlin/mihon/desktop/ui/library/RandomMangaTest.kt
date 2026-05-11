package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RandomMangaTest {

    @Test
    fun `pickRandomMangaId returns null for empty list`() {
        assertNull(pickRandomMangaId(emptyList()))
    }

    @Test
    fun `pickRandomMangaId returns the only element in a singleton list`() {
        assertNotNull(pickRandomMangaId(listOf(42L)))
    }

    @Test
    fun `pickRandomMangaId returns an element from the list`() {
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        val result = pickRandomMangaId(ids)
        assertNotNull(result)
        assert(result!! in ids) { "Expected $result to be in $ids" }
    }
}
