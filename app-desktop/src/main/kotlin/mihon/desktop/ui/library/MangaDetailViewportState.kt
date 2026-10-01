package mihon.desktop.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos

internal data class MangaDetailChapterPosition(val chapterId: Long, val rowIndex: Int, val offset: Int)

/** Only chapter entities are anchors; information and missing-count rows never become identities. */
@Composable
internal fun rememberMangaDetailChapterState(
    model: MangaDetailScreenModel,
    rows: List<MangaDetailChapterListRow>,
    prefixItems: Int,
): LazyListState {
    val state = rememberLazyListState()
    LaunchedEffect(state, rows, prefixItems) {
        val previous = model.chapterPosition
        withFrameNanos { }
        if (previous != null && rows.isNotEmpty()) {
            val matching = rows.indexOfFirst {
                it is MangaDetailChapterListRow.ChapterRow && it.chapter.id == previous.chapterId
            }
            val index = if (matching >= 0) {
                matching
            } else {
                val bounded = previous.rowIndex.coerceIn(rows.indices)
                (bounded..rows.lastIndex).firstOrNull { rows[it] is MangaDetailChapterListRow.ChapterRow }
                    ?: (bounded downTo 0).firstOrNull { rows[it] is MangaDetailChapterListRow.ChapterRow }
                    ?: return@LaunchedEffect
            }
            state.scrollToItem(index + prefixItems)
            withFrameNanos { }
            val itemHeight =
                state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index + prefixItems }?.size ?: 1
            state.scrollToItem(index + prefixItems, previous.offset.coerceIn(0, (itemHeight - 1).coerceAtLeast(0)))
            withFrameNanos { }
        }
        snapshotFlow {
            if (state.firstVisibleItemIndex < prefixItems) return@snapshotFlow null
            val item =
                state.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("chapter-") == true }
                    ?: return@snapshotFlow null
            val id = (item.key as String).removePrefix("chapter-").toLongOrNull() ?: return@snapshotFlow null
            val index = rows.indexOfFirst {
                it is MangaDetailChapterListRow.ChapterRow && it.chapter.id == id
            }
            if (index < 0) return@snapshotFlow null
            MangaDetailChapterPosition(
                id,
                index,
                (state.layoutInfo.viewportStartOffset - item.offset).coerceIn(0, (item.size - 1).coerceAtLeast(0)),
            )
        }.collect { position ->
            model.chapterPosition = position
        }
    }
    return state
}
