package mihon.desktop.ui.library

import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
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

internal fun LazyListScope.mangaDetailChapterListItems(
    displayedChapterCount: Int,
    totalChapterCount: Int,
    chapterRows: List<MangaDetailChapterListRow>,
    downloadQueue: List<DownloadItem>,
    manga: Manga?,
    isChapterDownloaded: (Manga, Chapter) -> Boolean,
    isChapterSelected: (Long) -> Boolean,
    onSelectChapter: (Long) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onDeleteDownload: (Chapter) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onToggleBookmark: (Chapter) -> Unit,
    onReadChapter: (Chapter) -> Unit,
) {
    item {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chapters ($displayedChapterCount${if (displayedChapterCount != totalChapterCount) "/$totalChapterCount" else ""})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
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
                val downloadStatus = when {
                    queuedItem != null -> when (queuedItem.status) {
                        DownloadStatus.DOWNLOADING -> ChapterDownloadStatus.DOWNLOADING
                        else -> ChapterDownloadStatus.QUEUED
                    }
                    manga != null && isChapterDownloaded(manga, chapter) -> ChapterDownloadStatus.DOWNLOADED
                    else -> ChapterDownloadStatus.NOT_DOWNLOADED
                }
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
                    onSelect = { onSelectChapter(chapter.id) },
                    onDownload = { onDownloadChapter(chapter) },
                    onDeleteDownload = { onDeleteDownload(chapter) },
                    onCancelDownload = { onCancelDownload(chapter.id) },
                    onToggleBookmark = { onToggleBookmark(chapter) },
                    onRead = { onReadChapter(chapter) },
                )
            }
        }
    }
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
