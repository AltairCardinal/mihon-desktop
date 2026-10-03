package mihon.desktop.ui.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.AcceptedMigration
import mihon.desktop.domain.MigrationCommittedCleanupException
import mihon.desktop.domain.MigrationOptions
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.desktop.migration.BatchMigrationOptions
import mihon.desktop.migration.BatchMigrationTargetSelection
import mihon.desktop.ui.browse.SourceBrowseQueryCoordinator
import mihon.desktop.ui.browse.SourceBrowseStateProjector
import mihon.desktop.ui.source.desktopSourceErrorMessage
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.i18n.MR

/** One source/query/page session. Confirmation retains its original files across retries. */
data class MigrationSearchScreen(
    val sourceMangaId: Long,
    val sourceMangaTitle: String,
    val batchQueueId: String? = null,
) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val sources =
            remember(dependencies.sourceManager) {
                dependencies.sourceManager.getCatalogueSources().sortedBy { it.name }
            }
        var selectedId by remember { mutableStateOf(sources.firstOrNull()?.id) }
        val source = sources.firstOrNull { it.id == selectedId }
        var query by remember { mutableStateOf(sourceMangaTitle) }
        var submitted by remember { mutableStateOf(query) }
        var page by remember { mutableIntStateOf(1) }
        var retry by remember { mutableIntStateOf(0) }
        val listingQuery = remember(selectedId, submitted) {
            SourceQuery.Search(
                submitted,
                source?.getFilterList() ?: FilterList(),
            )
        }
        val scope = rememberCoroutineScope()
        val coordinator =
            remember(dependencies.sourceMangaSearchService) {
                SourceBrowseQueryCoordinator(dependencies.sourceMangaSearchService)
            }
        val state by coordinator.states.collectAsState(initial = null)
        val projected = SourceBrowseStateProjector.project(state)
        val matching =
            state?.request?.let { it.sourceId == selectedId && it.query == listingQuery && it.page == page } == true
        val results = if (matching) projected.items else emptyList()
        val hasNext = matching && projected.hasNextPage
        val loading = projected.loading || (!matching && source != null)
        val error = if (source == null) {
            MR.strings.source_not_installed.localized()
        } else {
            if (matching) projected.pageError?.error?.let { desktopSourceErrorMessage(it) } else null
        }
        var sourceMenu by remember { mutableStateOf(false) }
        var target by remember { mutableStateOf<Pair<CatalogueSource, SManga>?>(null) }
        var returnFocus by remember { mutableIntStateOf(0) }
        var lastTargetUrl by remember { mutableStateOf<String?>(null) }
        val triggerFocus = remember { FocusRequester() }
        LaunchedEffect(returnFocus) {
            if (returnFocus > 0) {
                withFrameNanos {}
                triggerFocus.requestFocus()
            }
        }
        LaunchedEffect(selectedId, submitted, page, retry) {
            source?.let { coordinator.load(it, page, listingQuery) }
        }
        target?.let { (targetSource, targetManga) ->
            MigrationConfirmation(
                sourceMangaId,
                sourceMangaTitle,
                targetSource,
                targetManga,
                batchQueueId,
                onDismiss = {
                    target = null
                    returnFocus++
                },
                onCompleted = { navigator.pop() },
            )
        }
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Text(
                        MR.strings.desktop_ui_find_replacement.localized(
                            java.util.Locale.getDefault(),
                            sourceMangaTitle,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        navigator.pop()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.desktop_ui_back.localized()) }
                },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(MR.strings.label_sources.localized())
                    Box {
                        TextButton(onClick = {
                            sourceMenu = true
                        }) { Text(source?.name ?: MR.strings.source_not_installed.localized()) }
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            sources.forEach { candidate ->
                                DropdownMenuItem(text = { Text(candidate.name) }, onClick = {
                                    selectedId = candidate.id
                                    page = 1
                                    sourceMenu = false
                                })
                            }
                        }
                    }
                }
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        submitted = it
                        page = 1
                        retry++
                    },
                    active = false,
                    onActiveChange = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text(MR.strings.desktop_ui_search_manga_title.localized()) },
                ) {}
                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Column(
                        Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
                        Button(onClick = {
                            source?.let { currentSource ->
                                state?.request?.let { request ->
                                    scope.launch { coordinator.retry(currentSource, request) }
                                }
                            }
                        }) { Text(MR.strings.action_retry.localized()) }
                    }
                    results.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(MR.strings.desktop_ui_no_results.localized())
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(results, key = { it.url }) { manga ->
                            ListItem(
                                leadingContent = {
                                    AsyncImage(
                                        mihon.desktop.image.desktopSourceImageModel(
                                            manga.thumbnail_url,
                                            requireNotNull(source).id,
                                        ),
                                        null,
                                        modifier = Modifier.size(40.dp, 56.dp),
                                        contentScale = ContentScale.Crop,
                                    )
                                },
                                headlineContent = {
                                    Text(manga.title)
                                },
                                supportingContent = { Text(requireNotNull(source).name) },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            val current = coordinator.state?.request
                                            if (current != null && current.sourceId == selectedId &&
                                                source?.id == selectedId &&
                                                current.query == listingQuery && current.page == page
                                            ) {
                                                lastTargetUrl = manga.url
                                                target = requireNotNull(source) to manga
                                            }
                                        },
                                        modifier = if (manga.url ==
                                            lastTargetUrl
                                        ) {
                                            Modifier.focusRequester(triggerFocus)
                                        } else {
                                            Modifier
                                        },
                                    ) { Text(MR.strings.desktop_ui_select.localized()) }
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state?.request?.page ?: 1}", Modifier.padding(16.dp))
                    TextButton(enabled = !loading && error == null && hasNext, onClick = {
                        page =
                            requireNotNull(state).request.page + 1
                    }) { Text(MR.strings.onboarding_action_next.localized()) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MigrationConfirmation(
    sourceId: Long,
    sourceTitle: String,
    targetSource: CatalogueSource,
    target: SManga,
    batchQueueId: String?,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    val dependencies = LocalDesktopUiDependencies.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Manga?>(null) }
    val initialOptions = remember(batchQueueId, sourceId) {
        batchQueueId?.let { id ->
            dependencies.batchMigrationController.queue(id)?.let { queue ->
                queue.items.firstOrNull { it.mangaId == sourceId }?.options ?: queue.defaultOptions
            }
        } ?: BatchMigrationOptions()
    }
    var copyChapters by remember { mutableStateOf(initialOptions.copyChapters) }
    var copyCategories by remember { mutableStateOf(initialOptions.copyCategories) }
    var copyNotes by remember { mutableStateOf(initialOptions.copyNotes) }
    var copyCover by remember { mutableStateOf(initialOptions.copyCustomCover) }
    var removeDownloads by remember { mutableStateOf(initialOptions.removeDownloads) }
    var hasCover by remember { mutableStateOf(false) }
    var hasDownloads by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var committedPending by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(initialOptions.accepted) }
    LaunchedEffect(sourceId) {
        try {
            val loadedSource = dependencies.getManga.await(sourceId) ?: error("Source manga no longer exists")
            hasCover = dependencies.customCoverStore.getCustomCoverFile(sourceId).isFile
            hasDownloads = withContext(Dispatchers.IO) { dependencies.migrateManga.hasDownloads(loadedSource) }
            source = loadedSource
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error =
                MR.strings.internal_error.localized()
        }
    }
    fun dismiss() {
        if (busy) return
        if (committedPending) {
            onDismiss()
            return
        }
        scope.launch {
            try {
                accepted?.let {
                    dependencies.migrateManga.cancelAccepted(it)
                    batchQueueId?.let { id ->
                        dependencies.batchMigrationController.clearAccepted(id, sourceId, it.operationId)
                    }
                }
                onDismiss()
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (e: Exception) {
                error = MR.strings.desktop_migration_cleanup_pending.localized()
            }
        }
    }
    fun execute(replace: Boolean) {
        if (busy || source == null) return
        busy = true
        error = null
        scope.launch {
            try {
                val options =
                    accepted?.options
                        ?: MigrationOptions(
                            copyChapters,
                            copyCategories,
                            copyNotes && source?.notes?.isNotBlank() == true,
                            copyCover && hasCover,
                            removeDownloads && hasDownloads,
                        )
                val confirmation =
                    accepted
                        ?: dependencies.migrateManga.accept(
                            requireNotNull(source),
                            options,
                            replace,
                            checkpointOwner = batchQueueId,
                        ).also {
                            accepted =
                                it
                        }
                check(confirmation.replace == replace)
                if (batchQueueId != null) {
                    dependencies.batchMigrationController.selectTarget(
                        batchQueueId,
                        sourceId,
                        target.toBatchTarget(targetSource.id),
                        BatchMigrationOptions(
                            options.copyChapters,
                            options.copyCategories,
                            options.copyNotes,
                            replace,
                            options.copyCustomCover,
                            options.removeDownloads,
                            confirmation,
                            batchQueueId,
                        ),
                    )
                } else {
                    if (dependencies.migrateManga.recoverAccepted(confirmation) == null) {
                        val chapters = when (val result = safeSourceCall { targetSource.getChapterList(target) }) {
                            is SourceCallResult.Success -> result.value
                            is SourceCallResult.Error -> {
                                error = desktopSourceErrorMessage(result.error)
                                return@launch
                            }
                            is SourceCallResult.Timeout -> {
                                error = desktopSourceErrorMessage(result.error)
                                return@launch
                            }
                        }
                        dependencies.migrateManga.await(
                            confirmation.source,
                            target,
                            targetSource.id,
                            chapters,
                            options,
                            replace,
                            confirmation,
                        )
                    }
                }
                onCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MigrationCommittedCleanupException) {
                committedPending = true
                error = MR.strings.desktop_migration_cleanup_pending.localized()
            } catch (e: mihon.desktop.domain.MigrationFileConfirmationException) {
                error =
                    MR.strings.desktop_migration_files_changed.localized()
            } catch (
                e: Exception,
            ) {
                error = desktopSourceErrorMessage(e.toSourceAppError())
            } finally {
                busy = false
            }
        }
    }
    val editable = !busy && accepted == null
    AlertDialog(
        onDismissRequest = { dismiss() },
        modifier = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                dismiss()
                true
            } else {
                false
            }
        },
        title = { Text(MR.strings.label_migration.localized()) },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text("$sourceTitle → ${target.title}")
                CheckRow(MR.strings.desktop_ui_copy_chapter_read_status.localized(), copyChapters, editable) {
                    copyChapters =
                        it
                }
                CheckRow(MR.strings.desktop_ui_copy_categories.localized(), copyCategories, editable) {
                    copyCategories = it
                }
                if (source?.notes?.isNotBlank() ==
                    true
                ) {
                    CheckRow(MR.strings.desktop_ui_copy_notes.localized(), copyNotes, editable) { copyNotes = it }
                }
                if (hasCover) CheckRow(MR.strings.custom_cover.localized(), copyCover, editable) { copyCover = it }
                if (hasDownloads) {
                    CheckRow(MR.strings.delete_downloaded.localized(), removeDownloads, editable) {
                        removeDownloads =
                            it
                    }
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            FlowRow {
                Button(enabled = !busy && source != null && accepted?.replace != false, onClick = {
                    execute(true)
                }) { Text(MR.strings.action_migrate.localized()) }
                OutlinedButton(enabled = !busy && source != null && accepted?.replace != true, onClick = {
                    execute(false)
                }) { Text(MR.strings.copy.localized()) }
                TextButton(enabled = !busy, onClick = { dismiss() }) {
                    val closeLabel = if (committedPending) {
                        MR.strings.action_close.localized()
                    } else {
                        MR.strings.action_cancel.localized()
                    }
                    Text(closeLabel)
                }
            }
        },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onToggle, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

private fun SManga.toBatchTarget(sourceId: Long) = BatchMigrationTargetSelection(
    sourceId, url, title, thumbnail_url, author, artist, description, getGenres(), status,
)
