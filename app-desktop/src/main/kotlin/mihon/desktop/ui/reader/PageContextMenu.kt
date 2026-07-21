package mihon.desktop.ui.reader

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.platform.DesktopShareFailureReason
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.platform.toDesktopNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.desktop.reader.PageSaveHelper
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.splitPageBounds
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import tachiyomi.i18n.MR

/**
 * Wraps [content] in a right-click context menu with page actions:
 *   • 分享图片 → native share with manga/chapter/page info, otherwise an honest save fallback
 *   • 复制到剪贴板
 *   • 保存图片 → ~/Pictures/Mihon/{filename}.png
 *   • 设为封面 (calls back to the parent)
 *
 * Android reference: presentation/reader/ReaderPageActionsDialog.kt
 *
 * @param pageUrl       URL of the current page image.
 * @param mangaTitle    Used to build the save filename.
 * @param chapterTitle  Used to build the save filename.
 * @param pageIndex     0-based index; shown as p(n+1) in the filename.
 * @param scope         CoroutineScope for launching IO operations (share/copy/save).
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
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    saveDirectory: File = PageSaveHelper.defaultSaveDirectory(),
    content: @Composable () -> Unit,
) {
    val dependencies = LocalDesktopUiDependencies.current
    val labels = pageContextMenuLabels(includeSetAsCover = onSetAsCover != null)
    val items = buildList {
        add(ContextMenuItem(labels[0]) {
            scope.launch(Dispatchers.IO) {
                val sharedImage = loadPageContextMenuImage(pageUrl, splitHalf, sourceBounds)
                performPageContextMenuImageAction(
                    PageContextMenuImageAction.SHARE,
                    sharedImage,
                    File(saveDirectory, "shared-page.png"),
                    dependencies.shareService,
                    dependencies.notificationService,
                    shareMessage = MR.strings.share_page_info.localized(
                        Locale.getDefault(),
                        mangaTitle,
                        chapterTitle,
                        pageIndex + 1,
                    ),
                )
            }
        })
        add(ContextMenuItem(labels[1]) {
            scope.launch(Dispatchers.IO) {
                val img = loadPageContextMenuImage(pageUrl, splitHalf, sourceBounds)
                performPageContextMenuImageAction(
                    PageContextMenuImageAction.COPY,
                    img,
                    File(saveDirectory, "page.png"),
                    dependencies.shareService,
                    dependencies.notificationService,
                )
            }
        })
        add(ContextMenuItem(labels[2]) {
            scope.launch(Dispatchers.IO) {
                val img = loadPageContextMenuImage(pageUrl, splitHalf, sourceBounds)
                val destination = saveDirectory.resolve(
                    PageSaveHelper.buildSaveFileName(mangaTitle, chapterTitle, pageIndex),
                )
                performPageContextMenuImageAction(
                    PageContextMenuImageAction.SAVE,
                    img,
                    destination,
                    dependencies.shareService,
                    dependencies.notificationService,
                )
            }
        })
        if (onSetAsCover != null) {
            add(ContextMenuItem(labels[3], onSetAsCover))
        }
    }

    ContextMenuArea(items = { items }) {
        content()
    }
}

internal fun loadPageContextMenuImage(
    pageUrl: String,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
): BufferedImage? {
    val source = PageSaveHelper.loadImage(pageUrl) ?: return null
    val bounds = sourceBounds ?: splitHalf?.let { splitPageBounds(source.width, source.height, it) }
        ?: return source
    if (
        bounds.x < 0 ||
        bounds.y < 0 ||
        bounds.width <= 0 ||
        bounds.height <= 0 ||
        bounds.x.toLong() + bounds.width.toLong() > source.width.toLong() ||
        bounds.y.toLong() + bounds.height.toLong() > source.height.toLong()
    ) {
        return null
    }
    return source.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height)
}

internal fun pageContextMenuLabels(includeSetAsCover: Boolean): List<String> = buildList {
    add("分享图片")
    add("复制到剪贴板")
    add("保存图片")
    if (includeSetAsCover) add("设为封面")
}

// ── Shared desktop action wiring ──────────────────────────────────────────────

internal enum class PageContextMenuImageAction { SHARE, COPY, SAVE }

internal fun performPageContextMenuImageAction(
    action: PageContextMenuImageAction,
    image: BufferedImage?,
    destination: File,
    shareService: DesktopShareService,
    notificationService: DesktopNotificationService,
    shareMessage: String? = null,
): DesktopShareResult {
    val result = if (image == null) {
        DesktopShareResult.Failed(DesktopShareFailureReason.INVALID_PAYLOAD)
    } else {
        when (action) {
            PageContextMenuImageAction.SHARE -> shareService.shareImage(image, shareMessage) { terminal ->
                notificationService.post(terminal.toDesktopNotification())
            }
            PageContextMenuImageAction.COPY -> shareService.copyImage(image)
            PageContextMenuImageAction.SAVE -> shareService.saveImage(image, destination)
        }
    }
    if (result != DesktopShareResult.OpenedNatively) {
        notificationService.post(result.toDesktopNotification())
    }
    return result
}
