package mihon.desktop.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.awt.Rectangle

class DesktopWindowGeometryPolicyTest {
    private val primary = Rectangle(0, 0, 1920, 1040)

    @Test
    fun `valid normal window keeps its exact logical position and size`() {
        assertEquals(DesktopWindowGeometry(140, 110, 860, 610), restore("""{"x":140,"y":110,"width":860,"height":610}"""))
    }

    @Test
    fun `negative coordinates on a connected monitor remain valid`() {
        val expected = DesktopWindowGeometry(-1800, 60, 1000, 700, true)
        assertEquals(expected, restoreDesktopWindowGeometry(
            """{"x":-1800,"y":60,"width":1000,"height":700,"maximized":true}""",
            listOf(primary, Rectangle(-1920, 0, 1920, 1080)),
        ))
    }

    @Test
    fun `disconnected monitor falls back to primary work area and retains maximization`() {
        assertEquals(DesktopWindowGeometry(460, 170, 1000, 700, true), restore(
            """{"x":4000,"y":200,"width":1000,"height":700,"maximized":true}""",
        ))
    }

    @Test
    fun `oversized window is clamped to usable screen excluding taskbar`() {
        assertEquals(DesktopWindowGeometry(0, 0, 1920, 1040), restore(
            """{"x":1500,"y":500,"width":3000,"height":2000}""",
        ))
    }

    @Test
    fun `extreme positions cannot overflow monitor overlap calculation`() {
        assertEquals(DesktopWindowGeometry(660, 280, 600, 480), restore(
            """{"x":2147483647,"y":-2147483648,"width":600,"height":480}""",
        ))
    }

    @Test
    fun `empty malformed missing and nonpositive sizes use first run defaults`() {
        for (raw in listOf("", "broken", "{}", """{"x":0,"y":0,"width":0,"height":600}""",
            """{"x":0,"y":0,"width":800,"height":-1}""")) {
            assertNull(restore(raw), raw)
        }
    }

    @Test
    fun `unknown future fields do not discard valid geometry`() {
        assertEquals(DesktopWindowGeometry(140, 110, 860, 610), restore(
            """{"x":140,"y":110,"width":860,"height":610,"futureField":true}""",
        ))
    }

    @Test
    fun `storage failure reports unsuccessful save and keeps previous preference`() {
        val raw = """{"x":4000,"y":200,"width":1000,"height":700}"""
        val preference = mockk<Preference<String>> {
            every { get() } returns raw
            every { isSet() } returns true
            every { set(any()) } throws IllegalStateException("isolated persistence failure")
        }
        val app = mockk<DesktopAppPreferences> { every { windowGeometry } returns preference }
        val controller = DesktopWindowGeometryController(app, listOf(primary))
        assertFalse(controller.save())
        assertEquals(raw, preference.get())
        verify(exactly = 2) { preference.set(any()) }
    }

    private fun restore(raw: String) = restoreDesktopWindowGeometry(raw, listOf(primary))
}
