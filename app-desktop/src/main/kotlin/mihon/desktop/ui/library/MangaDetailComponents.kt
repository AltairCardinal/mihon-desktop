package mihon.desktop.ui.library

import tachiyomi.i18n.MR
import java.util.Locale

import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.platform.toDesktopNotification
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.domain.task.TaskState
import mihon.domain.platform.SharePayload
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.authors.AuthorDetailScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.manga.model.Manga
import androidx.compose.foundation.layout.size as layoutSize

@Composable
internal fun MangaHeader(
    manga: Manga,
    coverModel: String?,
    coverLastModified: Long,
    coverFeedback: String?,
    onEditCover: () -> Unit,
    onDeleteCover: () -> Unit,
    sourceName: String?,
    onTagSearch: (String) -> Unit,
    onTagCopy: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val coverRequestState = rememberMangaCoverRequestState(manga.id, manga.source, coverModel, coverLastModified)
    var showCoverMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            key(coverRequestState.stateKey) {
                AsyncImage(
                    model = coverRequestState.request,
                    contentDescription = manga.title,
                    contentScale = ContentScale.Crop,
                    placeholder = null,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(0.7f),
                )
            }
            // Edit cover button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd),
            ) {
                IconButton(
                    onClick = { showCoverMenu = true },
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = MR.strings.action_edit_cover.localized(),
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = showCoverMenu,
                    onDismissRequest = { showCoverMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(MR.strings.action_edit_cover.localized()) },
                        onClick = {
                            showCoverMenu = false
                            onEditCover()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(MR.strings.desktop_ui_delete_cover.localized()) },
                        onClick = {
                            showCoverMenu = false
                            onDeleteCover()
                        },
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            coverFeedback?.let {
                Text(
                    it,
                    color = if (it == MR.strings.cover_updated.localized()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleLarge,
            )
            authorNavigationNameOrNull(manga.author)?.let { author ->
                Text(
                    text = MR.strings.desktop_ui_author_value.localized(Locale.getDefault(), author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { onAuthorClick(author) },
                )
            }
            authorNavigationNameOrNull(manga.artist)?.takeIf { it != authorNavigationNameOrNull(manga.author) }?.let { artist ->
                Text(
                    text = MR.strings.desktop_ui_artist_value.localized(Locale.getDefault(), artist),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { onArtistClick(artist) },
                )
            }
            Text(
                text = listOfNotNull(
                    mangaStatusLabel(manga.status),
                    sourceName?.let { MR.strings.desktop_ui_source_value.localized(Locale.getDefault(), it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            manga.description?.let { desc ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val tags = manga.genre.orEmpty().filter { it.isNotBlank() }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { onTagSearch(tag) },
                            label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(onClick = { onTagCopy(tag) }) {
                                    Icon(Icons.Default.Link, contentDescription = MR.strings.desktop_ui_copy_tag.localized())
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MangaCategoryDialog(
    mangaId: Long,
    title: String = MR.strings.action_edit_categories.localized(),
    loadCategories: suspend () -> List<Category>,
    loadCategoryIds: suspend (Long) -> Set<Long>,
    onConfirm: suspend (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var checkedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mangaId) {
        categories = loadCategories()
        checkedIds = loadCategoryIds(mangaId)
        loaded = true
    }

    if (!loaded) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (categories.isEmpty()) {
                Text(MR.strings.desktop_ui_no_categories_create_categories_from_library_first.localized())
            } else {
                Column {
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checkedIds = if (category.id in checkedIds) checkedIds - category.id else checkedIds + category.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = category.id in checkedIds,
                                onCheckedChange = { checked ->
                                    checkedIds = if (checked) checkedIds + category.id else checkedIds - category.id
                                },
                            )
                            Text(category.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        onConfirm(checkedIds.toList())
                        onDismiss()
                    }
                },
            ) {
                Text(MR.strings.action_ok.localized())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}

internal enum class MangaCategoryDialogMode {
    ADD_TO_LIBRARY,
    EDIT_CATEGORIES,
}

@Composable
internal fun MangaDetailLibraryCategoryDialog(
    manga: Manga,
    mode: MangaCategoryDialogMode,
    model: MangaDetailScreenModel,
    onDismiss: () -> Unit,
) {
    MangaCategoryDialog(
        mangaId = manga.id,
        title = when (mode) {
            MangaCategoryDialogMode.ADD_TO_LIBRARY -> MR.strings.add_to_library.localized()
            MangaCategoryDialogMode.EDIT_CATEGORIES -> MR.strings.action_edit_categories.localized()
        },
        loadCategories = model::categories,
        loadCategoryIds = model::categoryIdsForManga,
        onConfirm = { categoryIds ->
            when (mode) {
                MangaCategoryDialogMode.ADD_TO_LIBRARY -> model.toggleLibrary(
                    manga = manga,
                    categoryIds = categoryIds,
                )
                MangaCategoryDialogMode.EDIT_CATEGORIES -> model.setCategoriesForManga(
                    mangaId = manga.id,
                    categoryIds = categoryIds,
                )
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun FetchIntervalDialog(
    manga: Manga,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedInterval by remember(manga.fetchInterval) {
        mutableStateOf(manga.fetchInterval.coerceAtMost(0).let { -it })
    }
    val options = listOf(0, 1, 2, 7, 14, 30)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.desktop_ui_update_interval.localized()) },
        text = {
            Column {
                options.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedInterval = interval }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedInterval == interval,
                            onCheckedChange = { selectedInterval = interval },
                        )
                        Text(
                            text = if (interval == 0) {
                                MR.strings.label_default.localized()
                            } else {
                                MR.strings.desktop_ui_days.localized(Locale.getDefault(), interval)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedInterval) }) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}

internal fun mangaStatusLabel(status: Long): String? =
    when (status) {
        SManga.ONGOING.toLong() -> MR.strings.ongoing.localized()
        SManga.COMPLETED.toLong() -> MR.strings.completed.localized()
        SManga.LICENSED.toLong() -> MR.strings.licensed.localized()
        SManga.PUBLISHING_FINISHED.toLong() -> MR.strings.publishing_finished.localized()
        SManga.CANCELLED.toLong() -> MR.strings.cancelled.localized()
        else -> null
    }

@Composable
internal fun MangaDetailActionRow(
    manga: Manga,
    mangaUrl: String?,
    hasUnreadChapters: Boolean,
    onToggleLibrary: () -> Unit,
    onEditCategories: () -> Unit,
    onEditFetchInterval: () -> Unit,
    onTracking: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    val linkActions = mangaUrl?.let { mangaLinkActions(it) }
    val actions = mangaDetailPrimaryActionTypes(
        isFavorite = manga.favorite,
        isHttpSource = linkActions != null,
        hasUnreadChapters = hasUnreadChapters,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            when (action) {
                MangaDetailPrimaryActionType.TOGGLE_LIBRARY ->
                    TextButton(onClick = onToggleLibrary) {
                        Icon(
                            if (manga.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (manga.favorite) MR.strings.in_library.localized() else MR.strings.add_to_library.localized())
                    }
                MangaDetailPrimaryActionType.EDIT_CATEGORIES ->
                    IconButton(onClick = onEditCategories) {
                        Icon(Icons.Default.Category, contentDescription = MR.strings.action_edit_categories.localized())
                    }
                MangaDetailPrimaryActionType.EDIT_FETCH_INTERVAL ->
                    IconButton(onClick = onEditFetchInterval) {
                        Icon(Icons.Default.HourglassEmpty, contentDescription = MR.strings.desktop_ui_edit_update_interval.localized())
                    }
                MangaDetailPrimaryActionType.TRACKING ->
                    IconButton(onClick = onTracking) {
                        Icon(Icons.Default.Sync, contentDescription = MR.strings.pref_category_tracking.localized())
                    }
                MangaDetailPrimaryActionType.OPEN_IN_BROWSER ->
                    IconButton(onClick = onOpenInBrowser) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = MR.strings.action_open_in_browser.localized())
                    }
                MangaDetailPrimaryActionType.COPY_LINK ->
                    IconButton(onClick = linkActions!!.copyLink) {
                        Icon(Icons.Default.Link, contentDescription = MR.strings.action_copy_link.localized())
                    }
                MangaDetailPrimaryActionType.SHARE ->
                    IconButton(onClick = linkActions!!.share) {
                        Icon(Icons.Default.Share, contentDescription = MR.strings.desktop_ui_share_link.localized())
                    }
                MangaDetailPrimaryActionType.CONTINUE_READING -> Unit
            }
        }
    }
}

internal enum class ChapterDownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED }

@Composable
internal fun ChapterRow(
    chapter: Chapter,
    title: String,
    downloadStatus: ChapterDownloadStatus,
    downloadProgress: Float?,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRead: () -> Unit,
) {
    ListItem(
        modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
        leadingContent = {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() },
            )
        },
        headlineContent = {
            Text(
                text = title,
                color = if (chapter.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Bookmark toggle
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        if (chapter.bookmark) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (chapter.bookmark) {
                            MR.strings.action_remove_bookmark.localized()
                        } else {
                            MR.strings.action_bookmark.localized()
                        },
                        tint = if (chapter.bookmark) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                when (downloadStatus) {
                    ChapterDownloadStatus.DOWNLOADED ->
                        IconButton(onClick = onDeleteDownload) {
                            Icon(
                                Icons.Default.DownloadDone,
                                contentDescription = MR.strings.desktop_ui_delete_download.localized(),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    ChapterDownloadStatus.QUEUED, ChapterDownloadStatus.DOWNLOADING ->
                        ChapterDownloadingIndicator(
                            downloadProgress = downloadProgress,
                            onCancel = onCancelDownload,
                        )
                    ChapterDownloadStatus.NOT_DOWNLOADED ->
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.CloudDownload, contentDescription = MR.strings.action_download.localized())
                        }
                }
                if (!isSelected) {
                    TextButton(onClick = onRead) {
                        if (chapter.read) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = MR.strings.label_read_chapters.localized(),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(MR.strings.label_read_chapters.localized())
                        }
                    }
                }
            }
        },
        supportingContent = chapter.scanlator?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * Returns the download progress as a fraction [0.0, 1.0], or null for indeterminate state.
 *
 * Returns null when:
 * - totalPages is 0 (page list not yet resolved)
 * - progress is 0 (download just started, no pages fetched yet)
 */
internal fun downloadProgressFraction(progress: Int, totalPages: Int): Float? {
    if (totalPages == 0 || progress == 0) return null
    return (progress.toFloat() / totalPages).coerceIn(0f, 1f)
}

internal val MangaDetailDownloadAction.label: String
    get() = when (this) {
        MangaDetailDownloadAction.NEXT_1_CHAPTER -> MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 1)
        MangaDetailDownloadAction.NEXT_5_CHAPTERS -> MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 5)
        MangaDetailDownloadAction.NEXT_10_CHAPTERS -> MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 10)
        MangaDetailDownloadAction.NEXT_25_CHAPTERS -> MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 25)
        MangaDetailDownloadAction.UNREAD_CHAPTERS -> MR.strings.desktop_ui_all_unread_chapters.localized()
        MangaDetailDownloadAction.BOOKMARKED_CHAPTERS -> MR.strings.desktop_ui_bookmarked_chapters.localized()
    }

internal data class MangaLinkActions(
    val copyLink: () -> Unit,
    val share: () -> Unit,
)

@Composable
internal fun mangaLinkActions(url: String): MangaLinkActions {
    val dependencies = LocalDesktopUiDependencies.current
    val shareService: DesktopShareService = dependencies.shareService
    val notificationService: DesktopNotificationService = dependencies.notificationService
    val scope = rememberCoroutineScope()
    return MangaLinkActions(
        copyLink = {
            notificationService.post(shareService.copyText(url).toDesktopNotification())
        },
        share = {
            scope.launch(Dispatchers.IO) {
                val launch = shareService.share(SharePayload.Text(url)) { terminal ->
                    notificationService.post(terminal.toDesktopNotification())
                }
                if (launch != DesktopShareResult.OpenedNatively) {
                    notificationService.post(launch.toDesktopNotification())
                }
            }
        },
    )
}

internal fun openExternalLink(url: String) {
    DesktopUrlOpener.open(url)
}

/**
 * Matches Android's ChapterDownloadIndicator for QUEUE and DOWNLOADING states:
 * - null progress → indeterminate circular indicator
 * - non-null → determinate circular indicator with animation
 * - ArrowDownward icon in center
 * - Click shows context menu with Cancel option
 */
@Composable
internal fun ChapterDownloadingIndicator(
    downloadProgress: Float?,
    onCancel: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val indicatorSize = 36.dp
    val strokeWidth = 3.dp
    val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clickable { showMenu = true }
            .layoutSize(indicatorSize),
        contentAlignment = Alignment.Center,
    ) {
        if (downloadProgress == null) {
            CircularProgressIndicator(
                modifier = Modifier.matchParentSize(),
                color = strokeColor,
                strokeWidth = strokeWidth,
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "download_progress",
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.matchParentSize(),
                color = strokeColor,
                strokeWidth = strokeWidth,
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = MR.strings.ext_downloading.localized(),
            modifier = Modifier.layoutSize(16.dp),
            tint = strokeColor,
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(MR.strings.action_cancel.localized()) },
                onClick = {
                    onCancel()
                    showMenu = false
                },
            )
        }
    }
}

/**
 * Batch action bar that appears at the bottom when chapters are selected.
 * Mirrors the SelectionActionBar pattern from LibraryTab.
 */
@Composable
internal fun ChapterSelectionBar(
    selectedCount: Int,
    downloadAction: ChapterSelectionDownloadAction,
    onBookmark: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMarkBelowRead: () -> Unit,
    onDownloadOrDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val actionTypes = chapterSelectionActionTypes(downloadAction)
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = MR.strings.desktop_ui_clear_selection.localized())
        }
        Text(
            text = MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), selectedCount),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        actionTypes.forEach { action ->
            when (action) {
                ChapterSelectionActionType.BOOKMARK ->
                    IconButton(onClick = onBookmark) {
                        Icon(Icons.Default.Bookmark, contentDescription = MR.strings.desktop_ui_bookmark_selected.localized())
                    }
                ChapterSelectionActionType.MARK_READ ->
                    IconButton(onClick = onMarkRead) {
                        Icon(Icons.Default.DoneAll, contentDescription = MR.strings.desktop_ui_mark_selected_as_read.localized())
                    }
                ChapterSelectionActionType.MARK_UNREAD ->
                    IconButton(onClick = onMarkUnread) {
                        Icon(Icons.Default.RadioButtonUnchecked, contentDescription = MR.strings.desktop_ui_mark_selected_as_unread.localized())
                    }
                ChapterSelectionActionType.MARK_BELOW_READ ->
                    IconButton(onClick = onMarkBelowRead) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = MR.strings.desktop_ui_mark_below_as_read.localized())
                    }
                ChapterSelectionActionType.DOWNLOAD ->
                    IconButton(onClick = onDownloadOrDelete) {
                        Icon(Icons.Default.CloudDownload, contentDescription = MR.strings.desktop_ui_download_selected.localized())
                    }
                ChapterSelectionActionType.DELETE_DOWNLOAD ->
                    IconButton(onClick = onDownloadOrDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = MR.strings.delete_downloaded.localized(),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
            }
        }
    }
}
