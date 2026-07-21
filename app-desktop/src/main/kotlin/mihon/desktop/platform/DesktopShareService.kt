package mihon.desktop.platform

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import mihon.desktop.domain.DesktopNotification
import mihon.domain.platform.SharePayload
import tachiyomi.i18n.MR

class DesktopShareService(
    private val nativeSharePort: DesktopNativeSharePort = UnavailableNativeSharePort,
    private val clipboardPort: DesktopClipboardPort = AwtDesktopClipboardPort,
    private val savePort: DesktopSavePort = SwingDesktopSavePort,
    private val isHeadless: () -> Boolean = GraphicsEnvironment::isHeadless,
    private val revealPort: DesktopRevealPort = AwtDesktopRevealPort,
) {
    fun share(payload: SharePayload): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        val content = payload.toDesktopContent()
            ?: return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.UNSUPPORTED_PAYLOAD)
        return when (runCatching { nativeSharePort.share(content) }.getOrNull()) {
            DesktopNativeShareOutcome.Shared -> DesktopShareResult.SharedNatively
            DesktopNativeShareOutcome.Unavailable -> when (content) {
                is DesktopNativeShareContent.Text -> copyTextUnchecked(content.text)
                is DesktopNativeShareContent.LocalFile -> saveUnchecked(
                    DesktopSaveContent.LocalFile(content.file),
                    content.file.name,
                )
            }
            else -> DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED)
        }
    }

    fun copyText(text: String): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        return copyTextUnchecked(text)
    }

    fun copyImage(image: BufferedImage): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        return runCatching { clipboardPort.copyImage(image) }
            .fold(
                onSuccess = { DesktopShareResult.CopiedToClipboard },
                onFailure = { DesktopShareResult.Failed(DesktopShareFailureReason.CLIPBOARD_BUSY) },
            )
    }

    fun saveImage(image: BufferedImage, suggestedName: String): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        return saveUnchecked(DesktopSaveContent.Image(image), suggestedName)
    }

    fun saveImage(image: BufferedImage, destination: File): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        return runCatching {
            destination.parentFile?.mkdirs()
            check(ImageIO.write(image, "png", destination))
            revealBestEffort(destination)
            DesktopShareResult.Saved(destination)
        }.getOrElse { DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED) }
    }

    fun shareImage(image: BufferedImage, message: String? = null): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        val file = runCatching { LastSharedImageCache.replace(image) }
            .getOrElse { return DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED) }
        return share(SharePayload.Stream(file.toURI().toString(), "image/png", message))
    }

    private fun copyTextUnchecked(text: String) = runCatching { clipboardPort.copyText(text) }
        .fold(
            onSuccess = { DesktopShareResult.CopiedToClipboard },
            onFailure = { DesktopShareResult.Failed(DesktopShareFailureReason.CLIPBOARD_BUSY) },
        )

    private fun saveUnchecked(content: DesktopSaveContent, suggestedName: String) =
        runCatching { savePort.save(content, suggestedName) }
            .fold(
                onSuccess = {
                    when (it) {
                        is DesktopSaveOutcome.Saved -> {
                            revealBestEffort(it.file)
                            DesktopShareResult.Saved(it.file)
                        }
                        DesktopSaveOutcome.Cancelled -> DesktopShareResult.Cancelled
                    }
                },
                onFailure = { DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED) },
            )

    private fun revealBestEffort(file: File) {
        runCatching { revealPort.reveal(file) }
    }
}

sealed interface DesktopShareResult {
    data object SharedNatively : DesktopShareResult
    data object CopiedToClipboard : DesktopShareResult
    data class Saved(val file: File) : DesktopShareResult
    data object Cancelled : DesktopShareResult
    data class Unavailable(val reason: DesktopShareUnavailableReason) : DesktopShareResult
    data class Failed(val reason: DesktopShareFailureReason) : DesktopShareResult
}

enum class DesktopShareUnavailableReason { HEADLESS, UNSUPPORTED_PAYLOAD }
enum class DesktopShareFailureReason { NATIVE_SHARE_FAILED, CLIPBOARD_BUSY, SAVE_FAILED, INVALID_PAYLOAD }

fun DesktopShareResult.toDesktopNotification(): DesktopNotification = DesktopNotification(
    title = MR.strings.action_share.localized(),
    message = when (this) {
        DesktopShareResult.SharedNatively -> MR.strings.completed.localized()
        DesktopShareResult.CopiedToClipboard -> MR.strings.copied_to_clipboard_plain.localized()
        is DesktopShareResult.Saved -> MR.strings.picture_saved.localized()
        DesktopShareResult.Cancelled -> MR.strings.cancelled.localized()
        is DesktopShareResult.Unavailable -> MR.strings.unknown_error.localized()
        is DesktopShareResult.Failed -> when (reason) {
            DesktopShareFailureReason.CLIPBOARD_BUSY -> MR.strings.clipboard_copy_error.localized()
            DesktopShareFailureReason.SAVE_FAILED -> MR.strings.error_saving_picture.localized()
            DesktopShareFailureReason.NATIVE_SHARE_FAILED -> MR.strings.error_sharing_cover.localized()
            DesktopShareFailureReason.INVALID_PAYLOAD -> MR.strings.decode_image_error.localized()
        }
    },
)

fun interface DesktopNativeSharePort {
    fun share(content: DesktopNativeShareContent): DesktopNativeShareOutcome
}

sealed interface DesktopNativeShareContent {
    data class Text(val text: String) : DesktopNativeShareContent
    data class LocalFile(val file: File, val mimeType: String, val message: String?) : DesktopNativeShareContent
}

enum class DesktopNativeShareOutcome { Shared, Unavailable, Failed }

interface DesktopClipboardPort {
    fun copyText(text: String)
    fun copyImage(image: BufferedImage)
}

fun interface DesktopSavePort {
    fun save(content: DesktopSaveContent, suggestedName: String): DesktopSaveOutcome
}

fun interface DesktopRevealPort {
    fun reveal(file: File)
}

sealed interface DesktopSaveContent {
    data class Image(val image: BufferedImage) : DesktopSaveContent
    data class LocalFile(val file: File) : DesktopSaveContent
}

sealed interface DesktopSaveOutcome {
    data class Saved(val file: File) : DesktopSaveOutcome
    data object Cancelled : DesktopSaveOutcome
}

private fun SharePayload.toDesktopContent(): DesktopNativeShareContent? = when (this) {
    is SharePayload.Text -> DesktopNativeShareContent.Text(text)
    is SharePayload.Stream -> runCatching {
        val file = File(java.net.URI(uri)).takeIf(File::isFile) ?: return null
        DesktopNativeShareContent.LocalFile(file, mimeType, message)
    }.getOrNull()
}

private object UnavailableNativeSharePort : DesktopNativeSharePort {
    override fun share(content: DesktopNativeShareContent) = DesktopNativeShareOutcome.Unavailable
}

private object LastSharedImageCache {
    private val file = File(System.getProperty("java.io.tmpdir"), "mihon/mihon-shared-page.png")

    @Synchronized
    fun replace(image: BufferedImage): File {
        file.parentFile.mkdirs()
        val pending = File(file.parentFile, "${file.name}.pending")
        check(ImageIO.write(image, "png", pending))
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return file
    }
}

private object AwtDesktopClipboardPort : DesktopClipboardPort {
    override fun copyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun copyImage(image: BufferedImage) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
    }
}

private object AwtDesktopRevealPort : DesktopRevealPort {
    override fun reveal(file: File) {
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().open(file.parentFile ?: file)
    }
}

private object SwingDesktopSavePort : DesktopSavePort {
    override fun save(content: DesktopSaveContent, suggestedName: String): DesktopSaveOutcome {
        var selected: File? = null
        val choose = {
            val chooser = JFileChooser().apply { selectedFile = File(suggestedName) }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selected = chooser.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) choose() else SwingUtilities.invokeAndWait(choose)
        val destination = selected ?: return DesktopSaveOutcome.Cancelled
        when (content) {
            is DesktopSaveContent.Image -> check(ImageIO.write(content.image, "png", destination))
            is DesktopSaveContent.LocalFile -> Files.copy(
                content.file.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return DesktopSaveOutcome.Saved(destination)
    }
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return image
    }
}
