package mihon.desktop.ui.reader

import tachiyomi.i18n.MR

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asSkiaBitmap
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.platform.DesktopShareFailureReason
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.platform.toDesktopNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderImageAssetLease
import mihon.desktop.reader.PageSaveHelper
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.splitPageBounds
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale

/**
 * Wraps [content] in a right-click context menu with page actions:
 *   • 分享图片 → native share with manga/chapter/page info, otherwise an honest save fallback
 *   • 复制到剪贴板
 *   • 保存图片 → ~/Pictures/Mihon/{filename}.png
 *   • 设为封面 (calls back to the parent)
 *
 * Android reference: presentation/reader/ReaderPageActionsDialog.kt
 *
 * @param imageLeaseProvider Lazily retains the visible slot's stable decoded image.
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
    imageLeaseProvider: () -> DesktopReaderImageAssetLease?,
    mangaTitle: String,
    chapterTitle: String,
    pageIndex: Int,
    scope: CoroutineScope,
    onSetAsCover: (() -> Unit)?,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    saveDirectoryProvider: () -> File = PageSaveHelper::defaultSaveDirectory,
    content: @Composable () -> Unit,
) {
    val dependencies = LocalDesktopUiDependencies.current
    val labels = pageContextMenuLabels(includeSetAsCover = onSetAsCover != null)
    val items = buildList {
        add(ContextMenuItem(labels[0]) {
            scope.launch(Dispatchers.IO) {
                val sharedImage = imageLeaseProvider().useAsset(splitHalf, sourceBounds)
                performPageContextMenuImageAction(
                    PageContextMenuImageAction.SHARE,
                    sharedImage,
                    File("shared-page.png"),
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
                val img = imageLeaseProvider().useAsset(splitHalf, sourceBounds)
                performPageContextMenuImageAction(
                    PageContextMenuImageAction.COPY,
                    img,
                    File("page.png"),
                    dependencies.shareService,
                    dependencies.notificationService,
                )
            }
        })
        add(ContextMenuItem(labels[2]) {
            scope.launch(Dispatchers.IO) {
                val img = imageLeaseProvider().useAsset(splitHalf, sourceBounds)
                val destination = saveDirectoryProvider().resolve(
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
    asset: DesktopReaderImageAsset,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
): BufferedImage? {
    val bitmap = asset.bitmap.asSkiaBitmap()
    val bounds = when {
        sourceBounds != null -> runCatching {
            sourceBounds.mapToBitmap(
                sourceWidth = asset.sourceWidth,
                sourceHeight = asset.sourceHeight,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
            )
        }.getOrNull() ?: return null
        splitHalf != null -> splitPageBounds(bitmap.width, bitmap.height, splitHalf) ?: return null
        else -> PixelBounds(0, 0, bitmap.width, bitmap.height)
    }
    if (
        bounds.x < 0 ||
        bounds.y < 0 ||
        bounds.width <= 0 ||
        bounds.height <= 0 ||
            bounds.x.toLong() + bounds.width.toLong() > bitmap.width.toLong() ||
            bounds.y.toLong() + bounds.height.toLong() > bitmap.height.toLong()
    ) {
        return null
    }
    val argb = IntArray(bounds.width * bounds.height) { index ->
        val x = bounds.x + index % bounds.width
        val y = bounds.y + index / bounds.width
        bitmap.getColor(x, y)
    }
    return BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, bounds.width, bounds.height, argb, 0, bounds.width)
    }
}

private fun DesktopReaderImageAssetLease?.useAsset(
    splitHalf: PageSplitHalf?,
    sourceBounds: PixelBounds?,
): BufferedImage? = this?.use { lease ->
    loadPageContextMenuImage(lease.asset, splitHalf, sourceBounds)
}

internal fun pageContextMenuLabels(
    includeSetAsCover: Boolean,
    locale: Locale = Locale.getDefault(),
): List<String> = buildList {
    add(MR.strings.action_share.localized(locale))
    add(MR.strings.action_copy_to_clipboard.localized(locale))
    add(MR.strings.action_save.localized(locale))
    if (includeSetAsCover) add(MR.strings.set_as_cover.localized(locale))
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
