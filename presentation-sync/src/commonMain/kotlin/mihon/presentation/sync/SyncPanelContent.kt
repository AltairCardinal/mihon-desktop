package mihon.presentation.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.inbox.SyncPendingItem
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncFailureLogStatus
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncPasswordProblem
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import tachiyomi.i18n.MR

@Composable
fun SyncToolbarButton(state: SyncPanelState, onOpen: () -> Unit) {
    val busyDescription = if (state.busy) syncString(MR.strings.sync_busy) else ""
    Box(Modifier.size(48.dp)) {
        IconButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxSize().testTag("sync-open").semantics {
                stateDescription = busyDescription
            },
        ) {
            val rotation = if (state.busy) {
                val transition = rememberInfiniteTransition(label = "sync-busy")
                val value by transition.animateFloat(
                    0f,
                    360f,
                    infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
                    label = "sync-rotation",
                )
                value
            } else {
                0f
            }
            Icon(
                Icons.Outlined.Sync,
                syncString(MR.strings.sync_title),
                Modifier.rotate(rotation).then(if (state.busy) Modifier.testTag("sync-busy") else Modifier),
            )
        }
        if (state.pendingTotal > 0) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp).testTag("sync-count"),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) { Text(if (state.pendingTotal > 99) "99+" else state.pendingTotal.toString()) }
        }
    }
}

@Composable
fun SyncPanelContent(
    panel: SyncPanel,
    modifier: Modifier = Modifier,
    onOpenBrowser: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenFailureLog: (String) -> Unit = {},
) {
    val state by panel.state.collectAsState()
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        PanelHeader(state, panel::dispatch)
        HorizontalDivider()
        when (state.page) {
            SyncPanelPage.MAIN -> MainPage(state, panel::dispatch, onOpenFailureLog, listState, Modifier.weight(1f))
            SyncPanelPage.SETTINGS -> SettingsPage(state, panel::dispatch, Modifier.weight(1f))
            SyncPanelPage.HISTORY -> RecordsPage(state, Modifier.weight(1f))
            SyncPanelPage.SETUP -> SetupPage(
                state,
                panel::dispatch,
                onOpenBrowser,
                onCopyCode,
                panel::claimDeviceCodeBrowser,
                onOpenFailureLog,
                Modifier.weight(1f),
            )
        }
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { panel.dispatch(SyncPanelAction.CancelDecision) },
            title = { Text(syncString(decisionLabel(confirmation.decision))) },
            text = {
                Text(syncString(MR.strings.sync_confirm_body, confirmation.manga, confirmation.authors))
            },
            confirmButton = {
                Action("sync-confirm-decision", MR.strings.sync_confirm) {
                    panel.dispatch(SyncPanelAction.ConfirmDecision)
                }
            },
            dismissButton = {
                Action("sync-cancel-decision", MR.strings.sync_cancel) {
                    panel.dispatch(SyncPanelAction.CancelDecision)
                }
            },
        )
    }
    state.question?.let { question ->
        AlertDialog(
            onDismissRequest = { panel.dispatch(SyncPanelAction.CancelQuestion) },
            title = {
                Text(
                    syncString(
                        if (question == SyncPanelQuestion.DISCONNECT) {
                            MR.strings.sync_disconnect
                        } else {
                            MR.strings.sync_switch
                        },
                    ),
                )
            },
            text = {
                Text(
                    syncString(
                        if (question == SyncPanelQuestion.DISCONNECT) {
                            MR.strings.sync_disconnect_body
                        } else {
                            MR.strings.sync_switch_body
                        },
                    ),
                )
            },
            confirmButton = {
                Action("sync-confirm-question", MR.strings.sync_confirm) {
                    panel.dispatch(SyncPanelAction.ConfirmQuestion)
                }
            },
            dismissButton = {
                Action("sync-cancel-question", MR.strings.sync_cancel) {
                    panel.dispatch(SyncPanelAction.CancelQuestion)
                }
            },
        )
    }
}

@Composable
private fun PanelHeader(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.page != SyncPanelPage.MAIN) {
            IconButton({ dispatch(SyncPanelAction.Back) }, Modifier.testTag("sync-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, syncString(MR.strings.sync_back))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                syncString(
                    when (state.page) {
                        SyncPanelPage.SETTINGS -> MR.strings.sync_settings
                        SyncPanelPage.HISTORY -> MR.strings.sync_records
                        else -> MR.strings.sync_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.page == SyncPanelPage.MAIN && state.deviceName.isNotBlank()) {
                Text(
                    syncString(MR.strings.sync_space_subtitle, state.deviceName),
                    Modifier.testTag("sync-space-subtitle"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.page == SyncPanelPage.MAIN) {
            IconButton(
                { dispatch(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS)) },
                Modifier.testTag("sync-settings"),
            ) {
                Icon(Icons.Outlined.Settings, syncString(MR.strings.sync_settings))
            }
        }
        IconButton({ dispatch(SyncPanelAction.Close) }, Modifier.testTag("sync-close")) {
            Icon(Icons.Outlined.Close, syncString(MR.strings.sync_close))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainPage(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    onOpenFailureLog: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier,
) {
    val continuingSetup = state.setupStep !in setOf(SyncSetupStep.SIGN_IN, SyncSetupStep.COMPLETE)
    Column(modifier) {
        SyncStatusHeader(state, continuingSetup, dispatch)
        SyncQueueSummary(state)
        state.run?.let { run -> SyncProgressCard(run, state, dispatch, onOpenFailureLog) }
        if (state.problem == SyncRunProblem.AUTHORIZATION) {
            Action("sync-reconnect", MR.strings.sync_reconnect) { dispatch(SyncPanelAction.Authorize) }
        }
        if (state.setupProblem != SyncDiscoveryProblem.INCOMPATIBLE &&
            (state.problem == SyncRunProblem.STORAGE || state.problem == SyncRunProblem.INVALID_DATA)
        ) {
            Action("sync-reenter-password", MR.strings.sync_password_connect) { dispatch(SyncPanelAction.BeginSetup) }
        }
        state.notice?.let { notice ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (notice.setupCompleted) {
                        Text(syncString(MR.strings.sync_setup_complete), Modifier.testTag("sync-setup-complete"))
                    }
                    notice.exchange?.let { result ->
                        if (result.status == SyncRunStatus.SUCCESS || result.status == SyncRunStatus.PARTIAL) {
                            Text(
                                syncString(
                                    MR.strings.sync_notice,
                                    result.uploaded,
                                    result.downloaded,
                                    result.pending,
                                ),
                                Modifier.testTag("sync-notice-counts"),
                            )
                        }
                        if (result.status == SyncRunStatus.PARTIAL && result.pending > 0 && result.problem == null) {
                            Text(
                                syncString(MR.strings.sync_pending_decisions_count, result.pending),
                                Modifier.testTag("sync-notice-pending"),
                            )
                        } else if (result.status == SyncRunStatus.PARTIAL && result.problem == null &&
                            state.run?.stopReason == "projection_pending"
                        ) {
                            Text(
                                syncString(MR.strings.sync_projection_pending),
                                Modifier.testTag("sync-notice-projection"),
                            )
                        } else if (result.status == SyncRunStatus.FAILED || result.status == SyncRunStatus.PARTIAL) {
                            Text(
                                problemText(result.problem ?: SyncRunProblem.UNKNOWN),
                                Modifier.testTag("sync-notice-error"),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    notice.bulk?.let {
                        Text(syncString(MR.strings.sync_bulk, it.completed, it.remaining, it.skipped, it.failed))
                    }
                }
                Action("sync-dismiss-notice", MR.strings.sync_dismiss) { dispatch(SyncPanelAction.DismissNotice) }
            }
        }
        if (state.importRemaining > 0) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(syncString(MR.strings.sync_import_remaining, state.importRemaining))
                if (state.importPaused) {
                    Action("sync-resume-import", MR.strings.sync_resume) { dispatch(SyncPanelAction.ResumeImport) }
                } else {
                    Action("sync-pause-import", MR.strings.sync_pause) { dispatch(SyncPanelAction.PauseImport) }
                }
            }
        }
        state.bulk?.takeIf { it.remaining > 0 }?.let { bulk ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(syncString(MR.strings.sync_bulk, bulk.completed, bulk.remaining, bulk.skipped, bulk.failed))
                if (bulk.running) {
                    Action("sync-pause-bulk", MR.strings.sync_pause) { dispatch(SyncPanelAction.PauseBulk) }
                } else {
                    Action("sync-resume-bulk", MR.strings.sync_resume) { dispatch(SyncPanelAction.ResumeBulk) }
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("sync-pending-list")) {
            if (state.pendingTotal > 0) {
                stickyHeader(key = "selection-toolbar") { SelectionBar(state, dispatch) }
            }
            if (state.pending.isEmpty()) {
                item("empty") {
                    Text(syncString(MR.strings.sync_empty), Modifier.padding(24.dp))
                }
            }
            items(state.pending, key = { it.id }) { item -> PendingRow(item, state, dispatch) }
            if (state.loading) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
            if (state.hasMore) {
                item("more") {
                    Action("sync-load-more", MR.strings.sync_load_more, enabled = !state.loading) {
                        dispatch(SyncPanelAction.LoadMore)
                    }
                }
            }
            item("history") {
                Action("sync-history", MR.strings.sync_records) {
                    dispatch(SyncPanelAction.Navigate(SyncPanelPage.HISTORY))
                }
            }
        }
    }
    LaunchedEffect(listState, state.hasMore, state.loading, state.pending.size) {
        if (!state.hasMore || state.loading) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= state.pending.size - 3 }
            .collect { dispatch(SyncPanelAction.LoadMore) }
    }
}

@Composable
private fun SyncStatusHeader(
    state: SyncPanelState,
    continuingSetup: Boolean,
    dispatch: (SyncPanelAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(48.dp).testTag("sync-status-icon"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Sync, syncString(MR.strings.sync_title), Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(statusText(state), style = MaterialTheme.typography.titleMedium)
            Text(
                syncString(MR.strings.sync_detail, state.queuedMembership, state.queuedReading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = {
                dispatch(
                    if (state.connection?.enabled != true || continuingSetup) {
                        SyncPanelAction.BeginSetup
                    } else {
                        SyncPanelAction.Synchronize
                    },
                )
            },
            enabled = (!state.busy && state.run?.state != SyncRunState.PAUSED_USER) || continuingSetup,
            modifier = Modifier.testTag("sync-now"),
        ) {
            Text(
                syncString(
                    when {
                        continuingSetup -> MR.strings.sync_setup_continue
                        state.connection?.enabled != true -> MR.strings.sync_connect
                        else -> MR.strings.sync_now
                    },
                ),
            )
        }
    }
}

@Composable
private fun SyncQueueSummary(state: SyncPanelState) {
    val classified = state.queuedFavorites + state.queuedFollows
    val favorites = state.queuedFavorites + (state.queuedMembership - classified).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("sync-queue-summary")) {
        SyncQueueRow(
            MR.strings.sync_queue_upload,
            syncString(MR.strings.sync_queue_upload_value, state.queuedTotal),
            "sync-queue-upload",
        )
        SyncQueueRow(
            MR.strings.sync_queue_membership,
            syncString(MR.strings.sync_queue_membership_value, favorites, state.queuedFollows),
            "sync-queue-membership",
        )
        SyncQueueRow(
            MR.strings.sync_queue_reading,
            syncString(MR.strings.sync_queue_reading_value, state.queuedReading),
            "sync-queue-reading",
        )
    }
}

@Composable
private fun SyncQueueRow(label: StringResource, value: String, tag: String) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(syncString(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun SelectionBar(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("sync-selection-bar"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(syncString(MR.strings.sync_pending), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    syncString(
                        if (state.selecting) MR.strings.sync_selected else MR.strings.sync_pending_count,
                        if (state.selecting) state.selected.size.toLong() else state.pendingTotal,
                    ),
                    Modifier.weight(1f),
                )
                if (state.selecting) {
                    Action("sync-select-all", MR.strings.sync_all) { dispatch(SyncPanelAction.SelectAll) }
                    Action("sync-invert", MR.strings.sync_invert) { dispatch(SyncPanelAction.InvertSelection) }
                    IconButton(
                        { dispatch(SyncPanelAction.SelectionMode(false)) },
                        Modifier.testTag("sync-exit-selection"),
                    ) {
                        Icon(Icons.Outlined.Close, syncString(MR.strings.sync_cancel))
                    }
                } else {
                    Action("sync-select", MR.strings.sync_multi, state.pendingTotal > 0) {
                        dispatch(SyncPanelAction.SelectionMode(true))
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            { expanded = true },
                            Modifier.testTag("sync-all-menu"),
                            enabled = state.pendingTotal > 0 && state.decisionsEnabled,
                        ) {
                            Icon(Icons.Outlined.MoreVert, syncString(MR.strings.sync_more))
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            for (decision in SyncCancellationDecision.entries) {
                                DropdownMenuItem(
                                    enabled = state.decisionsEnabled,
                                    text = {
                                        Text(
                                            syncString(
                                                if (decision == SyncCancellationDecision.CONFIRM) {
                                                    MR.strings.sync_remove_all
                                                } else {
                                                    MR.strings.sync_keep_all
                                                },
                                            ),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        dispatch(
                                            SyncPanelAction.PrepareDecision(decision, SyncDecisionScope.ALL),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (state.selecting) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Action(
                        "sync-keep-selected",
                        MR.strings.sync_keep_selected,
                        state.selected.isNotEmpty() && state.decisionsEnabled,
                    ) {
                        dispatch(
                            SyncPanelAction.PrepareDecision(
                                SyncCancellationDecision.KEEP_LOCAL,
                                SyncDecisionScope.SELECTED,
                            ),
                        )
                    }
                    Action(
                        "sync-remove-selected",
                        MR.strings.sync_remove_selected,
                        state.selected.isNotEmpty() && state.decisionsEnabled,
                    ) {
                        dispatch(
                            SyncPanelAction.PrepareDecision(
                                SyncCancellationDecision.CONFIRM,
                                SyncDecisionScope.SELECTED,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun PendingRow(item: SyncPendingItem, state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit) {
    var shift by remember(item.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag("sync-item-${item.id}")
            .pointerInput(item.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) shift = event.keyboardModifiers.isShiftPressed
                    }
                }
            }
            .combinedClickable(
                onClick = {
                    if (state.selecting || shift) dispatch(SyncPanelAction.ToggleItem(item.id, shift))
                },
                onLongClick = { dispatch(SyncPanelAction.ToggleItem(item.id, range = true)) },
            ).padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.selecting) Checkbox(item.id in state.selected, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    syncString(
                        if (item.objectKey.type == SyncObjectType.AUTHOR) {
                            MR.strings.sync_author
                        } else {
                            MR.strings.sync_manga
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!state.selecting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Action("sync-keep-${item.id}", MR.strings.sync_keep, state.decisionsEnabled) {
                    dispatch(
                        SyncPanelAction.PrepareDecision(
                            SyncCancellationDecision.KEEP_LOCAL,
                            SyncDecisionScope.ITEM,
                            item.id,
                        ),
                    )
                }
                Action("sync-remove-${item.id}", MR.strings.sync_remove, state.decisionsEnabled) {
                    dispatch(
                        SyncPanelAction.PrepareDecision(
                            SyncCancellationDecision.CONFIRM,
                            SyncDecisionScope.ITEM,
                            item.id,
                        ),
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SettingsPage(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier.padding(horizontal = 24.dp).testTag("sync-settings-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(syncString(MR.strings.sync_account), style = MaterialTheme.typography.titleMedium)
            state.connection?.let { Text("${it.repository.owner}/${it.repository.name}") }
            Action("sync-settings-connect", MR.strings.sync_reconnect) { dispatch(SyncPanelAction.Authorize) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(syncString(MR.strings.sync_startup), Modifier.weight(1f))
                Switch(
                    state.startup,
                    { dispatch(SyncPanelAction.SetStartup(it)) },
                    Modifier.testTag("sync-startup"),
                )
            }
        }
        item {
            Text(syncString(MR.strings.sync_frequency), style = MaterialTheme.typography.titleMedium)
            for (minutes in listOf(0, 15, 60, 360, 1440)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        state.periodMinutes == minutes,
                        { dispatch(SyncPanelAction.SetPeriod(minutes)) },
                        Modifier.testTag("sync-period-$minutes"),
                    )
                    Text(if (minutes == 0) syncString(MR.strings.sync_off) else duration(minutes.toLong()))
                }
            }
        }
        item {
            OutlinedTextField(
                state.deviceName,
                { dispatch(SyncPanelAction.SetDeviceName(it)) },
                Modifier.fillMaxWidth().testTag("sync-device-name"),
                label = { Text(syncString(MR.strings.sync_device_name)) },
                singleLine = true,
            )
        }
        item {
            Text(
                syncString(
                    when (state.connection?.protectionMode) {
                        "password" -> MR.strings.sync_password_enabled
                        "none" -> MR.strings.sync_password_disabled
                        else -> MR.strings.sync_password_unavailable
                    },
                ),
                Modifier.testTag("sync-password-status"),
            )
        }
        item {
            Action("sync-settings-history", MR.strings.sync_records) {
                dispatch(SyncPanelAction.Navigate(SyncPanelPage.HISTORY))
            }
        }
        item {
            Action("sync-disconnect", MR.strings.sync_disconnect, state.connection != null) {
                dispatch(SyncPanelAction.Ask(SyncPanelQuestion.DISCONNECT))
            }
        }
        item {
            Action("sync-switch", MR.strings.sync_switch, state.connection != null) {
                dispatch(SyncPanelAction.Ask(SyncPanelQuestion.SWITCH_SPACE))
            }
        }
    }
}

@Composable
private fun RecordsPage(state: SyncPanelState, modifier: Modifier) {
    LazyColumn(
        modifier.padding(24.dp).testTag("sync-records"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.records.isEmpty()) item { Text(syncString(MR.strings.sync_no_records)) }
        items(state.records) { record ->
            Column {
                Text(syncDate(record.time), style = MaterialTheme.typography.labelMedium)
                Text(
                    syncString(
                        MR.strings.sync_notice,
                        record.result.uploaded,
                        record.result.downloaded,
                        record.result.pending,
                    ),
                )
                record.result.problem?.let { Text(problemText(it)) }
            }
        }
    }
}

@Composable
private fun SetupPage(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    openBrowser: (String) -> Unit,
    copyCode: (String) -> Unit,
    claimBrowser: (GitHubDeviceCode) -> Boolean,
    onOpenFailureLog: (String) -> Unit,
    modifier: Modifier,
) {
    val deviceCode = state.deviceCode
    LaunchedEffect(state.visible, deviceCode?.deviceCode) {
        if (state.visible && state.setupStep == SyncSetupStep.SIGN_IN && deviceCode != null &&
            claimBrowser(deviceCode)
        ) {
            copyCode(deviceCode.userCode)
            openBrowser(deviceCode.verificationUri)
        }
    }
    // Session-local text only: closing or leaving this step discards unsubmitted input.
    var password by remember(state.visible, state.setupStep, state.setupRepository) { mutableStateOf(TextFieldValue()) }
    var showPassword by remember(state.visible, state.setupStep) { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    LazyColumn(
        modifier.fillMaxWidth().padding(24.dp).testTag("sync-setup-list"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.setupBusy && state.setupStep != SyncSetupStep.SIGN_IN &&
            !(state.setupStep == SyncSetupStep.MERGING && state.run?.state?.isTerminal() == true)
        ) {
            item { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
        state.setupProblem?.let { item { Text(setupProblemText(it), Modifier.testTag("sync-setup-error")) } }
        if (state.setupProblem == null) state.problem?.let { item { Text(problemText(it)) } }
        when (state.setupStep) {
            SyncSetupStep.SIGN_IN -> {
                if (state.setupBusy && state.authFailure == null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag(
                                if (state.deviceCode == null) {
                                    "sync-auth-getting-code"
                                } else {
                                    "sync-auth-waiting-browser"
                                },
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                syncString(
                                    if (state.deviceCode == null) {
                                        MR.strings.sync_auth_getting_code
                                    } else {
                                        MR.strings.sync_auth_waiting_browser
                                    },
                                ),
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (state.setupBusy && state.deviceCode == null && state.authFailure == null) {
                    val startedAt = state.authRequestStartedAtMillis
                    if (startedAt != null && state.nowMillis >= startedAt && state.nowMillis - startedAt >= 5_000) {
                        item {
                            Text(
                                syncString(MR.strings.sync_auth_check_network),
                                Modifier.fillMaxWidth().testTag("sync-auth-network-hint"),
                            )
                        }
                    }
                }
                item { Text(syncString(MR.strings.sync_auth_description), Modifier.fillMaxWidth()) }
                state.deviceCode?.let { code ->
                    item { Text(code.userCode, style = MaterialTheme.typography.headlineMedium) }
                    item {
                        Action("sync-copy-open", MR.strings.sync_copy_open) {
                            copyCode(code.userCode)
                            openBrowser(code.verificationUri)
                        }
                    }
                    item {
                        Action("sync-cancel-auth", MR.strings.sync_auth_cancel) {
                            dispatch(SyncPanelAction.CancelAuthorization)
                        }
                    }
                }
                state.authFailure?.let { failure ->
                    item {
                        Text(
                            syncString(
                                if (failure == GitHubAuthFailureReason.HTTP) {
                                    MR.strings.sync_auth_request_failed
                                } else {
                                    MR.strings.sync_auth_failed
                                },
                            ),
                        )
                    }
                }
                if (state.deviceCode == null || state.authFailure != null) {
                    item {
                        Action(
                            "sync-authorize",
                            if (state.authFailure == null) MR.strings.sync_connect else MR.strings.sync_auth_retry,
                            !state.setupBusy,
                        ) { dispatch(SyncPanelAction.Authorize) }
                    }
                }
            }
            SyncSetupStep.NEW_PASSWORD, SyncSetupStep.UNLOCK -> {
                val creating = state.setupStep == SyncSetupStep.NEW_PASSWORD
                item {
                    Text(
                        syncString(
                            if (creating) MR.strings.sync_password_new_title else MR.strings.sync_password_unlock_title,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    Text(
                        syncString(
                            if (creating) MR.strings.sync_password_new_hint else MR.strings.sync_password_unlock_hint,
                        ),
                    )
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).testTag("sync-password-input"),
                        enabled = !state.setupBusy,
                        label = {
                            Text(
                                syncString(
                                    if (creating) MR.strings.sync_password_optional else MR.strings.sync_password_label,
                                ),
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                        ),
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        singleLine = true,
                        isError = state.passwordProblem != null,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    showPassword = !showPassword
                                    passwordFocus.requestFocus()
                                },
                                enabled = !state.setupBusy,
                                modifier = Modifier.testTag("sync-password-visibility"),
                            ) {
                                Icon(
                                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    syncString(
                                        if (showPassword) {
                                            MR.strings.sync_password_hide
                                        } else {
                                            MR.strings.sync_password_show
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
                state.passwordProblem?.let { problem ->
                    item {
                        Text(
                            syncString(
                                when (problem) {
                                    SyncPasswordProblem.INCORRECT -> MR.strings.sync_password_incorrect
                                    SyncPasswordProblem.TOO_LONG -> MR.strings.sync_password_too_long
                                    SyncPasswordProblem.INVALID -> MR.strings.sync_password_invalid
                                },
                            ),
                            Modifier.testTag("sync-password-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Action(
                        "sync-password-submit",
                        when {
                            !creating -> MR.strings.sync_password_connect
                            password.text.isEmpty() -> MR.strings.sync_password_skip
                            else -> MR.strings.sync_password_confirm
                        },
                        !state.setupBusy && (creating || password.text.isNotEmpty()),
                    ) {
                        val submitted = password.text
                        password = TextFieldValue()
                        dispatch(SyncPanelAction.SubmitPassword(submitted))
                    }
                }
            }
            SyncSetupStep.CHOOSE_SPACE -> {
                item { Text(syncString(MR.strings.sync_setup_multiple)) }
                items(state.spaces, key = { it.repositoryId }) { space ->
                    TextButton(
                        onClick = { dispatch(SyncPanelAction.ChooseSpace(space)) },
                        enabled = !state.setupBusy,
                        modifier = Modifier.testTag("sync-space-${space.repositoryId}"),
                    ) { Text(space.repository.fullName) }
                }
            }
            SyncSetupStep.DISCOVERING -> item { Text(syncString(MR.strings.sync_setup_discovering)) }
            SyncSetupStep.CREATING -> item { Text(syncString(MR.strings.sync_setup_creating)) }
            SyncSetupStep.MERGING -> {
                if (state.run?.state?.isTerminal() != true) {
                    item { Text(syncString(MR.strings.sync_setup_merging)) }
                }
                state.run?.let { run ->
                    item { SyncProgressCard(run, state, dispatch, onOpenFailureLog, horizontalPadding = 0.dp) }
                }
                if (state.importRemaining > 0) {
                    item { Text(syncString(MR.strings.sync_import_remaining, state.importRemaining)) }
                    item {
                        if (state.importPaused) {
                            Action("sync-resume-import", MR.strings.sync_resume) {
                                dispatch(SyncPanelAction.ResumeImport)
                            }
                        } else {
                            Action("sync-pause-import", MR.strings.sync_pause) { dispatch(SyncPanelAction.PauseImport) }
                        }
                    }
                }
            }
            SyncSetupStep.COMPLETE -> item { Text(syncString(MR.strings.sync_setup_complete)) }
            SyncSetupStep.ERROR -> {
                item {
                    Action("sync-setup-retry", MR.strings.sync_setup_retry, !state.setupBusy) {
                        dispatch(SyncPanelAction.RetrySetup)
                    }
                }
                item {
                    Action("sync-repo-reconnect", MR.strings.sync_reconnect, !state.setupBusy) {
                        dispatch(SyncPanelAction.Authorize)
                    }
                }
                if (state.setupProblem in setOf(
                        SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
                        SyncDiscoveryProblem.NAME_OCCUPIED,
                        SyncDiscoveryProblem.CREATION_UNCONFIRMED,
                    )
                ) {
                    item {
                        Action("sync-install-app", MR.strings.sync_install_app) {
                            openBrowser("https://github.com/apps/mihon-desktop/installations/new")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncProgressCard(
    run: SyncRunSnapshot,
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    onOpenFailureLog: (String) -> Unit,
    horizontalPadding: Dp = 24.dp,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 16.dp)
            .testTag("sync-progress-card"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        val reportScroll = state.failureLog?.runId == run.runId && run.state.isTerminal()
        Column(
            Modifier.fillMaxWidth().then(
                if (reportScroll) Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()) else Modifier,
            ).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val terminal = run.state.isTerminal()
            val progress = state.progress?.takeIf { it.scope == run.runId || it.scope.startsWith("${run.runId}:") }
            if (terminal) {
                SyncTerminalProgress(run, state)
            } else if (progress != null) {
                SyncStageProgress(run, progress, state.nowMillis)
            } else {
                Text(syncString(runPhaseLabel(run.phase)), style = MaterialTheme.typography.titleMedium)
            }
            if (!terminal && progress == null && run.total > 0) {
                Text(
                    syncString(MR.strings.sync_progress, run.processed, run.total),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (run.processed.toFloat() / run.total).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().testTag("sync-progress"),
                )
                val remaining = (run.total - run.completed - run.skipped - run.failed).coerceAtLeast(0)
                Text(
                    syncString(
                        MR.strings.sync_progress_detail,
                        run.completed,
                        run.skipped,
                        run.failed,
                        remaining,
                    ),
                    Modifier.testTag("sync-progress-detail"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!terminal && progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sync-progress"))
            }
            when (run.state) {
                SyncRunState.PAUSED_USER -> {
                    Text(syncString(MR.strings.sync_paused))
                    Action("sync-resume-run", MR.strings.sync_resume_run) {
                        dispatch(SyncPanelAction.ResumeSync)
                    }
                }
                SyncRunState.WAITING_NETWORK -> Text(syncString(MR.strings.sync_waiting_network))
                SyncRunState.WAITING_SYSTEM -> Text(syncString(MR.strings.sync_waiting_system))
                SyncRunState.WAITING_RETRY -> {
                    val waitingLabel = if (run.stopReason == "rate_limit") {
                        MR.strings.sync_waiting_rate_limit
                    } else {
                        MR.strings.sync_waiting_retry
                    }
                    Text(syncString(waitingLabel))
                    Text(retryLabel(run, state.nowMillis), Modifier.testTag("sync-retry-countdown"))
                }
                SyncRunState.FAILED -> {
                    Action("sync-retry-run", MR.strings.sync_retry_run) {
                        dispatch(SyncPanelAction.RetrySync)
                    }
                }
                SyncRunState.BLOCKED -> {
                    Text(
                        problemText(state.problem ?: SyncRunProblem.UNKNOWN),
                        Modifier.testTag("sync-blocked-reason"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SyncRunState.PARTIAL -> {
                    if ((state.terminalSummary?.takeIf { it.runId == run.runId }?.sourceUnavailableFields ?: 0L) ==
                        0L
                    ) {
                        Text(
                            if (run.stopReason == "projection_pending") {
                                syncString(MR.strings.sync_projection_pending)
                            } else if (state.pendingTotal > 0) {
                                syncString(MR.strings.sync_pending_decisions_count, state.pendingTotal)
                            } else {
                                syncString(MR.strings.sync_pending_decisions)
                            },
                        )
                    }
                    Action("sync-retry-run", MR.strings.sync_retry_run) {
                        dispatch(SyncPanelAction.RetrySync)
                    }
                }
                SyncRunState.SUCCEEDED,
                SyncRunState.CANCELLED,
                -> Unit
                else -> if (progress?.hold != SyncProgressHold.PAUSING) {
                    Action("sync-pause-run", MR.strings.sync_pause_run) {
                        dispatch(SyncPanelAction.PauseSync)
                    }
                }
            }
            when (val failureLog = state.failureLog?.takeIf { it.runId == run.runId && terminal }) {
                is SyncFailureLogStatus.Ready -> {
                    Text(syncString(MR.strings.sync_failure_log_count, failureLog.failedEntries))
                    Text(
                        failureLog.path,
                        Modifier.testTag("sync-failure-log-path").clickable { onOpenFailureLog(failureLog.path) },
                    )
                    Action("sync-failure-log-open", MR.strings.sync_failure_log_open) {
                        onOpenFailureLog(failureLog.path)
                    }
                }
                is SyncFailureLogStatus.SaveFailed -> Text(
                    if (failureLog.failedEntries > 0L) {
                        syncString(MR.strings.sync_failure_log_save_failed, failureLog.failedEntries)
                    } else {
                        syncString(MR.strings.sync_failure_log_save_failed_unknown)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
            if (state.logs.isNotEmpty()) {
                Text(syncString(MR.strings.sync_log_title), style = MaterialTheme.typography.titleSmall)
                state.logs.forEach { log ->
                    Column(Modifier.fillMaxWidth().testTag("sync-log-${log.key}")) {
                        Text(log.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            log.detail,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.logsHasMore) {
                    Action("sync-log-more", MR.strings.sync_load_more) {
                        dispatch(SyncPanelAction.LoadMoreLogs)
                    }
                }
            }
        }
    }
}

private fun SyncRunState.isTerminal(): Boolean = this in setOf(
    SyncRunState.SUCCEEDED,
    SyncRunState.PARTIAL,
    SyncRunState.FAILED,
    SyncRunState.BLOCKED,
    SyncRunState.CANCELLED,
)

@Composable
private fun SyncTerminalProgress(run: SyncRunSnapshot, state: SyncPanelState) {
    val title = when (run.state) {
        SyncRunState.SUCCEEDED -> MR.strings.sync_phase_complete
        SyncRunState.PARTIAL -> MR.strings.sync_terminal_partial
        SyncRunState.FAILED -> MR.strings.sync_retry_exhausted
        SyncRunState.BLOCKED -> MR.strings.sync_blocked
        SyncRunState.CANCELLED -> MR.strings.sync_terminal_cancelled
        else -> error("A terminal result is required")
    }
    Text(syncString(title), style = MaterialTheme.typography.titleMedium)
    Text(syncString(MR.strings.sync_items_confirmed_this_run, run.confirmedItems))
    val summary = state.terminalSummary?.takeIf { it.runId == run.runId }
    if (summary != null && summary.pendingDownloadBatches > 0L) {
        Text(
            syncString(
                MR.strings.sync_terminal_pending_receipts,
                summary.pendingDownloadBatches,
                summary.pendingDownloadEvents,
            ),
        )
        Text(syncString(MR.strings.sync_terminal_pending_explanation))
    }
    if (summary != null && summary.sourceUnavailableFields > 0L) {
        Text(syncString(MR.strings.sync_terminal_source_fields, summary.sourceUnavailableFields))
        Text(syncString(MR.strings.sync_terminal_source_retry))
    }
    val elapsedSeconds = ((run.updatedAt - run.createdAt).coerceAtLeast(0L) / 1_000L)
    val elapsed = "${(elapsedSeconds / 60).toString().padStart(2, '0')}:" +
        (elapsedSeconds % 60).toString().padStart(2, '0')
    Text(syncString(MR.strings.sync_elapsed, elapsed))
}

@Composable
private fun SyncStageProgress(run: SyncRunSnapshot, fact: SyncProgressFact, nowMillis: Long) {
    val stageResources = listOf(
        MR.strings.sync_stage_prepare,
        MR.strings.sync_stage_transfer,
        MR.strings.sync_stage_confirm,
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        stageResources.forEachIndexed { index, label ->
            val status = when {
                run.state == SyncRunState.SUCCEEDED || index < fact.stage.ordinal -> MR.strings.sync_stage_done
                index > fact.stage.ordinal -> MR.strings.sync_stage_waiting
                run.state in setOf(SyncRunState.FAILED, SyncRunState.BLOCKED, SyncRunState.CANCELLED) ->
                    MR.strings.sync_stage_failed
                run.state in setOf(
                    SyncRunState.PAUSED_USER,
                    SyncRunState.WAITING_NETWORK,
                    SyncRunState.WAITING_RETRY,
                    SyncRunState.WAITING_SYSTEM,
                    SyncRunState.PARTIAL,
                ) -> MR.strings.sync_stage_waiting
                else -> MR.strings.sync_stage_active
            }
            Text(
                "${syncString(label)} · ${syncString(status)}",
                style = if (index ==
                    fact.stage.ordinal
                ) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (fact.additionalWork) Text(syncString(MR.strings.sync_additional_work))
    val action = when (fact.stage) {
        SyncProgressStage.PREPARING -> if (run.phase == SyncRunPhase.UPLOADING) {
            MR.strings.sync_preparing_upload
        } else {
            runPhaseLabel(run.phase)
        }
        SyncProgressStage.TRANSFERRING -> if (fact.direction == SyncProgressDirection.UPLOAD) {
            MR.strings.sync_phase_uploading
        } else {
            MR.strings.sync_phase_downloading
        }
        SyncProgressStage.CONFIRMING -> if (fact.direction == SyncProgressDirection.UPLOAD) {
            MR.strings.sync_checking_github_saved
        } else if (fact.mergingReceivedData) {
            MR.strings.sync_merging_received_data
        } else {
            MR.strings.sync_receiving_and_verifying
        }
    }
    Text(syncString(action), style = MaterialTheme.typography.titleMedium)
    if (fact.stage == SyncProgressStage.PREPARING) {
        val imported = fact.importCompletedItems
        val importTotal = fact.importTotalItems
        if (imported != null && importTotal != null) {
            Text(syncString(MR.strings.sync_import_progress, imported, importTotal))
        }
    }
    if (fact.hold == SyncProgressHold.RECOVERING) {
        Text(syncString(MR.strings.sync_recovering_progress))
    } else if (fact.hold == SyncProgressHold.PAUSING) {
        Text(syncString(MR.strings.sync_pausing_save))
    }
    if (fact.totalItems == 0L && fact.completedItems == 0L && run.state == SyncRunState.SUCCEEDED) {
        Text(syncString(MR.strings.sync_no_pending_data))
    } else {
        val countResource = when (fact.stage) {
            SyncProgressStage.PREPARING -> MR.strings.sync_items_prepared
            SyncProgressStage.TRANSFERRING -> MR.strings.sync_items_transferred
            SyncProgressStage.CONFIRMING -> MR.strings.sync_items_confirmed
        }
        val unknownResource = when (fact.stage) {
            SyncProgressStage.PREPARING -> MR.strings.sync_items_prepared_unknown
            SyncProgressStage.TRANSFERRING -> MR.strings.sync_items_transferred_unknown
            SyncProgressStage.CONFIRMING -> MR.strings.sync_items_confirmed_unknown
        }
        val totalItems = fact.totalItems
        if (totalItems != null) {
            Text(syncString(countResource, fact.completedItems, totalItems), Modifier.testTag("sync-stage-count"))
        } else {
            if (run.state == SyncRunState.RUNNING) Text(syncString(MR.strings.sync_counting_data))
            Text(syncString(unknownResource, fact.completedItems), Modifier.testTag("sync-stage-count"))
        }
    }
    if (fact.direction == SyncProgressDirection.DOWNLOAD && fact.stage == SyncProgressStage.CONFIRMING) {
        fact.receivedItems?.takeIf { it > 0L }?.let { received ->
            val total = fact.receivedTotalItems
            Text(
                if (total != null) {
                    syncString(MR.strings.sync_items_received, received, total)
                } else {
                    syncString(MR.strings.sync_items_received_unknown, received)
                },
                Modifier.testTag("sync-received-count"),
            )
        }
        if (fact.checkedFields > 0L) {
            Text(syncString(MR.strings.sync_fields_checked, fact.checkedFields), Modifier.testTag("sync-checked-count"))
        }
    }
    if (fact.unavailableFields > 0L && run.state == SyncRunState.RUNNING) {
        Text(syncString(MR.strings.sync_source_unavailable_progress))
    }
    val fraction = when (fact.stage) {
        SyncProgressStage.TRANSFERRING -> fact.totalBytes?.takeIf { it > 0L }
            ?.let { (fact.effectiveBytes.toFloat() / it).coerceIn(0f, 1f) }
        SyncProgressStage.PREPARING -> fact.totalItems?.takeIf { it > fact.completedItems }
            ?.let { (fact.completedItems.toFloat() / it).coerceIn(0f, 1f) }
        SyncProgressStage.CONFIRMING -> fact.totalItems?.takeIf { it > 0L && fact.completedItems > 0L }
            ?.let { (fact.completedItems.toFloat() / it).coerceIn(0f, 1f) }
    }
    val progressDescription = if (fact.stage == SyncProgressStage.TRANSFERRING && fraction != null) {
        syncString(MR.strings.sync_transfer_percent, (fraction * 100).toInt())
    } else {
        syncString(stageResources[fact.stage.ordinal])
    }
    val progressModifier = Modifier.fillMaxWidth().testTag("sync-progress").semantics {
        stateDescription = progressDescription
    }
    if (fraction == null) {
        LinearProgressIndicator(progressModifier)
    } else {
        LinearProgressIndicator(progress = { fraction }, modifier = progressModifier)
    }
    if (fact.stage == SyncProgressStage.TRANSFERRING && fraction != null) {
        Text(progressDescription)
    }
    if (fact.stage == SyncProgressStage.TRANSFERRING) {
        val bodyFraction = fact.activeBodyTotal?.takeIf { it > 0L }?.let { total ->
            fact.activeBodyBytes?.let { (it.toFloat() / total).coerceIn(0f, 1f) }
        }
        if (bodyFraction != null && fact.totalBytes == null) {
            val bodyDescription = syncString(MR.strings.sync_active_body_percent, (bodyFraction * 100).toInt())
            Text(bodyDescription)
            LinearProgressIndicator(
                progress = { bodyFraction },
                modifier = Modifier.fillMaxWidth().testTag("sync-active-body-progress").semantics {
                    stateDescription = bodyDescription
                },
            )
        }
    }
    fact.confirmedThisRun?.let { confirmed ->
        Text(syncString(MR.strings.sync_items_confirmed_this_run, confirmed))
    }
    val elapsedEnd = if (run.state in setOf(
            SyncRunState.SUCCEEDED,
            SyncRunState.PARTIAL,
            SyncRunState.FAILED,
            SyncRunState.BLOCKED,
            SyncRunState.CANCELLED,
        )
    ) {
        run.updatedAt
    } else {
        nowMillis
    }
    val elapsedSeconds = ((elapsedEnd - run.createdAt).coerceAtLeast(0L) / 1_000L)
    val elapsed = "${(elapsedSeconds / 60).toString().padStart(2, '0')}:" +
        (elapsedSeconds % 60).toString().padStart(2, '0')
    Text(syncString(MR.strings.sync_elapsed, elapsed))
    val lastProgressSeconds = fact.secondsSinceLastProgress
    val idleSeconds = (fact.secondsWithoutProgress ?: lastProgressSeconds)?.takeIf {
        run.state == SyncRunState.RUNNING && fact.hold == SyncProgressHold.ACTIVE
    }
    if (idleSeconds != null && lastProgressSeconds != null) {
        Text(
            if (lastProgressSeconds < 10L) {
                syncString(MR.strings.sync_recent_progress_now)
            } else {
                syncString(MR.strings.sync_recent_progress_seconds, lastProgressSeconds)
            },
        )
    }
    if (idleSeconds != null) {
        if (idleSeconds >= 60L) {
            Text(syncString(MR.strings.sync_no_progress_minute))
        } else if (idleSeconds >= 10L) {
            Text(
                syncString(
                    if (fact.stage == SyncProgressStage.CONFIRMING) {
                        MR.strings.sync_waiting_current_step
                    } else {
                        MR.strings.sync_waiting_step_generic
                    },
                ),
            )
        }
    }
    if (run.state == SyncRunState.RUNNING && fact.hold == SyncProgressHold.ACTIVE) {
        val wholeEtaSeconds = fact.wholeEtaSeconds
        val stageEtaSeconds = fact.stageEtaSeconds
        val bodyEtaSeconds = fact.activeBodyEtaSeconds
        when {
            idleSeconds != null && idleSeconds >= 10L -> Text(syncString(MR.strings.sync_eta_unavailable))
            wholeEtaSeconds != null -> Text(
                syncString(MR.strings.sync_eta_whole, syncEtaDuration(wholeEtaSeconds)),
            )
            stageEtaSeconds != null -> {
                Text(syncString(MR.strings.sync_eta_stage, syncEtaDuration(stageEtaSeconds)))
                Text(syncString(MR.strings.sync_eta_unknown_whole))
            }
            bodyEtaSeconds != null -> {
                Text(syncString(MR.strings.sync_eta_body, syncEtaDuration(bodyEtaSeconds)))
                Text(syncString(MR.strings.sync_eta_unknown_whole))
            }
            fact.stage == SyncProgressStage.CONFIRMING || fact.elapsedSeconds >= 10 ->
                Text(syncString(MR.strings.sync_eta_unavailable))
            else -> Text(syncString(MR.strings.sync_eta_unknown))
        }
    }
}

@Composable
private fun syncEtaDuration(seconds: Long): String = when {
    seconds <= 0 -> syncString(MR.strings.sync_eta_finishing)
    seconds == 1L -> syncString(MR.strings.sync_duration_one_second)
    seconds < 60 -> syncString(MR.strings.sync_duration_seconds, seconds)
    seconds < 90 -> syncString(MR.strings.sync_duration_one_minute)
    else -> syncString(MR.strings.sync_duration_minutes, (seconds + 30) / 60)
}

@Composable
private fun setupProblemText(problem: SyncDiscoveryProblem): String = syncString(
    when (problem) {
        SyncDiscoveryProblem.AUTHORIZATION_REQUIRED -> MR.strings.sync_setup_authorization
        SyncDiscoveryProblem.RATE_LIMITED -> MR.strings.sync_setup_rate_limited
        SyncDiscoveryProblem.INCOMPATIBLE -> MR.strings.sync_setup_incompatible
        SyncDiscoveryProblem.NAME_OCCUPIED -> MR.strings.sync_setup_name_occupied
        SyncDiscoveryProblem.CREATION_UNCONFIRMED -> MR.strings.sync_setup_unconfirmed
        SyncDiscoveryProblem.ACCOUNT_CHANGED -> MR.strings.sync_setup_account_changed
        SyncDiscoveryProblem.MULTIPLE_SPACES -> MR.strings.sync_setup_multiple
        SyncDiscoveryProblem.MALFORMED -> MR.strings.sync_problem_data
        SyncDiscoveryProblem.RETRYABLE -> MR.strings.sync_problem_unknown
        SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE -> MR.strings.sync_problem_not_private
    },
)

@Composable
private fun statusText(state: SyncPanelState): String = when {
    state.setupProblem != null -> state.setupProblem?.let { setupProblemText(it) }.orEmpty()
    state.busy -> syncString(MR.strings.sync_busy)
    state.problem != null -> state.problem?.let { problemText(it) }.orEmpty()
    state.connection?.enabled != true -> syncString(MR.strings.sync_connect)
    state.periodMinutes > 0 -> if (state.nextSyncAtMillis <= state.nowMillis) {
        syncString(MR.strings.sync_soon)
    } else {
        val minutes = ((state.nextSyncAtMillis - state.nowMillis - 1) / 60_000) + 1
        syncString(MR.strings.sync_next, duration(minutes))
    }
    state.queuedTotal > 0 -> syncString(MR.strings.sync_queued, state.queuedTotal)
    state.pendingTotal > 0 -> syncString(MR.strings.sync_exchanged)
    else -> syncString(MR.strings.sync_synced)
}

private fun runPhaseLabel(phase: SyncRunPhase): StringResource = when (phase) {
    SyncRunPhase.CHECKING -> MR.strings.sync_phase_checking
    SyncRunPhase.IMPORTING -> MR.strings.sync_phase_importing
    SyncRunPhase.DOWNLOADING -> MR.strings.sync_phase_downloading
    SyncRunPhase.MERGING -> MR.strings.sync_phase_merging
    SyncRunPhase.UPLOADING -> MR.strings.sync_phase_uploading
    SyncRunPhase.CONFIRMING -> MR.strings.sync_phase_confirming
    SyncRunPhase.COMPLETE -> MR.strings.sync_phase_complete
}

@Composable
private fun retryLabel(run: SyncRunSnapshot, nowMillis: Long): String {
    val remainingSeconds = ((run.nextRetryAt - nowMillis).coerceAtLeast(0L) + 999L) / 1_000L
    return when {
        remainingSeconds == 0L -> syncString(MR.strings.sync_retry_ready)
        remainingSeconds < 60L -> syncString(MR.strings.sync_retry_after_seconds, remainingSeconds)
        else -> syncString(MR.strings.sync_retry_after_minutes, (remainingSeconds + 59L) / 60L)
    }
}

@Composable
private fun duration(minutes: Long): String = buildList {
    if (minutes >= 1440) add(syncString(MR.strings.sync_days, minutes / 1440))
    if (minutes % 1440 >= 60) add(syncString(MR.strings.sync_hours, minutes % 1440 / 60))
    if (minutes % 60 > 0) add(syncString(MR.strings.sync_minutes, minutes % 60))
}.joinToString(" ")

@Composable
private fun problemText(problem: SyncRunProblem): String = syncString(
    when (problem) {
        SyncRunProblem.AUTHORIZATION -> MR.strings.sync_problem_auth
        SyncRunProblem.NETWORK -> MR.strings.sync_problem_network
        SyncRunProblem.STORAGE -> MR.strings.sync_problem_storage
        SyncRunProblem.REMOTE_CHANGED -> MR.strings.sync_problem_remote
        SyncRunProblem.INVALID_DATA -> MR.strings.sync_problem_data
        SyncRunProblem.UNKNOWN -> MR.strings.sync_problem_unknown
        SyncRunProblem.REPOSITORY_NOT_PRIVATE -> MR.strings.sync_problem_not_private
    },
)

private fun decisionLabel(decision: SyncCancellationDecision) =
    if (decision == SyncCancellationDecision.CONFIRM) MR.strings.sync_remove else MR.strings.sync_keep

private val SyncPanelState.decisionsEnabled: Boolean
    get() = bulk?.let { it.running || it.remaining > 0 } != true

@Composable
private fun Action(tag: String, label: StringResource, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.testTag(tag), enabled = enabled) { Text(syncString(label)) }
}
