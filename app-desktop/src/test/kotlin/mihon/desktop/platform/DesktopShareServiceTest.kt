package mihon.desktop.platform

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import mihon.domain.platform.SharePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

class DesktopShareServiceTest {

    @Test
    fun `native success reports shared only after terminal callback`() {
        var received: DesktopNativeShareContent? = null
        var complete: ((DesktopNativeShareTerminal) -> Unit)? = null
        val service = service(native = DesktopNativeSharePort {
            received = it
            DesktopNativeShareOutcome.Opened(DesktopNativeShareSession { complete = it })
        })
        var terminal: DesktopShareResult? = null

        val result = service.share(SharePayload.Text("https://example.com/manga")) { terminal = it }

        assertEquals(DesktopNativeShareContent.Text("https://example.com/manga"), received)
        assertEquals(DesktopShareResult.OpenedNatively, result)
        assertEquals(null, terminal)
        requireNotNull(complete)(DesktopNativeShareTerminal.Shared)
        assertEquals(DesktopShareResult.SharedNatively, terminal)
    }

    @Test
    fun `image share snapshots are unique and each terminal cleans only its own file`() {
        val files = mutableListOf<File>()
        val terminals = mutableListOf<(DesktopNativeShareTerminal) -> Unit>()
        val service = service(native = DesktopNativeSharePort { content ->
            files += (content as DesktopNativeShareContent.LocalFile).file
            DesktopNativeShareOutcome.Opened(DesktopNativeShareSession { terminals += it })
        })
        val first = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, 0xFFFF0000.toInt()) }
        val second = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, 0xFF00FF00.toInt()) }

        service.shareImage(first)
        service.shareImage(second)
        service.shareImage(second)

        assertNotEquals(files[0], files[1])
        assertEquals(0xFFFF0000.toInt(), javax.imageio.ImageIO.read(files[0]).getRGB(0, 0))
        assertTrue(files.all(File::isFile))
        if (Files.getFileAttributeView(files[0].toPath(), PosixFileAttributeView::class.java) != null) {
            assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(files[0].toPath()))
        }
        terminals[0](DesktopNativeShareTerminal.Shared)
        assertTrue(!files[0].exists() && files[1].isFile)
        terminals[1](DesktopNativeShareTerminal.Cancelled)
        assertTrue(!files[1].exists() && files[2].isFile)
        terminals[2](DesktopNativeShareTerminal.Failed)
        assertTrue(files.none(File::exists))
    }

    @Test
    fun `image fallback and native registration failures clean their snapshot`() {
        val seen = mutableListOf<File>()
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        fun fallback(outcome: () -> DesktopSaveOutcome): DesktopShareResult = service(
            native = DesktopNativeSharePort { content -> seen += (content as DesktopNativeShareContent.LocalFile).file; DesktopNativeShareOutcome.Unavailable },
            save = DesktopSavePort { _, _ -> outcome() },
        ).shareImage(image)
        val destination = Files.createTempFile("mihon-shared-destination", ".png").toFile()
        assertEquals(DesktopShareResult.Saved(destination), fallback { DesktopSaveOutcome.Saved(destination) })
        assertTrue(!seen.removeLast().exists())
        assertEquals(DesktopShareResult.Cancelled, fallback { DesktopSaveOutcome.Cancelled })
        assertTrue(!seen.removeLast().exists())
        assertEquals(DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED), fallback { error("save") })
        assertTrue(!seen.removeLast().exists())
        val nativeFailure = service(native = DesktopNativeSharePort { content ->
            seen += (content as DesktopNativeShareContent.LocalFile).file
            error("launch")
        })
        assertEquals(DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED), nativeFailure.shareImage(image))
        assertTrue(!seen.removeLast().exists())
        seen.clear()
        val registrationFailure = service(native = DesktopNativeSharePort { content ->
            seen += (content as DesktopNativeShareContent.LocalFile).file
            DesktopNativeShareOutcome.Opened(DesktopNativeShareSession { error("register") })
        })
        assertEquals(DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED), registrationFailure.shareImage(image))
        assertTrue(!seen.single().exists())
    }

    @Test
    fun `headless environment is unavailable without invoking side effects`() {
        val service = service(
            native = DesktopNativeSharePort { error("native share must not run") },
            clipboard = object : DesktopClipboardPort {
                override fun copyText(text: String) = error("clipboard must not run")
                override fun copyImage(image: BufferedImage) = error("clipboard must not run")
            },
            headless = true,
        )

        assertEquals(
            DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS),
            service.share(SharePayload.Text("text")),
        )
    }

    @Test
    fun `unavailable native text share honestly falls back to clipboard`() {
        var copied: String? = null
        val clipboard = object : DesktopClipboardPort {
            override fun copyText(text: String) { copied = text }
            override fun copyImage(image: BufferedImage) = Unit
        }
        val service = service(clipboard = clipboard)

        assertEquals(DesktopShareResult.CopiedToClipboard, service.share(SharePayload.Text("fallback")))
        assertEquals("fallback", copied)
    }

    @Test
    fun `plain service constructor without a native port falls back to clipboard`() {
        var copied: String? = null
        val service = DesktopShareService(
            clipboardPort = object : DesktopClipboardPort {
                override fun copyText(text: String) { copied = text }
                override fun copyImage(image: BufferedImage) = Unit
            },
            isHeadless = { false },
        )

        assertEquals(DesktopShareResult.CopiedToClipboard, service.share(SharePayload.Text("fallback")))
        assertEquals("fallback", copied)
    }

    @Test
    fun `local file stream falls back to save and preserves cancellation`() {
        val source = Files.createTempFile("mihon-share", ".png").toFile()
        val destination = File(source.parentFile, "saved.png")
        var outcome: DesktopSaveOutcome = DesktopSaveOutcome.Saved(destination)
        val service = service(save = DesktopSavePort { content, _ ->
            assertEquals(DesktopSaveContent.LocalFile(source), content)
            outcome
        })
        val payload = SharePayload.Stream(source.toURI().toString(), "image/png")

        assertEquals(DesktopShareResult.Saved(destination), service.share(payload))
        outcome = DesktopSaveOutcome.Cancelled
        assertEquals(DesktopShareResult.Cancelled, service.share(payload))
    }

    @Test
    fun `clipboard busy and save failure are structured failures`() {
        val clipboardFailure = service(clipboard = object : DesktopClipboardPort {
            override fun copyText(text: String) = error("busy")
            override fun copyImage(image: BufferedImage) = error("busy")
        }).share(SharePayload.Text("text"))
        val saveFailure = service(save = DesktopSavePort { _, _ -> error("disk full") })
            .saveImage(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "page.png")

        assertEquals(
            DesktopShareResult.Failed(DesktopShareFailureReason.CLIPBOARD_BUSY),
            clipboardFailure,
        )
        assertEquals(DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED), saveFailure)
    }

    @Test
    fun `native failure exception and invalid remote stream never report native success`() {
        val failed = service(native = DesktopNativeSharePort { DesktopNativeShareOutcome.Failed }).share(SharePayload.Text("text"))
        val exception = service(native = DesktopNativeSharePort { error("boom") }).share(SharePayload.Text("text"))
        val invalid = service().share(SharePayload.Stream("https://example.com/page.png", "image/png"))

        assertEquals(DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED), failed)
        assertEquals(DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED), exception)
        assertEquals(DesktopShareResult.Unavailable(DesktopShareUnavailableReason.UNSUPPORTED_PAYLOAD), invalid)
    }

    @Test
    fun `notifications distinguish every terminal outcome`() {
        val messages = listOf(
            DesktopShareResult.SharedNatively,
            DesktopShareResult.CopiedToClipboard,
            DesktopShareResult.Saved(File("saved")),
            DesktopShareResult.Cancelled,
            DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS),
            DesktopShareResult.Failed(DesktopShareFailureReason.CLIPBOARD_BUSY),
        ).map { it.toDesktopNotification().message }

        assertTrue(messages.all(String::isNotBlank))
        assertNotEquals(messages[0], messages[1])
        assertNotEquals(messages[1], messages[2])
        assertNotEquals(messages[3], messages[4])
        assertNotEquals(messages[4], messages[5])
        assertEquals(
            MR.strings.decode_image_error.localized(),
            DesktopShareResult.Failed(DesktopShareFailureReason.INVALID_PAYLOAD).toDesktopNotification().message,
        )
    }

    @Test
    fun `saving a page reveals it after writing and keeps success if reveal fails`() {
        val destination = Files.createTempDirectory("mihon-save").resolve("page.png").toFile()
        var revealed: File? = null
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val service = DesktopShareService(
            isHeadless = { false },
            revealPort = DesktopRevealPort { revealed = it },
        )

        assertEquals(DesktopShareResult.Saved(destination), service.saveImage(image, destination))
        assertEquals(destination, revealed)
        val revealFailure = DesktopShareService(
            isHeadless = { false },
            revealPort = DesktopRevealPort { error("unsupported") },
        ).saveImage(image, destination)
        assertEquals(DesktopShareResult.Saved(destination), revealFailure)
    }

    private fun service(
        native: DesktopNativeSharePort = DesktopNativeSharePort { DesktopNativeShareOutcome.Unavailable },
        clipboard: DesktopClipboardPort = NoopClipboard,
        save: DesktopSavePort = DesktopSavePort { _, _ -> DesktopSaveOutcome.Cancelled },
        headless: Boolean = false,
    ) = DesktopShareService(
        nativeSharePort = native,
        clipboardPort = clipboard,
        savePort = save,
        isHeadless = { headless },
    )

    private object NoopClipboard : DesktopClipboardPort {
        override fun copyText(text: String) = Unit
        override fun copyImage(image: BufferedImage) = Unit
    }

}
