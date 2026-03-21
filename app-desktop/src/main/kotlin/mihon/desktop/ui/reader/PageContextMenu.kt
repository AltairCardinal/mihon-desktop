package mihon.desktop.ui.reader

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.desktop.reader.PageSaveHelper
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.IOException

/**
 * Wraps [content] in a right-click context menu with page actions:
 *   • Save Image → ~/Pictures/Mihon/{filename}.png
 *   • Copy to Clipboard
 *   • Set as Cover (calls back to the parent)
 *
 * Android reference: presentation/reader/ReaderPageActionsDialog.kt
 *
 * @param pageUrl       URL of the current page image.
 * @param mangaTitle    Used to build the save filename.
 * @param chapterTitle  Used to build the save filename.
 * @param pageIndex     0-based index; shown as p(n+1) in the filename.
 * @param scope         CoroutineScope for launching IO operations (save/copy).
 * @param onSetAsCover  Called when the user selects "Set as Cover".
 *                      Null hides that menu item (e.g. when no manga is tracked).
 * @param content       The composable to wrap (i.e. the page image).
 */
@Composable
internal fun PageContextMenu(
    pageUrl: String,
    mangaTitle: String,
    chapterTitle: String,
    pageIndex: Int,
    scope: CoroutineScope,
    onSetAsCover: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val items = buildList {
        add(ContextMenuItem("Save Image") {
            scope.launch(Dispatchers.IO) {
                val img = PageSaveHelper.loadImage(pageUrl) ?: return@launch
                val dir = PageSaveHelper.defaultSaveDirectory()
                val file = dir.resolve(
                    PageSaveHelper.buildSaveFileName(mangaTitle, chapterTitle, pageIndex),
                )
                PageSaveHelper.saveImageToFile(img, file)
                // Open the containing folder so user sees the result
                try {
                    java.awt.Desktop.getDesktop().open(dir)
                } catch (_: Exception) { /* best-effort */ }
            }
        })
        add(ContextMenuItem("Copy to Clipboard") {
            scope.launch(Dispatchers.IO) {
                val img = PageSaveHelper.loadImage(pageUrl) ?: return@launch
                val transferable = BufferedImageTransferable(img)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
            }
        })
        if (onSetAsCover != null) {
            add(ContextMenuItem("Set as Cover", onSetAsCover))
        }
    }

    ContextMenuArea(items = { items }) {
        content()
    }
}

// ── AWT clipboard helpers ─────────────────────────────────────────────────────

private class BufferedImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    @Throws(UnsupportedFlavorException::class, IOException::class)
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return image
    }
}
