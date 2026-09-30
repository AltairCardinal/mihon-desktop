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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import mihon.data.sync.auth.SyncAppInstallation
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncInstallationAccountType
import mihon.data.sync.auth.SyncRepositorySelection
import mihon.data.sync.inbox.SyncPendingItem
import mihon.data.sync.runtime.SyncDecisionScope
import mihon.data.sync.runtime.SyncDiagnosticFeedback
import mihon.data.sync.runtime.SyncDiagnosticStatus
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
import mihon.domain.sync.transport.SyncRepositoryTarget
import tachiyomi.i18n.MR
import kotlin.time.TimeSource

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
    onOpenDiagnostic: (String) -> Unit = {},
    displayMonotonicMillis: (() -> Long)? = null,
) {
    val state by panel.state.collectAsState()
    if (!state.visible) return
    val listState = rememberLazyListState()
    val session = remember(state.visible) { SyncProgressDisplaySession() }
    val origin = remember(session) { TimeSource.Monotonic.markNow() }
    var wake by remember(session) { mutableStateOf(0L) }
    val monotonicMillis = displayMonotonicMillis?.invoke() ?: origin.elapsedNow().inWholeMilliseconds
    val presentation = remember(state, wake) { session.project(state, monotonicMillis) }
    var detailsExpanded by remember(state.visible, state.run?.spaceId, state.run?.generation, state.run?.runId) {
        mutableStateOf(false)
    }
    val detailScroll = androidx.compose.runtime.key(
        state.visible,
        state.run?.spaceId,
        state.run?.generation,
        state.run?.runId,
    ) { rememberScrollState() }
    LaunchedEffect(session, state.visible, presentation.nextDeadlineMillis) {
        if (!state.visible) return@LaunchedEffect
        presentation.nextDeadlineMillis?.let { deadline ->
            val current = displayMonotonicMillis?.invoke() ?: origin.elapsedNow().inWholeMilliseconds
            delay((deadline - current).coerceAtLeast(1))
            wake++
        }
    }
    Column(modifier.fillMaxSize()) {
        PanelHeader(state, panel::dispatch)
        HorizontalDivider()
        when (state.page) {
            SyncPanelPage.MAIN -> MainPage(state, presentation, detailsExpanded, {
                detailsExpanded = !detailsExpanded
            }, detailScroll, panel::dispatch, onOpenFailureLog, listState, Modifier.weight(1f))
            SyncPanelPage.SETTINGS -> SettingsPage(state, panel::dispatch, Modifier.weight(1f))
            SyncPanelPage.DIAGNOSTICS -> DiagnosticPage(state, panel::dispatch, onOpenDiagnostic, Modifier.weight(1f))
            SyncPanelPage.HISTORY -> RecordsPage(state, Modifier.weight(1f))
            SyncPanelPage.SETUP -> SetupPage(
                state,
                presentation,
                detailsExpanded,
                { detailsExpanded = !detailsExpanded },
                detailScroll,
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
                        when (question) {
                            SyncPanelQuestion.DISCONNECT -> MR.strings.sync_disconnect
                            SyncPanelQuestion.SWITCH_SPACE -> MR.strings.sync_switch
                            SyncPanelQuestion.ABANDON_LEGACY -> MR.strings.sync_setup_abandon_legacy_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    syncString(
                        when (question) {
                            SyncPanelQuestion.DISCONNECT -> MR.strings.sync_disconnect_body
                            SyncPanelQuestion.SWITCH_SPACE -> MR.strings.sync_switch_body
                            SyncPanelQuestion.ABANDON_LEGACY -> MR.strings.sync_setup_abandon_legacy_body
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
            IconButton({ dispatch(SyncPanelAction.Back) }, Modifier.size(48.dp).testTag("sync-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, syncString(MR.strings.sync_back))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                syncString(
                    when (state.page) {
                        SyncPanelPage.SETTINGS -> MR.strings.sync_settings
                        SyncPanelPage.HISTORY -> MR.strings.sync_records
                        SyncPanelPage.DIAGNOSTICS -> MR.strings.sync_diagnostics
                        else -> MR.strings.sync_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.page == SyncPanelPage.MAIN && state.deviceName.isNotBlank()) {
                Text(
                    syncString(MR.strings.sync_space_subtitle, state.deviceName),
                    Modifier.testTag("sync-space-subtitle"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.page == SyncPanelPage.MAIN) {
            IconButton(
                { dispatch(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS)) },
                Modifier.size(48.dp).testTag("sync-settings"),
            ) {
                Icon(Icons.Outlined.Settings, syncString(MR.strings.sync_settings))
            }
        }
        IconButton({ dispatch(SyncPanelAction.Close) }, Modifier.size(48.dp).testTag("sync-close")) {
            Icon(Icons.Outlined.Close, syncString(MR.strings.sync_close))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainPage(
    state: SyncPanelState,
    presentation: SyncProgressPresentation,
    detailsExpanded: Boolean,
    toggleDetails: () -> Unit,
    detailScroll: androidx.compose.foundation.ScrollState,
    dispatch: (SyncPanelAction) -> Unit,
    onOpenFailureLog: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier,
) {
    val continuingSetup = state.setupStep !in setOf(SyncSetupStep.SIGN_IN, SyncSetupStep.COMPLETE)
    LazyColumn(state = listState, modifier = modifier.testTag("sync-pending-list")) {
        item("summary") {
            if (state.run == null && !state.busy) {
                SyncStatusHeader(state, continuingSetup, dispatch)
                SyncQueueSummary(state)
            }
            if (state.run != null || state.busy) {
                SyncProgressCard(
                    state,
                    presentation,
                    detailsExpanded,
                    toggleDetails,
                    detailScroll,
                    dispatch,
                    onOpenFailureLog,
                )
            }
            if (state.run == null && state.problem == SyncRunProblem.AUTHORIZATION) {
                Action("sync-reconnect", MR.strings.sync_reconnect) { dispatch(SyncPanelAction.Authorize) }
            }
            if (state.run == null && state.setupProblem != SyncDiscoveryProblem.INCOMPATIBLE &&
                (state.problem == SyncRunProblem.STORAGE || state.problem == SyncRunProblem.INVALID_DATA)
            ) {
                Action("sync-reenter-password", MR.strings.sync_password_connect) {
                    dispatch(SyncPanelAction.BeginSetup)
                }
            }
            state.notice?.takeIf {
                it.bulk != null || (it.exchange != null && state.run == null)
            }?.let { notice ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        notice.exchange?.takeIf { state.run == null }?.let { result ->
                            if (
                                result.status == SyncRunStatus.SUCCESS || result.status == SyncRunStatus.PARTIAL
                            ) {
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
                            if (
                                result.status == SyncRunStatus.PARTIAL && result.pending > 0 && result.problem == null
                            ) {
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
                            } else if (
                                result.status == SyncRunStatus.FAILED || result.status == SyncRunStatus.PARTIAL
                            ) {
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
            if (state.run == null && state.importRemaining > 0) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(syncString(MR.strings.sync_import_remaining, state.importRemaining))
                    if (state.importPaused) {
                        Action("sync-resume-import", MR.strings.sync_resume) { dispatch(SyncPanelAction.ResumeImport) }
                    } else {
                        Action("sync-pause-import", MR.strings.sync_pause) { dispatch(SyncPanelAction.PauseImport) }
                    }
                }
            }
            state.bulk?.takeIf { state.run == null && it.remaining > 0 }?.let { bulk ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(syncString(MR.strings.sync_bulk, bulk.completed, bulk.remaining, bulk.skipped, bulk.failed))
                    if (bulk.running) {
                        Action("sync-pause-bulk", MR.strings.sync_pause) { dispatch(SyncPanelAction.PauseBulk) }
                    } else {
                        Action("sync-resume-bulk", MR.strings.sync_resume) { dispatch(SyncPanelAction.ResumeBulk) }
                    }
                }
            }
        }
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
    LaunchedEffect(listState, state.hasMore, state.loading, state.pending.size) {
        if (!state.hasMore || state.loading) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= state.pending.size - 3 }
            .collect { dispatch(SyncPanelAction.LoadMore) }
    }
}

private data class SyncConnectionOperation(
    val label: StringResource,
    val action: SyncPanelAction,
    val enabled: Boolean = true,
)

/** Connection recovery is shared by the empty header and a historical result card. */
private fun connectionOperation(state: SyncPanelState): SyncConnectionOperation? = when {
    !state.loaded -> SyncConnectionOperation(
        if (state.problem == null) MR.strings.sync_checking_connection else MR.strings.sync_retry_connection_check,
        SyncPanelAction.Open,
        enabled = state.problem != null,
    )
    state.connection?.unsupportedFormat == true ||
        (state.connection?.enabled != true && state.setupProblem == SyncDiscoveryProblem.INCOMPATIBLE) ->
        SyncConnectionOperation(MR.strings.sync_connection_problem, SyncPanelAction.BeginSetup)
    state.connection?.enabled != true ->
        SyncConnectionOperation(MR.strings.sync_connect_space, SyncPanelAction.BeginSetup)
    else -> null
}

@Composable
private fun SyncStatusHeader(
    state: SyncPanelState,
    continuingSetup: Boolean,
    dispatch: (SyncPanelAction) -> Unit,
) {
    val operation = connectionOperation(state)
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
                    operation?.action ?: if (continuingSetup) {
                        SyncPanelAction.BeginSetup
                    } else {
                        SyncPanelAction.Synchronize
                    },
                )
            },
            enabled = operation?.enabled ?: (
                (!state.busy && state.run?.state != SyncRunState.PAUSED_USER) || continuingSetup
                ),
            modifier = Modifier.testTag("sync-now"),
        ) {
            Text(
                syncString(
                    when {
                        operation != null && (
                            !state.loaded || state.setupProblem == SyncDiscoveryProblem.INCOMPATIBLE ||
                                state.connection?.unsupportedFormat == true
                            ) -> operation.label
                        continuingSetup -> MR.strings.sync_setup_continue
                        operation != null -> operation.label
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
            Action("sync-settings-diagnostics", MR.strings.sync_diagnostics) {
                dispatch(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
            }
        }
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
private fun DiagnosticPage(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    onOpenDiagnostic: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier.padding(24.dp).testTag("sync-diagnostics-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(syncString(MR.strings.sync_diagnostic_description)) }
        item {
            Action("sync-diagnostic-capture", MR.strings.sync_diagnostic_capture, !state.diagnosticBusy) {
                dispatch(SyncPanelAction.CaptureDiagnostics)
            }
            if (state.diagnosticBusy) Text(syncString(MR.strings.sync_diagnostic_busy))
            state.diagnosticFeedback?.let { feedback ->
                Text(
                    syncString(
                        when (feedback) {
                            SyncDiagnosticFeedback.CAPTURED -> MR.strings.sync_diagnostic_captured
                            SyncDiagnosticFeedback.READ_FAILED -> MR.strings.sync_diagnostic_read_failed
                            SyncDiagnosticFeedback.INCONSISTENT -> MR.strings.sync_diagnostic_inconsistent
                            SyncDiagnosticFeedback.EXPORTED -> MR.strings.sync_diagnostic_exported
                            SyncDiagnosticFeedback.SAVE_FAILED -> MR.strings.sync_diagnostic_save_failed
                            SyncDiagnosticFeedback.SESSION_STARTED -> MR.strings.sync_diagnostic_session_started
                            SyncDiagnosticFeedback.SESSION_ENDED -> MR.strings.sync_diagnostic_session_ended
                        },
                    ),
                    Modifier.testTag("sync-diagnostic-feedback"),
                )
            }
        }
        item {
            Text(syncString(MR.strings.sync_diagnostic_session_description))
            val comparable = state.diagnosticSnapshot?.crossProcessComparable == true
            Action(
                "sync-diagnostic-session",
                if (comparable) {
                    MR.strings.sync_diagnostic_session_end
                } else {
                    MR.strings.sync_diagnostic_session_start
                },
                !state.diagnosticBusy,
            ) {
                dispatch(
                    if (comparable) SyncPanelAction.EndDiagnosticSession else SyncPanelAction.BeginDiagnosticSession,
                )
            }
        }
        state.diagnosticSnapshot?.let { snapshot ->
            item {
                Text(
                    syncString(
                        when {
                            snapshot.status != SyncDiagnosticStatus.OK -> MR.strings.sync_diagnostic_unknown
                            snapshot.connection.panelConnectionEnabled == true -> MR.strings.sync_diagnostic_connected
                            else -> MR.strings.sync_diagnostic_disconnected
                        },
                    ),
                )
                Text(
                    syncString(
                        if (snapshot.coordinatorRunning) {
                            MR.strings.sync_diagnostic_running
                        } else {
                            MR.strings.sync_diagnostic_idle
                        },
                    ),
                )
            }
            item {
                Action("sync-diagnostic-export", MR.strings.sync_diagnostic_export, !state.diagnosticBusy) {
                    dispatch(SyncPanelAction.ExportDiagnostics)
                }
                state.diagnosticPath?.let { path ->
                    Action("sync-diagnostic-open", MR.strings.sync_diagnostic_open, !state.diagnosticBusy) {
                        onOpenDiagnostic(path)
                    }
                }
            }
            item {
                Text(
                    snapshot.json(),
                    Modifier.testTag("sync-diagnostic-details"),
                    style = MaterialTheme.typography.bodySmall,
                )
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
    presentation: SyncProgressPresentation,
    detailsExpanded: Boolean,
    toggleDetails: () -> Unit,
    detailScroll: androidx.compose.foundation.ScrollState,
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
        state.setupInstallation?.let(::installationScopeWarning)?.let { warning ->
            item {
                Text(syncString(warning), Modifier.testTag("sync-installation-scope-warning"))
            }
        }
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
                item {
                    SyncProgressCard(
                        state,
                        presentation,
                        detailsExpanded,
                        toggleDetails,
                        detailScroll,
                        dispatch,
                        onOpenFailureLog,
                        horizontalPadding = 0.dp,
                    )
                }
            }
            SyncSetupStep.COMPLETE -> { }
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
                if (state.legacyRecoveryAvailable) {
                    item {
                        Text(
                            syncString(MR.strings.sync_setup_legacy_recovery),
                            Modifier.testTag("sync-legacy-recovery"),
                        )
                    }
                    item {
                        Action("sync-abandon-legacy", MR.strings.sync_setup_abandon_legacy, !state.setupBusy) {
                            dispatch(SyncPanelAction.Ask(SyncPanelQuestion.ABANDON_LEGACY))
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
private fun SyncProgressCard(
    state: SyncPanelState,
    presentation: SyncProgressPresentation,
    detailsExpanded: Boolean,
    toggleDetails: () -> Unit,
    detailScroll: androidx.compose.foundation.ScrollState,
    dispatch: (SyncPanelAction) -> Unit,
    onOpenFailureLog: (String) -> Unit,
    horizontalPadding: Dp = 24.dp,
) {
    val run = state.run
    val fact = presentation.fact
    val terminal = run?.state in TERMINAL_STATES
    val historical = state.showingHistoricalResult
    val confirmedLabel = if (historical) {
        MR.strings.sync_last_confirmed
    } else {
        MR.strings.sync_items_confirmed_this_run
    }
    val operationHeight = syncMainOperationHeight()
    val triggerFocus = remember { FocusRequester() }
    var detailsHaveFocus by remember { mutableStateOf(false) }
    val reducedMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 16.dp).testTag("sync-progress-card"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.padding(18.dp)) {
            val fontScale = LocalDensity.current.fontScale
            val confirmedHeight = reservedTextHeight(
                listOf(
                    syncString(MR.strings.sync_items_confirmed_this_run, Long.MAX_VALUE),
                    syncString(MR.strings.sync_last_confirmed, Long.MAX_VALUE),
                    syncString(MR.strings.sync_confirmed_checking),
                ),
                constraints.maxWidth,
                MaterialTheme.typography.bodyLarge,
            )
            val actionHeight = reservedTextHeight(
                listOf(
                    MR.strings.sync_stage_prepare, MR.strings.sync_exchanging_data, MR.strings.sync_phase_uploading,
                    MR.strings.sync_phase_downloading, MR.strings.sync_checking_github_saved,
                    MR.strings.sync_merging_received_data, MR.strings.sync_receiving_and_verifying,
                    MR.strings.sync_pausing_save, MR.strings.sync_recovering_progress,
                    MR.strings.sync_waiting_network, MR.strings.sync_waiting_system, MR.strings.sync_waiting_retry,
                    MR.strings.sync_waiting_rate_limit, MR.strings.sync_paused, MR.strings.sync_wait_start,
                    MR.strings.sync_terminal_partial, MR.strings.sync_not_completed, MR.strings.sync_blocked,
                    MR.strings.sync_phase_complete, MR.strings.sync_terminal_cancelled,
                    MR.strings.sync_confirmed_retained,
                ).map { syncString(it) },
                constraints.maxWidth,
                MaterialTheme.typography.bodyMedium,
            )
            val etaCandidates = listOf(
                syncEtaDuration(20),
                syncEtaDuration(Long.MAX_VALUE - 30),
                syncString(MR.strings.sync_eta_unknown),
                syncString(MR.strings.sync_eta_unavailable),
            )
            val elapsedHeight = reservedTextHeight(
                listOf(
                    syncString(MR.strings.sync_elapsed, "153722867280912:59"),
                    syncString(MR.strings.sync_last_elapsed, "153722867280912:59"),
                ),
                constraints.maxWidth,
                MaterialTheme.typography.bodyLarge,
            )
            val etaHeight = if (fontScale > 1.4f) {
                reservedTextHeight(
                    listOf(syncString(MR.strings.sync_eta_remaining)),
                    constraints.maxWidth,
                    MaterialTheme.typography.bodyLarge,
                ) +
                    reservedTextHeight(etaCandidates, constraints.maxWidth, MaterialTheme.typography.bodyLarge)
            } else {
                reservedTextHeight(
                    etaCandidates.map { "${syncString(MR.strings.sync_eta_remaining)} · $it" },
                    constraints.maxWidth,
                    MaterialTheme.typography.bodyLarge,
                )
            }
            val explanationHeight = reservedTextHeight(
                listOf(
                    MR.strings.sync_no_progress_minute, MR.strings.sync_waiting_step_generic,
                    MR.strings.sync_import_paused_summary, MR.strings.sync_bulk_paused_summary,
                    MR.strings.sync_retry_exhausted, MR.strings.sync_projection_pending,
                    MR.strings.sync_pending_decisions, MR.strings.sync_additional_work,
                    MR.strings.sync_running_explanation, MR.strings.sync_retry_ready,
                    MR.strings.sync_connection_disconnected, MR.strings.sync_connection_owned_run,
                    MR.strings.sync_setup_incompatible,
                ).map { syncString(it) } + listOf(
                    syncString(MR.strings.sync_pending_decisions_count, Int.MAX_VALUE),
                    syncString(MR.strings.sync_retry_after_seconds, 59),
                    syncString(MR.strings.sync_retry_after_minutes, Long.MAX_VALUE / 60000 + 1),
                ),
                constraints.maxWidth,
                MaterialTheme.typography.bodySmall,
            ) + 48.dp
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 380.dp || fontScale > 1.4f) {
                        Column {
                            Text(
                                syncString(presentation.status),
                                Modifier.fillMaxWidth().height(
                                    (64 * fontScale).dp,
                                ).testTag("sync-progress-status").semantics {
                                    liveRegion =
                                        LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Box(
                                Modifier.fillMaxWidth().height(operationHeight),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                SyncMainOperation(state, presentation, detailsExpanded, toggleDetails, dispatch)
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(maxOf((64 * fontScale).dp, operationHeight)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                syncString(presentation.status),
                                Modifier.weight(1f).testTag("sync-progress-status").semantics {
                                    liveRegion =
                                        LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            SyncMainOperation(state, presentation, detailsExpanded, toggleDetails, dispatch)
                        }
                    }
                }
                Text(
                    presentation.confirmed?.let {
                        syncString(confirmedLabel, it)
                    }
                        ?: syncString(MR.strings.sync_confirmed_checking),
                    Modifier.fillMaxWidth().height(confirmedHeight).testTag("sync-confirmed-count"),
                )
                Text(
                    syncString(presentation.action),
                    Modifier.fillMaxWidth().height(actionHeight).testTag("sync-progress-action"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val progressDescription = presentation.fraction?.let {
                    syncString(
                        MR.strings.sync_transfer_percent,
                        (
                            it *
                                100
                            ).toInt(),
                    )
                }.orEmpty()
                val progressModifier = Modifier.fillMaxWidth().testTag("sync-progress").semantics {
                    stateDescription = progressDescription
                }
                if (presentation.fraction != null) {
                    LinearProgressIndicator(progress = { presentation.fraction }, modifier = progressModifier)
                } else if (presentation.active && !reducedMotion) {
                    LinearProgressIndicator(progressModifier)
                } else {
                    LinearProgressIndicator(progress = {
                        if (run?.state ==
                            SyncRunState.SUCCEEDED
                        ) {
                            1f
                        } else {
                            0f
                        }
                    }, modifier = progressModifier)
                }
                val elapsedSeconds =
                    (
                        (if (terminal) run?.updatedAt ?: state.nowMillis else state.nowMillis) -
                            (run?.createdAt ?: state.nowMillis)
                        ).coerceAtLeast(0) /
                        1000
                val elapsed = "${(elapsedSeconds / 60).toString().padStart(
                    2,
                    '0',
                )}:${(elapsedSeconds % 60).toString().padStart(2, '0')}"
                val etaValue = when {
                    terminal -> syncString(MR.strings.sync_eta_none)
                    presentation.wholeEta != null -> syncEtaDuration(presentation.wholeEta)
                    presentation.estimating -> syncString(MR.strings.sync_eta_unknown)
                    else -> syncString(MR.strings.sync_eta_unavailable)
                }
                Column(Modifier.fillMaxWidth().height(elapsedHeight + etaHeight)) {
                    Text(syncString(if (historical) MR.strings.sync_last_elapsed else MR.strings.sync_elapsed, elapsed))
                    if (fontScale > 1.4f) {
                        Text(syncString(MR.strings.sync_eta_remaining))
                        Text(etaValue, Modifier.fillMaxWidth().testTag("sync-whole-eta-value"))
                    } else {
                        Text(
                            "${syncString(MR.strings.sync_eta_remaining)} · $etaValue",
                            Modifier.fillMaxWidth().testTag("sync-whole-eta-value"),
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().height(explanationHeight)) {
                    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                        when {
                            state.connection?.unsupportedFormat == true ||
                                state.setupProblem == SyncDiscoveryProblem.INCOMPATIBLE ->
                                Text(syncString(MR.strings.sync_setup_incompatible))
                            run?.state == SyncRunState.WAITING_RETRY -> Text(
                                retryLabel(run, state.nowMillis),
                                Modifier.testTag("sync-retry-countdown"),
                            )
                            state.connection?.enabled != true -> Text(
                                syncString(
                                    if (terminal) {
                                        MR.strings.sync_connection_disconnected
                                    } else {
                                        MR.strings.sync_connection_owned_run
                                    },
                                ),
                            )
                            presentation.idleSeconds?.let {
                                it >= 60
                            } == true -> Text(syncString(MR.strings.sync_no_progress_minute))
                            presentation.idleSeconds?.let {
                                it >= 10
                            } == true -> Text(syncString(MR.strings.sync_waiting_step_generic))
                            state.importPaused && state.importRemaining > 0 -> Text(
                                syncString(MR.strings.sync_import_paused_summary),
                            )
                            state.bulk?.let {
                                !it.running && it.remaining > 0
                            } == true -> Text(syncString(MR.strings.sync_bulk_paused_summary))
                            run?.state == SyncRunState.FAILED && run.stopReason == "retry_exhausted" -> Text(
                                syncString(MR.strings.sync_retry_exhausted),
                            )
                            run?.state == SyncRunState.BLOCKED ||
                                (
                                    run?.state in setOf(
                                        SyncRunState.FAILED,
                                        SyncRunState.PARTIAL,
                                    ) && state.problem != null
                                    ) -> Text(
                                problemText(state.problem ?: SyncRunProblem.UNKNOWN),
                                Modifier.testTag("sync-blocked-reason"),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            run?.state == SyncRunState.PARTIAL -> Text(
                                syncString(
                                    when {
                                        run.stopReason == "projection_pending" -> MR.strings.sync_projection_pending
                                        state.pendingTotal > 0 -> MR.strings.sync_pending_decisions_count
                                        else -> MR.strings.sync_pending_decisions
                                    },
                                    *if (state.pendingTotal > 0 &&
                                        run.stopReason != "projection_pending"
                                    ) {
                                        arrayOf(state.pendingTotal)
                                    } else {
                                        emptyArray()
                                    },
                                ),
                            )
                            fact?.additionalWork == true -> Text(syncString(MR.strings.sync_additional_work))
                            presentation.active -> Text(
                                syncString(MR.strings.sync_running_explanation),
                                Modifier.fillMaxWidth().testTag("sync-running-explanation"),
                            )
                            else -> Text("—")
                        }
                    }
                    if ((state.importPaused && state.importRemaining > 0) ||
                        state.bulk?.let { !it.running && it.remaining > 0 } == true
                    ) {
                        Action("sync-view-processing", MR.strings.sync_view_processing) {
                            if (!detailsExpanded) toggleDetails()
                        }
                    }
                }
                TextButton({
                    toggleDetails()
                    if (detailsExpanded &&
                        detailsHaveFocus
                    ) {
                        triggerFocus.requestFocus()
                    }
                }, Modifier.testTag("sync-progress-details-toggle").focusRequester(triggerFocus)) {
                    Text(
                        syncString(
                            if (detailsExpanded) MR.strings.sync_details_collapse else MR.strings.sync_details_expand,
                        ),
                    )
                }
                if (terminal && run != null) {
                    val summary = state.terminalSummary?.takeIf { it.runId == run.runId }
                    summary?.takeIf { it.pendingDownloadBatches > 0 }?.let {
                        Text(
                            syncString(
                                MR.strings.sync_terminal_pending_receipts,
                                it.pendingDownloadBatches,
                                it.pendingDownloadEvents,
                            ),
                        )
                        Text(syncString(MR.strings.sync_terminal_pending_explanation))
                    }
                    summary?.takeIf { it.sourceUnavailableFields > 0 }?.let {
                        Text(syncString(MR.strings.sync_terminal_source_fields, it.sourceUnavailableFields))
                        Text(syncString(MR.strings.sync_terminal_source_retry))
                    }
                    when (val log = state.failureLog?.takeIf { it.runId == run.runId }) {
                        is SyncFailureLogStatus.Ready -> {
                            Text(syncString(MR.strings.sync_failure_log_count, log.failedEntries))
                            Action("sync-failure-log-open", MR.strings.sync_failure_log_open) {
                                onOpenFailureLog(log.path)
                            }
                        }
                        is SyncFailureLogStatus.SaveFailed -> Text(
                            if (log.failedEntries >
                                0
                            ) {
                                syncString(MR.strings.sync_failure_log_save_failed, log.failedEntries)
                            } else {
                                syncString(MR.strings.sync_failure_log_save_failed_unknown)
                            },
                        )
                        null -> Unit
                    }
                }
                if (detailsExpanded) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(detailScroll).onFocusChanged {
                            detailsHaveFocus =
                                it.hasFocus
                        }.focusGroup().testTag("sync-progress-details"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (run?.state == SyncRunState.BLOCKED ||
                            (run?.state in setOf(SyncRunState.FAILED, SyncRunState.PARTIAL) && state.problem != null)
                        ) {
                            Text(
                                problemText(state.problem ?: SyncRunProblem.UNKNOWN),
                                Modifier.testTag("sync-full-reason"),
                            )
                        }
                        if (fact != null && run != null &&
                            !terminal
                        ) {
                            SyncStageProgress(
                                run,
                                fact.copy(secondsWithoutProgress = presentation.idleSeconds),
                                presentation.stages,
                            )
                        }
                        if (run != null && fact == null && !terminal) {
                            Text(
                                syncString(
                                    MR.strings.sync_progress_detail,
                                    run.completed,
                                    run.skipped,
                                    run.failed,
                                    (run.total - run.completed - run.skipped - run.failed).coerceAtLeast(0),
                                ),
                                Modifier.testTag("sync-progress-detail"),
                            )
                        }
                        SyncQueueSummary(state)
                        if (state.importRemaining > 0) {
                            Text(syncString(MR.strings.sync_import_remaining, state.importRemaining))
                            Action(
                                if (state.importPaused) "sync-resume-import" else "sync-pause-import",
                                if (state.importPaused) MR.strings.sync_resume else MR.strings.sync_pause,
                            ) {
                                dispatch(
                                    if (state.importPaused) {
                                        SyncPanelAction.ResumeImport
                                    } else {
                                        SyncPanelAction.PauseImport
                                    },
                                )
                            }
                        }
                        state.bulk?.takeIf { it.remaining > 0 }?.let { bulk ->
                            Text(
                                syncString(
                                    MR.strings.sync_bulk,
                                    bulk.completed,
                                    bulk.remaining,
                                    bulk.skipped,
                                    bulk.failed,
                                ),
                            )
                            Action(
                                if (bulk.running) "sync-pause-bulk" else "sync-resume-bulk",
                                if (bulk.running) MR.strings.sync_pause else MR.strings.sync_resume,
                            ) {
                                dispatch(if (bulk.running) SyncPanelAction.PauseBulk else SyncPanelAction.ResumeBulk)
                            }
                        }
                        (state.failureLog as? SyncFailureLogStatus.Ready)?.takeIf {
                            it.runId == run?.runId && terminal
                        }?.let {
                            Text(
                                it.path,
                                Modifier.testTag("sync-failure-log-path").clickable { onOpenFailureLog(it.path) },
                            )
                        }
                        state.logs.filter { it.runId == run?.runId }.forEach { log ->
                            androidx.compose.runtime.key(log.runId, log.key) {
                                Column(Modifier.testTag("sync-log-${log.key}")) {
                                    Text(log.title)
                                    Text(log.detail)
                                }
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
    }
}

/** Measure the finite resource set once per layout configuration, never from the current number. */
@Composable
private fun reservedTextHeight(texts: List<String>, width: Int, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, width, style, density) {
        val pixels = texts.maxOf {
            measurer.measure(it, style = style, constraints = Constraints(maxWidth = width)).size.height
        }
        with(density) { pixels.toDp() + 1.dp }
    }
}

/** All operation labels reserve one stable slot for this width, font scale and locale. */
@Composable
private fun syncMainOperationHeight(): Dp {
    val padding = ButtonDefaults.TextButtonContentPadding
    val direction = LocalLayoutDirection.current
    val width = with(LocalDensity.current) {
        (168.dp - padding.calculateLeftPadding(direction) - padding.calculateRightPadding(direction)).roundToPx()
    }
    val labels = listOf(
        MR.strings.sync_resume_run, MR.strings.sync_retry_run, MR.strings.sync_reconnect,
        MR.strings.sync_password_connect, MR.strings.sync_view_reason, MR.strings.sync_now,
        MR.strings.sync_pause_run, MR.strings.sync_pausing_save, MR.strings.sync_wait_start,
        MR.strings.sync_connect_space, MR.strings.sync_connection_problem,
        MR.strings.sync_checking_connection, MR.strings.sync_retry_connection_check,
    ).map { syncString(it) }
    return maxOf(
        48.dp,
        reservedTextHeight(labels, width, MaterialTheme.typography.labelLarge) +
            padding.calculateTopPadding() + padding.calculateBottomPadding(),
    )
}

@Composable
private fun SyncMainOperation(
    state: SyncPanelState,
    presentation: SyncProgressPresentation,
    detailsExpanded: Boolean,
    toggleDetails: () -> Unit,
    dispatch: (SyncPanelAction) -> Unit,
) {
    val run = state.run
    val fact = presentation.fact
    val terminal = run?.state in TERMINAL_STATES
    val operation = connectionOperation(state)
    val operationHeight = syncMainOperationHeight()
    val operationShape = MaterialTheme.shapes.small
    Box(Modifier.width(168.dp).height(operationHeight), contentAlignment = Alignment.CenterEnd) {
        when {
            terminal && state.busy -> Action(
                "sync-wait",
                MR.strings.sync_wait_start,
                false,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {}
            terminal && operation != null -> Action(
                if (state.connection?.unsupportedFormat == true) "sync-view-reason" else "sync-now",
                operation.label,
                operation.enabled,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {
                dispatch(operation.action)
            }
            run?.state == SyncRunState.PAUSED_USER -> Action(
                "sync-resume-run",
                MR.strings.sync_resume_run,
                state.connection?.enabled == true,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {
                dispatch(SyncPanelAction.ResumeSync)
            }
            run?.state == SyncRunState.FAILED || run?.state == SyncRunState.PARTIAL -> Action(
                "sync-retry-run",
                MR.strings.sync_retry_run,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {
                dispatch(SyncPanelAction.RetrySync)
            }
            run?.state == SyncRunState.BLOCKED -> when (state.problem) {
                SyncRunProblem.AUTHORIZATION -> Action(
                    "sync-reconnect",
                    MR.strings.sync_reconnect,
                    shape = operationShape,
                    modifier = Modifier.fillMaxWidth().height(operationHeight),
                ) {
                    dispatch(SyncPanelAction.Authorize)
                }
                SyncRunProblem.STORAGE, SyncRunProblem.INVALID_DATA -> if (
                    state.setupProblem != SyncDiscoveryProblem.INCOMPATIBLE
                ) {
                    Action(
                        "sync-reenter-password",
                        MR.strings.sync_password_connect,
                        shape = operationShape,
                        modifier = Modifier.fillMaxWidth().height(operationHeight),
                    ) {
                        dispatch(SyncPanelAction.BeginSetup)
                    }
                } else {
                    Action(
                        "sync-view-reason",
                        MR.strings.sync_view_reason,
                        shape = operationShape,
                        modifier = Modifier.fillMaxWidth().height(operationHeight),
                    ) {
                        if (!detailsExpanded) toggleDetails()
                    }
                }
                else -> Action(
                    "sync-view-reason",
                    MR.strings.sync_view_reason,
                    shape = operationShape,
                    modifier = Modifier.fillMaxWidth().height(operationHeight),
                ) {
                    if (!detailsExpanded) toggleDetails()
                }
            }
            terminal -> Action(
                "sync-now",
                MR.strings.sync_now,
                state.connection?.enabled == true,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {
                dispatch(SyncPanelAction.Synchronize)
            }
            presentation.active -> Action(
                "sync-pause-run",
                MR.strings.sync_pause_run,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {
                dispatch(SyncPanelAction.PauseSync)
            }
            else -> Action(
                "sync-wait",
                if (fact?.hold ==
                    SyncProgressHold.PAUSING
                ) {
                    MR.strings.sync_pausing_save
                } else {
                    MR.strings.sync_wait_start
                },
                false,
                shape = operationShape,
                modifier = Modifier.fillMaxWidth().height(operationHeight),
            ) {}
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
private fun SyncStageProgress(
    run: SyncRunSnapshot,
    fact: SyncProgressFact,
    stages: Map<SyncProgressStage, Pair<Long, Long?>>,
) {
    val stageResources = listOf(
        MR.strings.sync_stage_prepare,
        MR.strings.sync_stage_transfer,
        MR.strings.sync_stage_confirm,
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        stageResources.forEachIndexed { index, label ->
            val observation = stages[SyncProgressStage.entries[index]]
            Text(
                "${syncString(label)} · ${observation?.let { (count, total) ->
                    if (total != null) "$count / $total" else count.toString()
                } ?: syncString(MR.strings.sync_counting_data)}",
                style = MaterialTheme.typography.bodySmall,
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
        fact.receivedItems?.let { received ->
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
        Text(syncString(MR.strings.sync_fields_checked, fact.checkedFields), Modifier.testTag("sync-checked-count"))
    }
    if (fact.unavailableFields > 0L && run.state == SyncRunState.RUNNING) {
        Text(syncString(MR.strings.sync_source_unavailable_progress))
    }
    val fraction = when (fact.stage) {
        SyncProgressStage.TRANSFERRING -> fact.totalBytes?.takeIf { it > 0L }
            ?.takeIf { fact.effectiveBytes in 0..it }?.let { fact.effectiveBytes.toFloat() / it }
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
    if (fact.stage == SyncProgressStage.TRANSFERRING && fraction != null) Text(progressDescription)
    val bodyTotal = fact.activeBodyTotal
    val bodyBytes = fact.activeBodyBytes
    if (bodyTotal != null && bodyTotal > 0 && bodyBytes != null && bodyBytes in 0..bodyTotal) {
        Text(syncString(MR.strings.sync_active_body_percent, (bodyBytes * 100 / bodyTotal).toInt()))
    }
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
        val stageEtaSeconds = fact.stageEtaSeconds?.takeIf { it >= 0 }
        val bodyEtaSeconds = fact.activeBodyEtaSeconds?.takeIf { it >= 0 }
        when {
            idleSeconds != null && idleSeconds >= 10L -> Text(syncString(MR.strings.sync_eta_unavailable))
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
private fun Action(
    tag: String,
    label: StringResource,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    onClick: () -> Unit,
) {
    if (shape == null) {
        TextButton(onClick, modifier.testTag(tag), enabled = enabled) { Text(syncString(label)) }
    } else {
        TextButton(onClick, modifier.testTag(tag), enabled = enabled, shape = shape) { Text(syncString(label)) }
    }
}
