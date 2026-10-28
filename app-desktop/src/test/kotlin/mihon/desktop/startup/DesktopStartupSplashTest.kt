package mihon.desktop.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

class DesktopStartupSplashTest {
    @Test
    fun `packaged graphical Windows startup opens the splash and exposes its cleanup`() {
        val surface = RecordingSurface()
        val session = showDesktopStartupSplash(emptyArray(), true, false, "startup.png") { path ->
            assertEquals("startup.png", path)
            surface
        }
        assertSame(surface, session)
    }

    @Test
    fun `headless other platforms and unpackaged runs do not create splash windows`() {
        val mustNotOpen: (String) -> AutoCloseable = { error("splash must not open") }
        assertNull(showDesktopStartupSplash(arrayOf("--headless"), true, false, "startup.png", mustNotOpen))
        assertNull(showDesktopStartupSplash(emptyArray(), true, true, "startup.png", mustNotOpen))
        assertNull(showDesktopStartupSplash(emptyArray(), false, false, "startup.png", mustNotOpen))
        assertNull(showDesktopStartupSplash(emptyArray(), true, false, null, mustNotOpen))
    }

    @Test
    fun `unavailable splash resources do not block startup`() {
        assertNull(showDesktopStartupSplash(emptyArray(), true, false, "missing.png") { error("missing resource") })
    }

    @Test
    fun `first visible frame is already centered on the secondary monitor`() {
        val surface = RecordingSurface()
        val session = showStartupSplash(surface, Rectangle(-1920, 0, 1920, 1040))
        assertEquals(listOf(Point(-1216, 392)), surface.visibleLocations)
        assertEquals(listOf("locate", "show"), surface.events)
        session.close()
        assertEquals("close", surface.events.last())
    }

    @Test
    fun `monitor above primary keeps its negative origin and taskbar inset`() {
        val surface = RecordingSurface()
        showStartupSplash(surface, Rectangle(0, -1160, 1920, 1160)).close()
        assertEquals(listOf(Point(704, -708)), surface.visibleLocations)
    }

    @Test
    fun `different screen scale uses logical work area coordinates`() {
        val surface = RecordingSurface()
        showStartupSplash(surface, Rectangle(2560, 32, 1280, 688)).close()
        assertEquals(listOf(Point(2944, 248)), surface.visibleLocations)
    }

    @Test
    fun `failed window creation releases its resources`() {
        val surface = RecordingSurface(failShowing = true)
        assertThrows(IllegalStateException::class.java) {
            showStartupSplash(surface, Rectangle(0, 0, 1920, 1040))
        }
        assertEquals("close", surface.events.last())
    }

    private class RecordingSurface(private val failShowing: Boolean = false) : StartupSplashSurface {
        override val size = Dimension(512, 256)
        val events = mutableListOf<String>()
        val visibleLocations = mutableListOf<Point>()
        private var location = Point(0, 0)

        override fun locate(x: Int, y: Int) {
            events += "locate"
            location = Point(x, y)
        }

        override fun show() {
            if (failShowing) error("window creation failed")
            events += "show"
            visibleLocations += Point(location)
        }

        override fun close() {
            events += "close"
        }
    }
}
