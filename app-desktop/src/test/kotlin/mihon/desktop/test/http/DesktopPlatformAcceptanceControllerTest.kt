package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import mihon.desktop.platform.DesktopClipboardPort
import mihon.desktop.platform.DesktopExternalActionPolicy
import mihon.desktop.platform.DesktopNativeShareContent
import mihon.desktop.platform.DesktopNativeShareOutcome
import mihon.desktop.platform.DesktopNativeSharePort
import mihon.desktop.platform.DesktopNativeShareSession
import mihon.desktop.platform.DesktopNativeShareTerminal
import mihon.desktop.platform.DesktopRevealPort
import mihon.desktop.platform.DesktopSaveContent
import mihon.desktop.platform.DesktopSaveOutcome
import mihon.desktop.platform.DesktopSavePort
import mihon.desktop.platform.DesktopShareFailureReason
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import mihon.desktop.test.state.TestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

class DesktopPlatformAcceptanceControllerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ordinary test mode remains suppressed without a scoped platform acceptance`() {
        val state = TestState().apply { testMode = true }

        assertTrue(DesktopExternalActionPolicy.isSuppressed(null, testMode = true))
        assertTrue(
            DesktopExternalActionPolicy.isSuppressed(
                gradleWorkerId = null,
                testMode = state.testMode,
            ),
        )
    }

    @Test
    fun `platform acceptance opens only the share gate and never nested external actions`() {
        DesktopExternalActionPolicy.allowSinglePlatformAcceptance {
            assertFalse(DesktopExternalActionPolicy.isShareSuppressed())
            assertThrows(IllegalStateException::class.java) {
                DesktopExternalActionPolicy.requireAllowed("Desktop reveal")
            }
        }
        assertTrue(DesktopExternalActionPolicy.isSuppressed())
    }

    @Test
    fun `controller requires a high entropy configured token and production DI service`() {
        val valid = TestArguments.parse(
            arrayOf("--test-mode", "--platform-acceptance-token=$TOKEN"),
        )

        assertNull(createPlatformAcceptanceController(valid, tempDir) { error("DI disconnected") })
        assertNull(
            createPlatformAcceptanceController(
                TestArguments.parse(arrayOf("--test-mode", "--platform-acceptance-token=short")),
                tempDir,
            ) { service() },
        )
        assertNull(
            createPlatformAcceptanceController(
                TestArguments.parse(arrayOf("--platform-acceptance-token=$TOKEN")),
                tempDir,
            ) { service() },
        )
    }

    @Test
    fun `missing wrong and replayed tokens cannot trigger the production share service`() = runBlocking {
        val clipboard = RecordingClipboard()
        val controller = controller(service(clipboard = clipboard))

        assertEquals(PlatformAcceptanceFailure.MISSING_TOKEN, controller.share(null, PlatformShareKind.TEXT).failure)
        assertEquals(PlatformAcceptanceFailure.INVALID_TOKEN, controller.share("0".repeat(64), PlatformShareKind.TEXT).failure)
        assertTrue(clipboard.texts.isEmpty())

        val accepted = controller.share(TOKEN, PlatformShareKind.TEXT)
        assertEquals("CopiedToClipboard", accepted.terminalResult)
        assertEquals(listOf(DesktopPlatformAcceptanceController.TEXT_PAYLOAD), clipboard.texts)

        assertEquals(PlatformAcceptanceFailure.TOKEN_ALREADY_USED, controller.share(TOKEN, PlatformShareKind.TEXT).failure)
        assertEquals(1, clipboard.texts.size)
        assertTrue(DesktopExternalActionPolicy.isSuppressed(), "acceptance scope must be closed after the call")
    }

    @Test
    fun `loopback endpoint enforces token status codes and consumes a success once`() = runBlocking {
        assertEquals("127.0.0.1", TestMode.TEST_MODE_HOST)
        val clipboard = RecordingClipboard()
        val controller = controller(service(clipboard = clipboard))
        val server = embeddedServer(CIO, host = TestMode.TEST_MODE_HOST, port = 0) {
            testHttpServer(updateModel = null, platformAcceptanceController = controller)
        }.start()
        try {
            val port = server.resolvedConnectors().single().port
            val endpoint = URI.create("http://${TestMode.TEST_MODE_HOST}:$port/test/platform-acceptance/share/text")
            assertEquals(401, post(endpoint, null).statusCode())
            assertEquals(403, post(endpoint, "f".repeat(64)).statusCode())
            assertEquals(200, post(endpoint, TOKEN).statusCode())
            assertEquals(409, post(endpoint, TOKEN).statusCode())
            assertEquals(listOf(DesktopPlatformAcceptanceController.TEXT_PAYLOAD), clipboard.texts)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `file acceptance creates its own controlled png and does not accept caller paths`() = runBlocking {
        val save = RecordingSavePort(tempDir.resolve("accepted.png").toFile())
        var revealSideEffects = 0
        val controller = controller(
            service(
                savePort = save,
                revealPort = DesktopRevealPort {
                    DesktopExternalActionPolicy.requireAllowed("Desktop reveal")
                    revealSideEffects++
                },
            ),
        )

        val result = controller.share(TOKEN, PlatformShareKind.FILE)

        assertEquals("Saved", result.terminalResult)
        assertEquals("image/png", save.content.single().let { it as DesktopSaveContent.LocalFile }.file.extension.let { "image/$it" })
        assertFalse((save.content.single() as DesktopSaveContent.LocalFile).file.exists(), "controlled source PNG is cleaned")
        assertEquals(0, revealSideEffects, "platform acceptance must not open a file manager")
    }

    @Test
    fun `native acceptance distinguishes launch from asynchronous terminal`() = runBlocking {
        val session = ImmediateSession(DesktopNativeShareTerminal.Shared)
        val controller = controller(
            service(nativePort = DesktopNativeSharePort { DesktopNativeShareOutcome.Opened(session) }),
        )

        val result = controller.share(TOKEN, PlatformShareKind.TEXT)

        assertEquals("OpenedNatively", result.launchResult)
        assertEquals("SharedNatively", result.terminalResult)
        assertNull(result.failure)
    }

    @Test
    fun `native acceptance reports cancellation failure and timeout separately`() = runBlocking {
        val cancelled = controller(
            service(
                nativePort = DesktopNativeSharePort {
                    DesktopNativeShareOutcome.Opened(ImmediateSession(DesktopNativeShareTerminal.Cancelled))
                },
            ),
        ).share(TOKEN, PlatformShareKind.TEXT)
        val failed = controller(
            service(
                nativePort = DesktopNativeSharePort {
                    DesktopNativeShareOutcome.Opened(ImmediateSession(DesktopNativeShareTerminal.Failed))
                },
            ),
        ).share(TOKEN, PlatformShareKind.TEXT)
        val timedOut = DesktopPlatformAcceptanceController(
            expectedToken = TOKEN,
            shareService = service(
                nativePort = DesktopNativeSharePort {
                    DesktopNativeShareOutcome.Opened(DesktopNativeShareSession { _ -> })
                },
            ),
            evidenceRoot = tempDir,
            terminalTimeoutMillis = 25,
        ).share(TOKEN, PlatformShareKind.TEXT)

        assertEquals("Cancelled", cancelled.terminalResult)
        assertNull(cancelled.failure)
        assertEquals("Failed:${DesktopShareFailureReason.NATIVE_SHARE_FAILED.name}", failed.terminalResult)
        assertNull(failed.failure)
        assertEquals("OpenedNatively", timedOut.launchResult)
        assertNull(timedOut.terminalResult)
        assertEquals(PlatformAcceptanceFailure.TERMINAL_TIMEOUT, timedOut.failure)
    }

    private fun controller(service: DesktopShareService) =
        DesktopPlatformAcceptanceController(
            expectedToken = TOKEN,
            shareService = service,
            evidenceRoot = tempDir,
            terminalTimeoutMillis = 2_000,
        )

    private fun service(
        nativePort: DesktopNativeSharePort = DesktopNativeSharePort { DesktopNativeShareOutcome.Unavailable },
        clipboard: DesktopClipboardPort = RecordingClipboard(),
        savePort: DesktopSavePort = RecordingSavePort(tempDir.resolve("saved.png").toFile()),
        revealPort: DesktopRevealPort = DesktopRevealPort { },
    ) = DesktopShareService(
        nativeSharePort = nativePort,
        clipboardPort = clipboard,
        savePort = savePort,
        isHeadless = { DesktopExternalActionPolicy.isShareSuppressed() },
        revealPort = revealPort,
    )

    private class RecordingClipboard : DesktopClipboardPort {
        val texts = mutableListOf<String>()

        override fun copyText(text: String) {
            texts += text
        }

        override fun copyImage(image: BufferedImage) = Unit
    }

    private class RecordingSavePort(private val destination: File) : DesktopSavePort {
        val content = mutableListOf<DesktopSaveContent>()

        override fun save(content: DesktopSaveContent, suggestedName: String): DesktopSaveOutcome {
            this.content += content
            return DesktopSaveOutcome.Saved(destination)
        }
    }

    private class ImmediateSession(private val terminal: DesktopNativeShareTerminal) : DesktopNativeShareSession {
        override fun onTerminal(callback: (DesktopNativeShareTerminal) -> Unit) = callback(terminal)
    }

    private fun post(uri: URI, token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody())
        token?.let { request.header(PLATFORM_ACCEPTANCE_TOKEN_HEADER, it) }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
