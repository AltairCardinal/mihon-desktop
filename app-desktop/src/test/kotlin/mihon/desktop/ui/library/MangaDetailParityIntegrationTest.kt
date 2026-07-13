package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MangaDetailParityIntegrationTest {

    @Test
    fun `android fixture missing or blank author does not create navigation target`() {
        assertNull(authorNavigationNameOrNull(null))
        assertNull(authorNavigationNameOrNull(""))
        assertNull(authorNavigationNameOrNull("   "))
    }

    @Test
    fun `android fixture author creates trimmed navigation target`() {
        assertEquals("Jane Doe", authorNavigationNameOrNull("  Jane Doe  "))
    }
}
