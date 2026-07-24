package mihon.desktop.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.awt.Desktop
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopUrlOpenerTest {

    @Test
    fun `default launcher is blocked inside a Gradle test worker`() {
        assertNotNull(System.getProperty("org.gradle.test.worker"))
        val desktop = mockk<Desktop>(relaxed = true)
        mockkStatic(Desktop::class)
        try {
            every { Desktop.isDesktopSupported() } returns true
            every { Desktop.getDesktop() } returns desktop

            val result = DesktopUrlOpener.open("https://example.com")

            assertTrue(result.isFailure)
            verify(exactly = 0) { Desktop.isDesktopSupported() }
            verify(exactly = 0) { Desktop.getDesktop() }
            verify(exactly = 0) { desktop.browse(any()) }
        } finally {
            unmockkStatic(Desktop::class)
        }
    }

    @Test
    fun `external action policy blocks Gradle workers and application test mode`() {
        assertTrue(DesktopExternalActionPolicy.isSuppressed(gradleWorkerId = "worker-1", testMode = false))
        assertTrue(DesktopExternalActionPolicy.isSuppressed(gradleWorkerId = null, testMode = true))
        assertTrue(!DesktopExternalActionPolicy.isSuppressed(gradleWorkerId = null, testMode = false))
    }

    @Test
    fun `injected launcher records exactly one normalized URI`() {
        val opened = mutableListOf<URI>()

        val result = DesktopUrlOpener.open("https://example.com/path") { opened += it }

        assertTrue(result.isSuccess)
        assertEquals(listOf(URI("https://example.com/path")), opened)
    }

    @Test
    fun `invalid URI and launcher failure are explicit failures`() {
        var calls = 0
        val invalid = DesktopUrlOpener.open("http://[invalid") { calls++ }
        val launchFailure = DesktopUrlOpener.open("https://example.com") { error("unsupported") }

        assertTrue(invalid.isFailure)
        assertEquals(0, calls)
        assertTrue(launchFailure.isFailure)
        assertEquals("unsupported", launchFailure.exceptionOrNull()?.message)
    }
}
