package mihon.desktop.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.desktop.history.HistoryScreenModelFactory
import mihon.desktop.history.toReaderScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.source.desktopSourceErrorMessage
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object HistoryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val localeTag = mihon.desktop.platform.LocalDesktopLocaleTag.current
            val icon = rememberVectorPainter(Icons.Default.History)
            return remember(localeTag) {
                TabOptions(
                    index = 2u,
                    title = MR.strings.history.localized(),
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        LocalHistoryNavigationHost.current.Content(HistoryRootScreen())
    }
}

class HistoryRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val model = rememberScreenModel { HistoryScreenModelFactory.create() }
        ContentWithModel(model)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    internal fun ContentWithModel(model: mihon.desktop.history.HistoryScreenModel) {
        val navigator = LocalNavigator.currentOrThrow
        val shared by model.controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        val dismiss = { model.controller.setDialog(null) }
        when (val dialog = shared.dialog) {
            is tachiyomi.domain.history.service.HistoryDialog.Delete -> mihon.presentation.history.HistoryDeleteDialog(
                onDismissRequest = dismiss,
                onDelete = { all ->
                    model.controller.invalidateReaderRequests()
                    scope.launch { model.controller.remove(dialog.history, all) }
                },
            )
            tachiyomi.domain.history.service.HistoryDialog.DeleteAll ->
                mihon.presentation.history
                    .HistoryDeleteAllDialog(
                        onDismissRequest = dismiss,
                        onDelete = {
                            model.controller.invalidateReaderRequests()
                            scope.launch { model.controller.clear() }
                        },
                    )
            is tachiyomi.domain.history.service.HistoryDialog.ChangeCategory -> {
                mihon.presentation.history.HistoryCategoryDialog(
                    dialog,
                    model.controller::selectCategory,
                    dismiss,
                    onConfirm = { scope.launch { model.controller.confirmCategory() } },
                    onEdit = {
                        model.controller.cancelReaderRequests()
                        navigator.push(mihon.desktop.ui.library.CategoryManagementScreen())
                    },
                )
            }
            is tachiyomi.domain.history.service.HistoryDialog.Duplicate ->
                mihon.presentation.history
                    .HistoryDuplicateDialog(
                        dialog,
                        dismiss,
                        onConfirm = {
                            scope.launch {
                                model.controller.addFavorite(dialog.manga.id, allowDuplicate = true)
                            }
                        },
                        onOpen = {
                            model.controller.cancelReaderRequests()
                            navigator.push(mihon.desktop.ui.library.MangaDetailScreen(it.id))
                        },
                        onMigrate = { model.controller.showMigration(it, dialog.manga) },
                    )
            is tachiyomi.domain.history.service.HistoryDialog.Migrate -> HistoryMigrationDialog(
                dialog,
                model,
                dismiss,
            ) {
                model.controller.cancelReaderRequests()
                navigator.push(mihon.desktop.ui.library.MangaDetailScreen(dialog.current.id))
            }
            null -> Unit
        }
        LaunchedEffect(Unit) {
            if (model.controller.state.value.dialog is tachiyomi.domain.history.service.HistoryDialog.ChangeCategory) {
                model.controller.refreshCategoryChoices()
            }
        }
        val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
        val openReader: (HistoryWithRelations?) -> Unit = { item ->
            val token = model.controller.beginReaderRequest()
            if (token != null) {
                scope.launch {
                    val delivery = model.readerDeliveryFor(item, token)
                    if (delivery != null && model.controller.consumeReaderRequest(token)) {
                        val request = delivery.request
                        if (request != null) {
                            navigator.push(request.toReaderScreen(model::cancelRead))
                        } else {
                            snackbar.showSnackbar(
                                (
                                    if (delivery.internalError) {
                                        MR.strings
                                            .internal_error
                                    } else {
                                        MR.strings.no_next_chapter
                                    }
                                    ).localized(),
                            )
                        }
                    }
                }
            }
        }
        val navigationHost = LocalHistoryNavigationHost.current
        DisposableEffect(model, navigationHost, navigator) {
            model.controller.activateReaderRequests()
            val unregister = navigationHost.registerReselectHandler { openReader(null) }
            onDispose {
                unregister()
                model.controller.cancelReaderRequests()
            }
        }
        LaunchedEffect(model) {
            model.controller.events.collect { event ->
                when (event) {
                    tachiyomi.domain.history.service.HistoryEvent.HistoryCleared -> snackbar.showSnackbar(
                        MR
                            .strings.clear_history_completed.localized(),
                    )
                    tachiyomi.domain.history.service.HistoryEvent.InternalError -> snackbar.showSnackbar(
                        MR
                            .strings.internal_error.localized(),
                    )
                    is tachiyomi.domain.history.service.HistoryEvent.OpenChapter -> Unit
                }
            }
        }
        mihon.presentation.history.HistoryContent(
            state = shared,
            datePreferences = HistoryScreenModelFactory.datePreferences(),
            onSearchQueryChange = model::updateSearchQuery,
            onCover = { item ->
                model.controller.cancelReaderRequests()
                navigator.push(mihon.desktop.ui.library.MangaDetailScreen(item.mangaId))
            },
            onResume = openReader,
            onFavorite = { item -> scope.launch { model.controller.addFavorite(item.mangaId) } },
            onDelete = { item ->
                model.controller.setDialog(
                    tachiyomi.domain.history.service.HistoryDialog
                        .Delete(item),
                )
            },
            onClear = { model.controller.setDialog(tachiyomi.domain.history.service.HistoryDialog.DeleteAll) },
            snackbar = { androidx.compose.material3.SnackbarHost(snackbar) },
            cover = { item, modifier, click ->
                AsyncImage(
                    model = mihon.desktop.image.desktopSourceImageModel(item.coverData.url, item.coverData.sourceId),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.clickable(onClick = click),
                )
            },
        )
    }
}

internal val LocalHistoryNavigationHost = androidx.compose.runtime.staticCompositionLocalOf<
    mihon.desktop.ui
        .library.LibraryNavigationHost,
    > {
    mihon.desktop.ui.library.VoyagerLibraryNavigationHost()
}
