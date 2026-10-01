package mihon.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.util.Locale

internal fun LazyListScope.mangaDetailChapterListItems(
    displayedChapterCount: Int,
    totalChapterCount: Int,
    chapterRows: List<MangaDetailChapterListRow>,
    downloadQueue: List<DownloadItem>,
    manga: Manga?,
    isChapterDownloaded: (Manga, Chapter) -> Boolean,
    isChapterSelected: (Long) -> Boolean,
    isSelectionMode: Boolean,
    onSelectChapter: (Long) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onDeleteDownload: (Chapter) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onRetryDownload: (Long) -> Unit,
    onToggleBookmark: (Chapter) -> Unit,
    onReadChapter: (Chapter) -> Unit,
    missingChapterCount: Int = 0,
    hasActiveFilters: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    onPrimaryChapterClick: ((Chapter, LibraryClickModifiers) -> Unit)? = null,
    onToggleRead: ((Chapter) -> Unit)? = null,
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth().then(
                if (!isSelectionMode &&
                    onOpenSettings != null
                ) {
                    Modifier.clickable(onClick = onOpenSettings)
                } else {
                    Modifier
                },
            ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = MR.strings.desktop_ui_chapter_count.localized(Locale.getDefault(), displayedChapterCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (hasActiveFilters) {
                    Text(
                        MR.strings.action_filter.localized(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (missingChapterCount > 0) {
                    Text(missingChapterCountText(missingChapterCount), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider()
    }
    items(
        chapterRows,
        key = { row ->
            when (row) {
                is MangaDetailChapterListRow.ChapterRow -> "chapter-${row.chapter.id}"
                is MangaDetailChapterListRow.MissingCountRow -> row.id
            }
        },
    ) { row ->
        when (row) {
            is MangaDetailChapterListRow.MissingCountRow -> {
                MissingChapterCountRow(count = row.count)
            }
            is MangaDetailChapterListRow.ChapterRow -> {
                val chapter = row.chapter
                val queuedItem = downloadQueue.find { it.chapterId == chapter.id }
                val downloadStatus = chapterDownloadStatus(
                    queuedItem = queuedItem,
                    isDownloaded = manga != null && isChapterDownloaded(manga, chapter),
                )
                val downloadProgress = downloadProgressFraction(
                    progress = queuedItem?.progress ?: 0,
                    totalPages = queuedItem?.pageUrls?.size ?: 0,
                )
                ChapterRow(
                    chapter = chapter,
                    title = chapterDisplayTitle(chapter, manga?.displayMode ?: Manga.CHAPTER_DISPLAY_NAME),
                    downloadStatus = downloadStatus,
                    downloadProgress = downloadProgress,
                    isSelected = isChapterSelected(chapter.id),
                    isSelectionMode = isSelectionMode,
                    onSelect = { onSelectChapter(chapter.id) },
                    onDownload = { onDownloadChapter(chapter) },
                    onDeleteDownload = { onDeleteDownload(chapter) },
                    onCancelDownload = { onCancelDownload(chapter.id) },
                    onRetryDownload = { onRetryDownload(chapter.id) },
                    onToggleBookmark = { onToggleBookmark(chapter) },
                    onRead = { onReadChapter(chapter) },
                    onToggleRead = onToggleRead?.let { toggle -> { toggle(chapter) } },
                    onPrimaryClick = onPrimaryChapterClick?.let { onPrimary ->
                        { modifiers -> onPrimary(chapter, modifiers) }
                    },
                    downloadEnabled = manga?.source != 0L,
                )
            }
        }
    }
}

internal fun chapterDownloadStatus(
    queuedItem: DownloadItem?,
    isDownloaded: Boolean,
): ChapterDownloadStatus = when (queuedItem?.status) {
    DownloadStatus.QUEUED -> ChapterDownloadStatus.QUEUED
    DownloadStatus.DOWNLOADING -> ChapterDownloadStatus.DOWNLOADING
    DownloadStatus.ERROR -> ChapterDownloadStatus.ERROR
    DownloadStatus.DONE -> ChapterDownloadStatus.DOWNLOADED
    DownloadStatus.CANCELLED,
    null,
    -> if (isDownloaded) ChapterDownloadStatus.DOWNLOADED else ChapterDownloadStatus.NOT_DOWNLOADED
}

@Composable
private fun MissingChapterCountRow(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = missingChapterCountText(count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

internal fun missingChapterCountText(
    count: Int,
    locale: Locale = Locale.getDefault(),
): String = MR.strings.desktop_ui_missing_chapter_count.localized(locale, count)
