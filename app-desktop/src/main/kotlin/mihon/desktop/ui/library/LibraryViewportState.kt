package mihon.desktop.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.drop
import tachiyomi.domain.library.model.LibraryManga

internal data class LibraryViewportState(val grid: LazyGridState, val list: LazyListState)

/** Native Lazy owners for one layout. The ScreenModel retains only a temporary entity anchor. */
@Composable
internal fun rememberLibraryViewportState(
    model: LibraryScreenModel,
    categoryId: Long?,
    items: List<LibraryManga>,
    mode: LibraryDisplayMode,
): LibraryViewportState {
    val position = model.browsePosition(categoryId)
    val index = position?.indexIn(items) ?: 0
    val grid = rememberLazyGridState(index, 0)
    val list = rememberLazyListState(index, 0)
    LaunchedEffect(grid, list) {
        withFrameNanos { }
        val isList = mode == LibraryDisplayMode.LIST
        fun itemHeight() = if (isList) {
            list.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1
        } else {
            grid.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 1
        }
        val offset = (position?.offset ?: 0).coerceIn(0, (itemHeight() - 1).coerceAtLeast(0))
        if (isList) list.scrollToItem(index, offset) else grid.scrollToItem(index, offset)
        if (position != null && items.any { it.id == position.mangaId }) {
            model.rememberBrowsePosition(categoryId, position.copy(index = index, offset = offset))
        }
        // Initial grid row alignment must not replace the canonical manga anchor on each reflow.
        snapshotFlow {
            if (isList) {
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            } else {
                grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset
            }
        }.drop(1).collect { (firstIndex, scrollOffset) ->
            items.getOrNull(firstIndex)?.let { item ->
                model.rememberBrowsePosition(
                    categoryId,
                    LibraryBrowsePosition(
                        item.id,
                        firstIndex,
                        scrollOffset.coerceIn(0, (itemHeight() - 1).coerceAtLeast(0)),
                    ),
                )
            }
        }
    }
    return LibraryViewportState(grid, list)
}
