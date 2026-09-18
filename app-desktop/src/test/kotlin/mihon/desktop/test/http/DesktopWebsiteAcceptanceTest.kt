package mihon.desktop.test.http

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.platform.DesktopUrlOpener
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

class DesktopWebsiteAcceptanceTest {
    @Test
    fun `website grant is exact single use and exception cleanup leaves no browser permission`() {
        val policy = mihon.desktop.platform.DesktopExternalActionPolicy
        val uri = URI("http://127.0.0.1:12345/exact")
        policy.allowSingleWebsiteAcceptance(uri) {
            assertTrue(runCatching { policy.requireBrowserAllowed(URI("http://127.0.0.1:12345/other")) }.isFailure)
            assertTrue(runCatching { policy.requireAllowed("Unrelated external action") }.isFailure)
            policy.requireBrowserAllowed(uri)
            assertTrue(runCatching { policy.requireBrowserAllowed(uri) }.isFailure)
        }
        assertTrue(runCatching {
            policy.allowSingleWebsiteAcceptance(uri) { error("controlled launch failure") }
        }.isFailure)
        assertTrue(runCatching { policy.requireBrowserAllowed(uri) }.isFailure)
    }

    @Test
    fun `normal automated browsing stays denied and one token opens only a valid loopback website`(@TempDir root: Path) = runBlocking {
        val desktop = mockk<Desktop>(relaxed = true)
        mockkStatic(Desktop::class)
        every { Desktop.isDesktopSupported() } returns true
        every { Desktop.getDesktop() } returns desktop
        every { desktop.isSupported(Desktop.Action.BROWSE) } returns true
        val token = "a".repeat(64)
        val url = "http://127.0.0.1:12345/eis/nonce"
        val controller = DesktopPlatformAcceptanceController(token, mockk<DesktopShareService>(), root)
        try {
            assertTrue(DesktopUrlOpener.open(url).isFailure)
            assertTrue(controller.openWebsite(null, url).isFailure)
            assertTrue(controller.openWebsite("wrong", url).isFailure)
            assertTrue(controller.openWebsite(token, "https://example.com/").isFailure)
            assertTrue(controller.openWebsite(token, "http://127.0.0.1@evil.example/").isFailure)
            assertTrue(controller.openWebsite(token, url).isSuccess)
            verify(exactly = 1) { desktop.browse(URI(url)) }
            assertTrue(controller.openWebsite(token, url).isFailure)
            assertTrue(controller.share(token, PlatformShareKind.TEXT).failure == PlatformAcceptanceFailure.TOKEN_ALREADY_USED)
            assertTrue(DesktopUrlOpener.open(url).isFailure)
        } finally { unmockkStatic(Desktop::class) }
    }
}
