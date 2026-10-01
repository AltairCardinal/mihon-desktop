package mihon.desktop.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
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
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveDone
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.platform.toDesktopNotification
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.authors.AuthorDetailScreen
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.domain.platform.SharePayload
import mihon.domain.task.TaskState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.ChapterItemClickAction
import tachiyomi.domain.chapter.chapterItemClickAction
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.util.Locale
import androidx.compose.foundation.layout.size as layoutSize

@Composable
internal fun MangaHeader(
    manga: Manga,
    expanded: Boolean = false,
    coverModel: String?,
    coverLastModified: Long,
    coverFeedback: String?,
    coverFailed: Boolean,
    sourceName: String?,
    sourceLanguage: String?,
    onSourceSearch: () -> Unit,
    onTagSearch: (String) -> Unit,
    onTagGlobalSearch: (String) -> Unit,
    onTagCopy: (String) -> Unit,
    creatorMentions: List<CreatorMention>,
    creatorIdentityLoading: Boolean,
    onCreatorClick: (CreatorMention) -> Unit,
    onCreatorSearch: (CreatorMention) -> Unit,
    onTitleSearch: () -> Unit,
    onNotes: () -> Unit,
    onViewCover: () -> Unit,
    coverFocus: FocusRequester,
) {
    val coverRequestState = rememberMangaCoverRequestState(manga.id, manga.source, coverModel, coverLastModified)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        val cover: @Composable (Modifier) -> Unit = { coverModifier ->
            Box(coverModifier) {
                key(coverRequestState.stateKey) {
                    AsyncImage(
                        model = coverRequestState.request,
                        contentDescription = manga.title,
                        contentScale = ContentScale.Crop,
                        placeholder = null,
                        modifier = Modifier
                            .focusRequester(coverFocus)
                            .clickable(onClick = onViewCover)
                            .fillMaxWidth()
                            .aspectRatio(0.7f),
                    )
                }
            }
        }
        val information: @Composable (Modifier) -> Unit = { informationModifier ->
            Column(
                modifier = informationModifier,
                horizontalAlignment = if (expanded) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                coverFeedback?.let {
                    Text(
                        it,
                        color = if (!coverFailed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                DetailMetadataContext(
                    title = manga.title,
                    searchLabel = MR.strings.action_global_search.localized(),
                    onPrimary = onTitleSearch,
                    onSearch = onTitleSearch,
                    onCopy = { onTagCopy(manga.title) },
                ) {
                    Text(manga.title, style = MaterialTheme.typography.titleLarge)
                }
                if (creatorMentions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        creatorMentions.forEach { mention ->
                            DetailMetadataContext(
                                title = creatorRoleLabel(mention.role),
                                searchLabel = MR.strings.action_search.localized(),
                                enabled = !creatorIdentityLoading,
                                onPrimary = { onCreatorClick(mention) },
                                onSearch = { onCreatorSearch(mention) },
                                onCopy = { onTagCopy(mention.displayName) },
                            ) { Text(mention.displayName, style = MaterialTheme.typography.titleSmall) }
                        }
                        if (creatorIdentityLoading) {
                            CircularProgressIndicator(Modifier.layoutSize(24.dp))
                        }
                    }
                }
                Text(
                    text = mangaStatusLabel(manga.status) ?: MR.strings.unknown_status.localized(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = sourceName?.let { name ->
                        if (sourceLanguage.isNullOrBlank()) {
                            name
                        } else {
                            MR.strings.desktop_extension_source_language.localized(
                                Locale.getDefault(),
                                name,
                                sourceLanguage,
                            )
                        }
                    } ?: MR.strings.source_not_installed.localized(Locale.getDefault(), manga.source.toString()),
                    modifier = Modifier.clickable(onClick = onSourceSearch).padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (manga.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clickable(onClick = onNotes).padding(8.dp)) {
                        Text(MR.strings.action_notes.localized(), style = MaterialTheme.typography.labelMedium)
                        MangaNotesSummary(manga.notes)
                    }
                }
                manga.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(8.dp))
                    val preference = LocalDesktopUiDependencies.current.appPreferences.imagesInDescription
                    val loadImages by preference.changes().collectAsState(initial = preference.get())
                    var descriptionExpanded by remember(manga.id) { mutableStateOf(false) }
                    var descriptionHeight by remember(manga.id) { mutableStateOf(0) }
                    val collapsedHeight = with(LocalDensity.current) { 120.dp.roundToPx() }
                    Box(if (descriptionExpanded) Modifier else Modifier.heightIn(max = 120.dp).clipToBounds()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            DesktopMarkdownDescription(
                                desc,
                                manga.source,
                                loadImages,
                                Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged {
                                    descriptionHeight =
                                        it.height
                                },
                            )
                        }
                    }
                    if (descriptionHeight > collapsedHeight) {
                        TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                            val label = if (descriptionExpanded) {
                                MR.strings.manga_info_collapse.localized()
                            } else {
                                MR.strings.manga_info_expand.localized()
                            }
                            Text(label)
                        }
                    }
                }
                val tags = manga.genre.orEmpty().filter { it.isNotBlank() }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            DetailMetadataContext(
                                title = tag,
                                searchLabel = MR.strings.action_global_search.localized(),
                                onPrimary = { onTagSearch(tag) },
                                onSearch = { onTagGlobalSearch(tag) },
                                onCopy = { onTagCopy(tag) },
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        tag,
                                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (expanded) {
            cover(Modifier.fillMaxWidth(.65f).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            information(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cover(Modifier.width(100.dp).align(Alignment.Top))
                information(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DetailMetadataContext(
    title: String,
    searchLabel: String,
    enabled: Boolean = true,
    onPrimary: () -> Unit,
    onSearch: () -> Unit,
    onCopy: () -> Unit,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val trigger = remember { FocusRequester() }
    val close: () -> Unit = {
        expanded = false
        trigger.requestFocus()
        Unit
    }
    Box(
        Modifier.focusRequester(trigger)
            .onPointerEvent(PointerEventType.Press) {
                if (enabled && it.button == PointerButton.Secondary) {
                    expanded = true
                    it.changes.forEach { change -> change.consume() }
                }
            }
            .onPreviewKeyEvent {
                if (enabled && it.key == Key.F10 && it.isShiftPressed &&
                    it.type == KeyEventType.KeyDown
                ) {
                    expanded = true
                    true
                } else {
                    false
                }
            }
            .combinedClickable(enabled = enabled, onClick = onPrimary, onLongClick = { expanded = true }),
    ) {
        content()
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            Text(
                title,
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            DropdownMenuItem(text = { Text(searchLabel) }, onClick = {
                close()
                onSearch()
            })
            DropdownMenuItem(text = { Text(MR.strings.action_copy_to_clipboard.localized()) }, onClick = {
                close()
                onCopy()
            })
        }
    }
}

private fun creatorRoleLabel(role: CreatorRole): String = when (role) {
    CreatorRole.AUTHOR -> MR.strings.author.localized()
    CreatorRole.ARTIST -> MR.strings.artist.localized()
    CreatorRole.BOTH -> MR.strings.desktop_ui_creator_role_both.localized()
    CreatorRole.UNKNOWN -> MR.strings.unknown.localized()
}

@Composable
internal fun CreatorIdentityChooserDialog(
    request: CreatorMentionResolution.Ambiguous,
    onSelect: (Long) -> Unit,
    onCreateDistinct: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.desktop_ui_choose_author_identity.localized()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    MR.strings.desktop_ui_choose_author_identity_summary.localized(
                        Locale.getDefault(),
                        request.mention.displayName,
                    ),
                )
                request.options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option.displayName) },
                        supportingContent = {
                            val details = buildList {
                                if (option.aliases.isNotEmpty()) add(option.aliases.joinToString())
                                if (option.needsReview) add(MR.strings.desktop_ui_identity_needs_review.localized())
                            }
                            if (details.isNotEmpty()) Text(details.joinToString(" · "))
                        },
                        modifier = Modifier.clickable { onSelect(option.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateDistinct) {
                Text(MR.strings.desktop_ui_create_distinct_identity.localized())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}

@Composable
internal fun MangaCategoryDialog(
    mangaId: Long,
    title: String = MR.strings.action_edit_categories.localized(),
    loadCategories: suspend () -> List<Category>,
    loadCategoryIds: suspend (Long) -> Set<Long>,
    onConfirm: suspend (List<Long>) -> Boolean,
    onDismiss: () -> Unit,
    onEditCategories: (() -> Unit)? = null,
) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var checkedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mangaId) {
        categories = loadCategories().filterNot(Category::isSystemCategory).sortedBy { it.order }
        checkedIds = loadCategoryIds(mangaId).intersect(categories.map { it.id }.toSet())
        loaded = true
    }

    if (!loaded) return
    val dialogFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        dialogFocus.requestFocus()
    }

    AlertDialog(
        modifier = Modifier.categoryDialogEscape(!busy, onDismiss).focusRequester(dialogFocus).focusable(),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text(MR.strings.information_empty_category_dialog.localized())
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("manga-category-list")) {
                        items(categories, key = { it.id }) { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manga-category-${category.id}")
                                    .toggleable(
                                        value = category.id in checkedIds,
                                        enabled = !busy,
                                        role = androidx.compose.ui.semantics.Role.Checkbox,
                                    ) { checked ->
                                        checkedIds = if (checked) checkedIds + category.id else checkedIds - category.id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = category.id in checkedIds,
                                    onCheckedChange = null,
                                )
                                Text(category.name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                if (failed) Text(MR.strings.internal_error.localized(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        failed = !onConfirm(checkedIds.toList())
                        busy = false
                        if (!failed) onDismiss()
                    }
                },
            ) {
                Text(MR.strings.action_ok.localized())
            }
        },
        dismissButton = {
            Row {
                onEditCategories?.let { edit ->
                    TextButton(enabled = !busy, onClick = edit) { Text(MR.strings.action_edit.localized()) }
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
            }
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
    onEditCategories: (() -> Unit)? = null,
    onSaved: (() -> Unit)? = null,
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
            val saved = when (mode) {
                MangaCategoryDialogMode.ADD_TO_LIBRARY -> model.toggleLibrary(
                    manga = manga,
                    categoryIds = categoryIds,
                ) is tachiyomi.domain.manga.interactor.LibraryMembershipResult.Success
                MangaCategoryDialogMode.EDIT_CATEGORIES -> model.setCategoriesForManga(
                    mangaId = manga.id,
                    categoryIds = categoryIds,
                ) == tachiyomi.domain.category.interactor.SetMangaCategories.Result.Success
            }
            if (saved) onSaved?.invoke()
            saved
        },
        onDismiss = onDismiss,
        onEditCategories = onEditCategories,
    )
}

@Composable
internal fun FetchIntervalDialog(
    manga: Manga,
    onDismiss: () -> Unit,
    onConfirm: suspend (Int) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val close = { if (!busy) onDismiss() }
    var selectedInterval by remember(manga.id) {
        mutableStateOf(manga.fetchInterval.coerceAtMost(0).let { -it })
    }
    val options = listOf(0, 1, 2, 7, 14, 30)

    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                close()
                true
            } else {
                false
            }
        },
        onDismissRequest = close,
        title = { Text(MR.strings.desktop_ui_update_interval.localized()) },
        text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                options.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { selectedInterval = interval }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedInterval == interval,
                            enabled = !busy,
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
                if (error) {
                    Text(
                        MR.strings.desktop_detail_save_failed.localized(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        if (onConfirm(selectedInterval)) onDismiss() else error = true
                    } catch (canceled: kotlinx.coroutines.CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        busy = false
                    }
                }
            }) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(cancelFocus), enabled = !busy, onClick = close) {
                Text(MR.strings.action_cancel.localized())
            }
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
        SManga.ON_HIATUS.toLong() -> MR.strings.on_hiatus.localized()
        else -> MR.strings.unknown_status.localized()
    }

@Composable
internal fun MangaDetailActionRow(
    manga: Manga,
    mangaUrl: String?,
    hasUnreadChapters: Boolean,
    onToggleLibrary: () -> Unit,
    libraryFocus: FocusRequester? = null,
    intervalFocus: FocusRequester? = null,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            actions.forEach { action ->
                when (action) {
                    MangaDetailPrimaryActionType.TOGGLE_LIBRARY ->
                        TextButton(
                            modifier = libraryFocus?.let {
                                Modifier.focusRequester(it)
                            } ?: Modifier,
                            onClick = onToggleLibrary,
                        ) {
                            Icon(
                                if (manga.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            val label = if (manga.favorite) {
                                MR.strings.in_library.localized()
                            } else {
                                MR.strings.add_to_library.localized()
                            }
                            Text(label)
                        }
                    MangaDetailPrimaryActionType.EDIT_FETCH_INTERVAL ->
                        IconButton(
                            modifier = intervalFocus?.let {
                                Modifier.focusRequester(it)
                            } ?: Modifier,
                            onClick = onEditFetchInterval,
                        ) {
                            Icon(
                                Icons.Default.HourglassEmpty,
                                contentDescription = MR.strings.desktop_ui_edit_update_interval.localized(),
                            )
                        }
                    MangaDetailPrimaryActionType.TRACKING ->
                        IconButton(onClick = onTracking) {
                            Icon(Icons.Default.Sync, contentDescription = MR.strings.pref_category_tracking.localized())
                        }
                    MangaDetailPrimaryActionType.OPEN_IN_BROWSER ->
                        IconButton(onClick = onOpenInBrowser) {
                            Icon(
                                Icons.Default.OpenInBrowser,
                                contentDescription = MR.strings.action_open_in_browser.localized(),
                            )
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
        if (manga.favorite) {
            MangaUpdateSchedule(manga)
        }
    }
}

@Composable
private fun MangaUpdateSchedule(manga: Manga) {
    val appearance = LocalDesktopUiDependencies.current.appPreferences
    val datePattern by appearance.dateFormat.changes().collectAsState(initial = appearance.dateFormat.get())
    val dateClock = mihon.desktop.platform.LocalDesktopDateClock.current
    val prediction = manga.expectedNextUpdate?.takeIf { manga.nextUpdate > 0 }?.let { instant ->
        val day = instant.atZone(dateClock.zone).toLocalDate()
        val date = eu.kanade.domain.ui.model.UiDateFormat.formatter(datePattern).format(day)
        "${MR.strings.action_sort_next_updated.localized()}: $date"
    } ?: MR.strings.manga_interval_expected_update_null.localized()
    val days = kotlin.math.abs(manga.fetchInterval.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val amount = MR.plurals.day.localized(Locale.getDefault(), days, days)
    val interval = when {
        manga.fetchInterval < 0 -> "${MR.strings.manga_interval_custom_amount.localized()} $amount"
        manga.fetchInterval == 0 -> MR.strings.label_default.localized()
        else -> MR.strings.desktop_detail_check_interval_automatic.localized(Locale.getDefault(), amount)
    }
    Text(prediction, style = MaterialTheme.typography.bodySmall)
    Text(
        MR.strings.desktop_detail_check_interval.localized(Locale.getDefault(), interval),
        style = MaterialTheme.typography.bodySmall,
    )
}

internal enum class ChapterDownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, ERROR, DOWNLOADED }

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ChapterRow(
    chapter: Chapter,
    title: String,
    downloadStatus: ChapterDownloadStatus,
    downloadProgress: Float?,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = isSelected,
    onSelect: () -> Unit = {},
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRead: () -> Unit,
    downloadEnabled: Boolean = true,
    onPrimaryClick: ((LibraryClickModifiers) -> Unit)? = null,
    onToggleRead: (() -> Unit)? = null,
    onStartDownloadNow: (() -> Unit)? = null,
    onDeleteDownloadFocus: ((FocusRequester) -> Unit)? = null,
) {
    val readPresentation = chapterReadPresentation(chapter)
    val readProgress = readPresentation.pageNumber?.let {
        MR.strings.chapter_progress.localized(Locale.getDefault(), it)
    }
    val appearance = LocalDesktopUiDependencies.current.appPreferences
    val datePattern by appearance.dateFormat.changes().collectAsState(initial = appearance.dateFormat.get())
    val relativeTime by appearance.relativeTime.changes().collectAsState(initial = appearance.relativeTime.get())
    val dateClock = mihon.desktop.platform.LocalDesktopDateClock.current
    val uploadDate = chapter.dateUpload.takeIf { it > 0 }?.let {
        val day = java.time.Instant.ofEpochMilli(it).atZone(dateClock.zone).toLocalDate()
        when (
            val difference = eu.kanade.domain.ui.model.UiDateFormat.relativeDays(
                day,
                java.time.LocalDate.now(dateClock),
                relativeTime,
            )
        ) {
            null -> eu.kanade.domain.ui.model.UiDateFormat.formatter(datePattern).format(day)
            0 -> MR.strings.relative_time_today.localized()
            in -7..-1 -> MR.plurals.upcoming_relative_time.localized(Locale.getDefault(), -difference, -difference)
            else -> MR.plurals.relative_time.localized(Locale.getDefault(), difference, difference)
        }
    }
    val supportingText = listOfNotNull(
        uploadDate,
        readProgress,
        chapter.scanlator?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    var contextExpanded by remember(chapter.id) { mutableStateOf(false) }
    val rowFocus = remember(chapter.id) { FocusRequester() }
    val deleteFocus = remember(chapter.id) { FocusRequester() }
    val delete = {
        onDeleteDownloadFocus?.invoke(deleteFocus)
        onDeleteDownload()
    }
    val closeContext = {
        contextExpanded = false
        try {
            rowFocus.requestFocus()
        } catch (_: IllegalStateException) {
            false
        }
        Unit
    }
    Box(Modifier.fillMaxWidth()) {
        val selectedColor = MaterialTheme.colorScheme.secondary.copy(
            alpha = if (mihon.desktop.ui.theme.LocalDesktopDarkTheme.current) .16f else .22f,
        ).compositeOver(MaterialTheme.colorScheme.surface)
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = if (isSelected) selectedColor else MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(rowFocus)
                .onPointerEvent(PointerEventType.Press) {
                    if (!isSelectionMode && onToggleRead != null && it.button == PointerButton.Secondary) {
                        contextExpanded = true
                        it.changes.forEach { change -> change.consume() }
                    }
                }
                .onPreviewKeyEvent {
                    if (!isSelectionMode && onToggleRead != null && it.key == Key.F10 && it.isShiftPressed &&
                        it.type == KeyEventType.KeyDown
                    ) {
                        contextExpanded = true
                        true
                    } else {
                        false
                    }
                }
                .semantics {
                    if (!isSelectionMode) {
                        val label = if (chapter.bookmark) {
                            MR.strings.action_remove_bookmark.localized()
                        } else {
                            MR.strings.action_bookmark.localized()
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(label) {
                                onToggleBookmark()
                                true
                            },
                        )
                    }
                }
                .semantics { selected = isSelected }
                .shiftAwareCombinedClickable(
                    onClick = { modifiers ->
                        if (onPrimaryClick != null) {
                            onPrimaryClick(modifiers)
                        } else {
                            when (chapterItemClickAction(isSelected, isSelectionMode)) {
                                ChapterItemClickAction.READ -> onRead()
                                ChapterItemClickAction.SELECT,
                                ChapterItemClickAction.DESELECT,
                                -> onSelect()
                            }
                        }
                    },
                    onLongClick = onSelect,
                ),
            leadingContent = null,
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!chapter.read) {
                        Icon(
                            Icons.Default.Circle,
                            MR.strings.unread.localized(),
                            Modifier.height(8.dp).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = title,
                        color = if (chapter.read) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bookmark toggle
                    if (chapter.bookmark) {
                        IconButton(enabled = !isSelectionMode, onClick = onToggleBookmark) {
                            Icon(
                                Icons.Default.Bookmark,
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
                    }

                    when (downloadStatus) {
                        ChapterDownloadStatus.DOWNLOADED ->
                            IconButton(
                                modifier = Modifier.focusRequester(deleteFocus),
                                enabled =
                                downloadEnabled && !isSelectionMode,
                                onClick = delete,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = MR.strings.desktop_ui_delete_download.localized(),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        ChapterDownloadStatus.QUEUED, ChapterDownloadStatus.DOWNLOADING ->
                            ChapterDownloadingIndicator(
                                downloadProgress = downloadProgress,
                                onCancel = onCancelDownload,
                                enabled = downloadEnabled && !isSelectionMode,
                            )
                        ChapterDownloadStatus.ERROR ->
                            IconButton(enabled = downloadEnabled && !isSelectionMode, onClick = onRetryDownload) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = MR.strings.desktop_ui_download_retry_error.localized(),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        ChapterDownloadStatus.NOT_DOWNLOADED ->
                            IconButton(enabled = downloadEnabled && !isSelectionMode, onClick = onDownload) {
                                Icon(
                                    ChapterDownloadIcon,
                                    contentDescription = MR.strings.action_download.localized(),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
                                )
                            }
                    }
                }
            },
            supportingContent = supportingText.takeIf { it.isNotEmpty() }?.let { text ->
                {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (chapter.read) .38f else .78f),
                    )
                }
            },
        )
        DropdownMenu(expanded = contextExpanded && !isSelectionMode, onDismissRequest = closeContext) {
            DropdownMenuItem(text = {
                Text(
                    if (chapter.read) {
                        MR.strings.action_mark_as_unread.localized()
                    } else {
                        MR.strings.action_mark_as_read.localized()
                    },
                )
            }, onClick = {
                closeContext()
                onToggleRead?.invoke()
            })
            DropdownMenuItem(text = {
                Text(
                    if (chapter.bookmark) {
                        MR.strings.action_remove_bookmark.localized()
                    } else {
                        MR.strings.action_bookmark.localized()
                    },
                )
            }, onClick = {
                closeContext()
                onToggleBookmark()
            })
            if (downloadEnabled && downloadStatus == ChapterDownloadStatus.QUEUED && onStartDownloadNow != null) {
                DropdownMenuItem(text = { Text(MR.strings.action_start_downloading_now.localized()) }, onClick = {
                    closeContext()
                    onStartDownloadNow()
                })
            }
            if (downloadEnabled) {
                val contextDownloadLabel = when (downloadStatus) {
                    ChapterDownloadStatus.DOWNLOADED -> MR.strings.action_delete
                    ChapterDownloadStatus.QUEUED, ChapterDownloadStatus.DOWNLOADING -> MR.strings.action_cancel
                    else -> MR.strings.action_download
                }
                DropdownMenuItem(text = { Text(contextDownloadLabel.localized()) }, onClick = {
                    closeContext()
                    when (downloadStatus) {
                        ChapterDownloadStatus.DOWNLOADED -> delete()
                        ChapterDownloadStatus.QUEUED, ChapterDownloadStatus.DOWNLOADING -> onCancelDownload()
                        ChapterDownloadStatus.ERROR -> onRetryDownload()
                        ChapterDownloadStatus.NOT_DOWNLOADED -> onDownload()
                    }
                })
            }
        }
    }
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
        MangaDetailDownloadAction.NEXT_1_CHAPTER -> MR.strings.desktop_ui_next_chapters.localized(
            Locale.getDefault(),
            1,
        )
        MangaDetailDownloadAction.NEXT_5_CHAPTERS -> MR.strings.desktop_ui_next_chapters.localized(
            Locale.getDefault(),
            5,
        )
        MangaDetailDownloadAction.NEXT_10_CHAPTERS -> MR.strings.desktop_ui_next_chapters.localized(
            Locale.getDefault(),
            10,
        )
        MangaDetailDownloadAction.NEXT_25_CHAPTERS -> MR.strings.desktop_ui_next_chapters.localized(
            Locale.getDefault(),
            25,
        )
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
    enabled: Boolean = true,
) {
    var showMenu by remember { mutableStateOf(false) }
    val indicatorSize = 36.dp
    val strokeWidth = 3.dp
    val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clickable(enabled = enabled) { showMenu = true }
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
@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier,
    selectedChapters: List<Chapter> = emptyList(),
    showDownload: Boolean = downloadAction == ChapterSelectionDownloadAction.DOWNLOAD,
    showDelete: Boolean = downloadAction == ChapterSelectionDownloadAction.DELETE_DOWNLOAD,
    onDelete: () -> Unit = onDownloadOrDelete,
    deleteFocus: FocusRequester? = null,
) {
    val baseActions = chapterSelectionActionTypes(ChapterSelectionDownloadAction.DOWNLOAD)
        .filterNot { it == ChapterSelectionActionType.DOWNLOAD }
    val actionTypes = (
        baseActions + listOfNotNull(
            ChapterSelectionActionType.DOWNLOAD.takeIf { showDownload },
            ChapterSelectionActionType.DELETE_DOWNLOAD.takeIf { showDelete },
        )
        ).filter { action ->
        when (action) {
            ChapterSelectionActionType.MARK_READ -> selectedChapters.any { !it.read }
            ChapterSelectionActionType.MARK_UNREAD -> selectedChapters.any { it.read || it.lastPageRead > 0 }
            ChapterSelectionActionType.MARK_BELOW_READ -> selectedChapters.size == 1
            else -> true
        }
    }
    val addingBookmark = selectedChapters.any { !it.bookmark }
    Surface(
        modifier = modifier.testTag("chapter-selection-bottom-bar"),
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            val columns = (maxWidth / 48.dp).toInt().coerceAtLeast(1).coerceAtMost(actionTypes.size)
            val actionWidth = maxWidth / columns
            val scope = rememberCoroutineScope()
            FlowRow(maxItemsInEachRow = columns) {
                actionTypes.forEach { action ->
                    val (icon, label, callback) = when (action) {
                        ChapterSelectionActionType.BOOKMARK -> Triple(
                            if (addingBookmark) Icons.Outlined.BookmarkAdd else Icons.Outlined.BookmarkRemove,
                            if (addingBookmark) MR.strings.action_bookmark else MR.strings.action_remove_bookmark,
                            onBookmark,
                        )
                        ChapterSelectionActionType.MARK_READ -> Triple(
                            Icons.Outlined.DoneAll,
                            MR.strings.action_mark_as_read,
                            onMarkRead,
                        )
                        ChapterSelectionActionType.MARK_UNREAD -> Triple(
                            Icons.Outlined.RemoveDone,
                            MR.strings.action_mark_as_unread,
                            onMarkUnread,
                        )
                        ChapterSelectionActionType.MARK_BELOW_READ -> Triple(
                            ChapterDonePreviousIcon,
                            MR.strings.action_mark_previous_as_read,
                            onMarkBelowRead,
                        )
                        ChapterSelectionActionType.DOWNLOAD -> Triple(
                            Icons.Outlined.Download,
                            MR.strings.action_download,
                            onDownloadOrDelete,
                        )
                        ChapterSelectionActionType.DELETE_DOWNLOAD -> Triple(
                            Icons.Outlined.Delete,
                            MR.strings.action_delete,
                            onDelete,
                        )
                    }
                    val tooltipState = rememberTooltipState()
                    TooltipBox(
                        modifier = Modifier.width(actionWidth),
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(label.localized()) } },
                        state = tooltipState,
                    ) {
                        val focusModifier = if (action == ChapterSelectionActionType.DELETE_DOWNLOAD &&
                            deleteFocus != null
                        ) {
                            Modifier.focusRequester(deleteFocus)
                        } else {
                            Modifier
                        }
                        Box(
                            modifier = focusModifier.fillMaxWidth().height(48.dp).combinedClickable(
                                role = Role.Button,
                                onClick = callback,
                                onLongClick = { scope.launch { tooltipState.show() } },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, label.localized()) }
                    }
                }
            }
        }
    }
}
