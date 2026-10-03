package mihon.desktop.platform

import mihon.desktop.domain.DesktopNotification
import mihon.domain.platform.SharePayload
import tachiyomi.i18n.MR
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

class DesktopShareService(
    private val nativeSharePort: DesktopNativeSharePort = UnavailableDesktopNativeSharePort,
    private val clipboardPort: DesktopClipboardPort = AwtDesktopClipboardPort,
    private val savePort: DesktopSavePort = SwingDesktopSavePort(),
    private val isHeadless: () -> Boolean = {
        GraphicsEnvironment.isHeadless() || DesktopExternalActionPolicy.isShareSuppressed()
    },
    private val revealPort: DesktopRevealPort = AwtDesktopRevealPort,
    private val overwriteConfirmation: (File) -> Boolean = ::confirmDesktopOverwrite,
    private val saveContentWriter: (DesktopSaveContent, File) -> Unit = ::writeDesktopSaveContent,
) {
    fun share(
        payload: SharePayload,
        onTerminal: (DesktopShareResult) -> Unit = {},
    ): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        val content = payload.toDesktopContent()
            ?: return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.UNSUPPORTED_PAYLOAD)
        return when (val nativeOutcome = runCatching { nativeSharePort.share(content) }.getOrNull()) {
            is DesktopNativeShareOutcome.Opened -> runCatching {
                nativeOutcome.session.onTerminal { terminal -> onTerminal(terminal.toShareResult()) }
                DesktopShareResult.OpenedNatively
            }.getOrElse { DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED) }
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
            when (
                saveDesktopContent(
                    DesktopSaveContent.Image(image),
                    destination,
                    overwriteConfirmation,
                    saveContentWriter,
                )
            ) {
                DesktopSaveOutcome.Cancelled -> DesktopShareResult.Cancelled
                is DesktopSaveOutcome.Saved -> {
                    revealBestEffort(destination)
                    DesktopShareResult.Saved(destination)
                }
            }
        }.getOrElse { DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED) }
    }

    fun shareImage(
        image: BufferedImage,
        message: String? = null,
        onTerminal: (DesktopShareResult) -> Unit = {},
    ): DesktopShareResult {
        if (isHeadless()) return DesktopShareResult.Unavailable(DesktopShareUnavailableReason.HEADLESS)
        val file = runCatching { LastSharedImageCache.create(image) }
            .getOrElse { return DesktopShareResult.Failed(DesktopShareFailureReason.SAVE_FAILED) }
        val result = share(SharePayload.Stream(file.toURI().toString(), "image/png", message)) { terminal ->
            runCatching { Files.deleteIfExists(file.toPath()) }
            onTerminal(terminal)
        }
        if (result !is DesktopShareResult.OpenedNatively) runCatching { Files.deleteIfExists(file.toPath()) }
        return result
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
    data object OpenedNatively : DesktopShareResult
    data object SharedNatively : DesktopShareResult
    data object CopiedToClipboard : DesktopShareResult
    data class Saved(val file: File) : DesktopShareResult
    data object Cancelled : DesktopShareResult
    data class Unavailable(val reason: DesktopShareUnavailableReason) : DesktopShareResult
    data class Failed(val reason: DesktopShareFailureReason) : DesktopShareResult
}

enum class DesktopShareUnavailableReason { HEADLESS, UNSUPPORTED_PAYLOAD }
enum class DesktopShareFailureReason { NATIVE_SHARE_FAILED, CLIPBOARD_BUSY, SAVE_FAILED, INVALID_PAYLOAD }

fun DesktopShareResult.toDesktopNotification(
    title: String = MR.strings.action_share.localized(),
): DesktopNotification = DesktopNotification(
    title = title,
    message = when (this) {
        DesktopShareResult.OpenedNatively -> MR.strings.action_share.localized()
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

fun interface DesktopNativeSharePort : AutoCloseable {
    fun share(content: DesktopNativeShareContent): DesktopNativeShareOutcome

    override fun close() = Unit
}

sealed interface DesktopNativeShareContent {
    data class Text(val text: String) : DesktopNativeShareContent
    data class LocalFile(val file: File, val mimeType: String, val message: String?) : DesktopNativeShareContent
}

sealed interface DesktopNativeShareOutcome {
    data class Opened(val session: DesktopNativeShareSession) : DesktopNativeShareOutcome
    data object Unavailable : DesktopNativeShareOutcome
    data object Failed : DesktopNativeShareOutcome
}

fun interface DesktopNativeShareSession {
    fun onTerminal(callback: (DesktopNativeShareTerminal) -> Unit)
}

enum class DesktopNativeShareTerminal { Shared, Cancelled, Failed }

private fun DesktopNativeShareTerminal.toShareResult(): DesktopShareResult = when (this) {
    DesktopNativeShareTerminal.Shared -> DesktopShareResult.SharedNatively
    DesktopNativeShareTerminal.Cancelled -> DesktopShareResult.Cancelled
    DesktopNativeShareTerminal.Failed -> DesktopShareResult.Failed(DesktopShareFailureReason.NATIVE_SHARE_FAILED)
}

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

private object LastSharedImageCache {
    fun create(image: BufferedImage): File = createSharedImageSnapshot(image)
}

internal fun createSharedImageSnapshot(
    image: BufferedImage,
    directory: File = File(System.getProperty("java.io.tmpdir"), "mihon"),
    onCreated: (Path) -> Unit = {},
): File {
    directory.mkdirs()
    val path = createPrivateShareTempFile(directory.toPath())
    return try {
        onCreated(path)
        check(ImageIO.write(image, "png", path.toFile()))
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PRIVATE_SHARE_PERMISSIONS)
        }
        path.toFile()
    } catch (failure: Throwable) {
        runCatching { Files.deleteIfExists(path) }
        throw failure
    }
}

private val PRIVATE_SHARE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

private fun createPrivateShareTempFile(directory: Path): Path =
    if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
        Files.createTempFile(
            directory,
            "mihon-shared-page-",
            ".png",
            PosixFilePermissions.asFileAttribute(PRIVATE_SHARE_PERMISSIONS),
        )
    } else {
        Files.createTempFile(directory, "mihon-shared-page-", ".png")
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
        DesktopExternalActionPolicy.requireAllowed("Desktop reveal")
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().open(file.parentFile ?: file)
    }
}

internal class SwingDesktopSavePort(
    private val chooseDestination: (String) -> File? = ::chooseDesktopSaveDestination,
    private val overwriteConfirmation: (File) -> Boolean = ::confirmDesktopOverwrite,
    private val contentWriter: (DesktopSaveContent, File) -> Unit = ::writeDesktopSaveContent,
) : DesktopSavePort {
    override fun save(content: DesktopSaveContent, suggestedName: String): DesktopSaveOutcome {
        val destination = chooseDestination(suggestedName) ?: return DesktopSaveOutcome.Cancelled
        return saveDesktopContent(content, destination, overwriteConfirmation, contentWriter)
    }
}

private fun chooseDesktopSaveDestination(suggestedName: String): File? {
    var selected: File? = null
    val choose = {
        val chooser = JFileChooser().apply { selectedFile = File(suggestedName) }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selected = chooser.selectedFile
    }
    if (SwingUtilities.isEventDispatchThread()) choose() else SwingUtilities.invokeAndWait(choose)
    return selected
}

private fun confirmDesktopOverwrite(destination: File): Boolean {
    var confirmed = false
    val confirm = {
        confirmed = javax.swing.JOptionPane.showConfirmDialog(
            null,
            MR.strings.sync_replace_file.localized(),
            MR.strings.action_save.localized(),
            javax.swing.JOptionPane.YES_NO_OPTION,
        ) == javax.swing.JOptionPane.YES_OPTION
    }
    if (SwingUtilities.isEventDispatchThread()) confirm() else SwingUtilities.invokeAndWait(confirm)
    return confirmed
}

private fun saveDesktopContent(
    content: DesktopSaveContent,
    destination: File,
    confirmOverwrite: (File) -> Boolean,
    writer: (DesktopSaveContent, File) -> Unit,
): DesktopSaveOutcome {
    val target = destination.toPath().toAbsolutePath().normalize()
    if (Files.exists(target) && !confirmOverwrite(destination)) return DesktopSaveOutcome.Cancelled
    Files.createDirectories(target.parent)
    val staging = Files.createTempFile(target.parent, ".mihon-save-", ".tmp")
    try {
        writer(content, staging.toFile())
        // If the filesystem cannot replace atomically, preserve the original and report
        // failure. There is deliberately no destructive copy-over fallback.
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return DesktopSaveOutcome.Saved(destination)
    } finally {
        Files.deleteIfExists(staging)
    }
}

private fun writeDesktopSaveContent(content: DesktopSaveContent, destination: File) {
    when (content) {
        is DesktopSaveContent.Image -> check(ImageIO.write(content.image, "png", destination))
        is DesktopSaveContent.LocalFile -> Files.copy(
            content.file.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
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
