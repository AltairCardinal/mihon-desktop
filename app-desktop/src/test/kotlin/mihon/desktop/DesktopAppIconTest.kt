package mihon.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopAppIconTest {

    @Test
    fun `desktop application icon loads from production resources`() {
        val icon = loadDesktopAppIcon()

        assertEquals(256, icon.width)
        assertEquals(256, icon.height)
    }
}
