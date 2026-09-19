package mihon.presentation.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import mihon.data.sync.auth.SyncAppInstallation
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncInstallationAccountType
import mihon.data.sync.auth.SyncRepositorySelection
import mihon.data.sync.inbox.SyncPendingItem
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncPasswordProblem
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncRepositoryTarget
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
) {
    val state by panel.state.collectAsState()
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        PanelHeader(state, panel::dispatch)
        HorizontalDivider()
        when (state.page) {
            SyncPanelPage.MAIN -> MainPage(state, panel::dispatch, listState, Modifier.weight(1f))
            SyncPanelPage.SETTINGS -> SettingsPage(state, panel::dispatch, Modifier.weight(1f))
            SyncPanelPage.HISTORY -> RecordsPage(state, Modifier.weight(1f))
            SyncPanelPage.SETUP -> SetupPage(
                state,
                panel::dispatch,
                onOpenBrowser,
                onCopyCode,
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
        Text(
            syncString(
                when (state.page) {
                    SyncPanelPage.SETTINGS -> MR.strings.sync_settings
                    SyncPanelPage.HISTORY -> MR.strings.sync_records
                    else -> MR.strings.sync_title
                },
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
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
    listState: LazyListState,
    modifier: Modifier,
) {
    val continuingSetup = state.setupStep !in setOf(SyncSetupStep.SIGN_IN, SyncSetupStep.COMPLETE)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(statusText(state), style = MaterialTheme.typography.titleMedium)
                Text(
                    syncString(MR.strings.sync_detail, state.queuedMembership, state.queuedReading),
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
                enabled = !state.busy || continuingSetup,
                modifier = Modifier.padding(start = 8.dp).testTag("sync-now"),
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
                        if (result.status == SyncRunStatus.FAILED || result.status == SyncRunStatus.PARTIAL) {
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
    modifier: Modifier,
) {
    // Session-local text only: closing or leaving this step discards unsubmitted input.
    var password by remember(state.visible, state.setupStep, state.setupRepository) { mutableStateOf(TextFieldValue()) }
    var showPassword by remember(state.visible, state.setupStep) { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    LazyColumn(modifier.padding(24.dp).testTag("sync-setup-list"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.setupBusy) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        state.setupProblem?.let { item { Text(setupProblemText(it), Modifier.testTag("sync-setup-error")) } }
        if (state.setupProblem == null) state.problem?.let { item { Text(problemText(it)) } }
        state.setupInstallation?.let(::installationScopeWarning)?.let { warning ->
            item {
                Text(syncString(warning), Modifier.testTag("sync-installation-scope-warning"))
            }
        }
        when (state.setupStep) {
            SyncSetupStep.SIGN_IN -> {
                item { Text(syncString(MR.strings.sync_auth_description)) }
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
                if (state.authFailure != null) item { Text(syncString(MR.strings.sync_auth_failed)) }
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
                if (creating) {
                    item {
                        Text(
                            syncString(
                                MR.strings.sync_setup_new_space_target,
                                state.setupRepository?.fullName.orEmpty(),
                            ),
                            Modifier.testTag("sync-setup-target"),
                        )
                    }
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
                item { Text(syncString(MR.strings.sync_setup_merging)) }
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
                val problem = state.setupProblem
                val needsRepositoryGuide = problem in setOf(
                    SyncDiscoveryProblem.NEEDS_INSTALLATION,
                    SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                )
                if (needsRepositoryGuide) {
                    item {
                        Text(syncString(MR.strings.sync_setup_repository_guide))
                    }
                    item {
                        Text(syncString(MR.strings.sync_setup_app_guide))
                    }
                    item {
                        Text(syncString(MR.strings.sync_setup_recheck_guide))
                    }
                    state.setupAccountLogin?.let { login ->
                        item {
                            Action("sync-create-private-repo", MR.strings.sync_setup_create_repo) {
                                openBrowser(githubRepositoryCreationUrl(login))
                            }
                        }
                    }
                }
                val installationIsMissing = problem == SyncDiscoveryProblem.NEEDS_INSTALLATION
                val manageInstallation = problem in setOf(
                    SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                    SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION,
                    SyncDiscoveryProblem.INSTALLATION_SUSPENDED,
                    SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE,
                    SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE,
                )
                val installationManagementUrl = if (manageInstallation) {
                    state.setupInstallation?.let { installation ->
                        state.setupAccountLogin?.let { login -> githubInstallationManagementUrl(installation, login) }
                    }
                } else {
                    null
                }
                if (installationIsMissing || installationManagementUrl != null) {
                    item {
                        Action(
                            "sync-install-app",
                            if (installationIsMissing) {
                                MR.strings.sync_setup_install_app
                            } else {
                                MR.strings.sync_setup_manage_installation
                            },
                        ) {
                            openBrowser(
                                if (installationIsMissing) {
                                    GITHUB_APP_INSTALL_URL
                                } else {
                                    requireNotNull(installationManagementUrl)
                                },
                            )
                        }
                    }
                    item {
                        Action("sync-recheck-installation", MR.strings.sync_setup_recheck, !state.setupBusy) {
                            dispatch(SyncPanelAction.RetrySetup)
                        }
                    }
                }
                if (problem !in INSTALLATION_RECOVERY_PROBLEMS) {
                    item {
                        Action("sync-setup-retry", MR.strings.sync_setup_retry, !state.setupBusy) {
                            dispatch(SyncPanelAction.RetrySetup)
                        }
                    }
                }
                if (problem == SyncDiscoveryProblem.AUTHORIZATION_REQUIRED) {
                    item {
                        Action("sync-repo-reconnect", MR.strings.sync_reconnect, !state.setupBusy) {
                            dispatch(SyncPanelAction.Authorize)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun setupProblemText(problem: SyncDiscoveryProblem): String = syncString(
    when (problem) {
        SyncDiscoveryProblem.NEEDS_INSTALLATION -> MR.strings.sync_setup_needs_installation
        SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS -> MR.strings.sync_setup_needs_repository_access
        SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION -> MR.strings.sync_setup_missing_contents
        SyncDiscoveryProblem.INSTALLATION_SUSPENDED -> MR.strings.sync_setup_installation_suspended
        SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE -> MR.strings.sync_setup_not_writable
        SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE -> MR.strings.sync_setup_repository_unavailable
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

private val INSTALLATION_RECOVERY_PROBLEMS = setOf(
    SyncDiscoveryProblem.NEEDS_INSTALLATION,
    SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
    SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION,
    SyncDiscoveryProblem.INSTALLATION_SUSPENDED,
    SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE,
    SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE,
)

private const val GITHUB_APP_INSTALL_URL = "https://github.com/apps/mihon-desktop/installations/new"

private fun githubRepositoryCreationUrl(owner: String): String =
    "https://github.com/new?name=${encodeQueryParameter(SyncRepositoryTarget.NAME)}" +
        "&visibility=${encodeQueryParameter("private")}&owner=${encodeQueryParameter(owner)}"

private fun githubInstallationManagementUrl(installation: SyncAppInstallation, accountLogin: String): String? {
    if (installation.id <= 0) return null
    return when (installation.accountType) {
        SyncInstallationAccountType.USER -> "https://github.com/settings/installations/${installation.id}"
        SyncInstallationAccountType.ORGANIZATION ->
            "https://github.com/organizations/${encodeQueryParameter(accountLogin)}" +
                "/settings/installations/${installation.id}"
    }
}

private fun installationScopeWarning(installation: SyncAppInstallation): StringResource? = when {
    installation.repositorySelection == SyncRepositorySelection.ALL -> MR.strings.sync_setup_scope_all
    installation.authorizedRepositoryCount?.let { it > 1 } == true -> MR.strings.sync_setup_scope_multiple
    else -> null
}

private fun encodeQueryParameter(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code ||
            code in '0'.code..'9'.code || code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
        ) {
            append(code.toChar())
        } else {
            append('%')
            append(hex[code shr 4])
            append(hex[code and 0x0F])
        }
    }
}

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
