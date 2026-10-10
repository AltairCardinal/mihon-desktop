package mihon.presentation.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.text.style.TextAlign
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
import mihon.data.sync.runtime.SyncRecoveryAction
import mihon.data.sync.runtime.SyncRecoveryActionAvailability
import mihon.data.sync.runtime.SyncRecoveryActionDecision
import mihon.data.sync.runtime.SyncRecoveryAuthorization
import mihon.data.sync.runtime.SyncRecoveryContinuation
import mihon.data.sync.runtime.SyncRecoveryOutcome
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncRecoveryPlatformRequest
import mihon.data.sync.runtime.SyncRecoveryPlatformResult
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncSpaceRecoveryReason
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncNetworkFailurePhase
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncRepositoryTarget
import tachiyomi.i18n.MR
import kotlin.time.TimeSource

@Composable
fun SyncToolbarButton(state: SyncPanelState, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val busyDescription = if (state.busy) syncString(MR.strings.sync_busy) else ""
    Box(Modifier.size(48.dp)) {
        IconButton(
            onClick = onOpen,
            modifier = modifier.fillMaxSize().testTag("sync-open").semantics {
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
    onOpenFailureLog: (String) -> Unit = { error("Report viewer unavailable") },
    onOpenDiagnostic: (String) -> Unit = { error("Report viewer unavailable") },
    onOpenRecoveryPlatform: ((SyncRecoveryPlatformRequest) -> Unit)? = null,
    displayMonotonicMillis: (() -> Long)? = null,
) {
    val state by panel.state.collectAsState()
    if (!state.visible) return
    // The same Compose window-focus fact is supplied by Android and Desktop. Returning is not approval.
    val focused = LocalWindowInfo.current.isWindowFocused
    var browserLaunched by remember(panel) { mutableStateOf(false) }
    var browserLeft by remember(panel) { mutableStateOf(false) }
    val dispatch: (SyncPanelAction) -> Unit = { action ->
        if (action is SyncPanelAction.RecoveryOfficialOpened) {
            // Only the confirmed official recovery launch arms return checking, not an arbitrary browser link.
            browserLaunched = true
            browserLeft = !focused
        }
        panel.dispatch(action)
    }
    LaunchedEffect(focused, state.recoveryOfficialAction, browserLaunched) {
        if (browserLaunched && !focused) browserLeft = true
        if (browserLaunched && browserLeft && focused && state.recoveryOfficialAction != null) {
            browserLaunched = false
            browserLeft = false
            panel.dispatch(SyncPanelAction.RecoveryOfficialReturned)
        }
    }
    LaunchedEffect(state.recoveryPlatformRequest?.requestId, state.recoveryPlatformLaunchPending) {
        val request = state.recoveryPlatformRequest ?: return@LaunchedEffect
        if (state.recoveryPlatformLaunchPending && panel.claimRecoveryPlatform(request.requestId)) {
            try {
                requireNotNull(onOpenRecoveryPlatform) { "Native recovery entry is unavailable" }.invoke(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                panel.dispatch(SyncPanelAction.RecoveryPlatformFailed(request.requestId))
            }
        }
    }
    var externalUnavailable by remember(state.visible) { mutableStateOf(false) }
    var failedBrowserAddress by remember(state.visible) { mutableStateOf<String?>(null) }
    fun externalAction(action: (String) -> Unit, value: String, browser: Boolean = false): Boolean {
        try {
            action(value)
            return true
        } catch (
            cancelled: kotlinx.coroutines.CancellationException,
        ) {
            throw cancelled
        } catch (_: Exception) {
            externalUnavailable = true
            if (browser && value.startsWith("https://github.com/") && value.length <= 2048 &&
                value.none { it == '\n' || it == '\r' || it == '\u0000' }
            ) {
                val sensitive = Regex(
                    "[?&](access_token|token|device_code|client_secret|authorization)=",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(value)
                failedBrowserAddress = if (sensitive) value.substringBefore('?') else value
            }
            return false
        }
    }
    val openBrowserSafely: (String) -> Boolean = { externalAction(onOpenBrowser, it, browser = true) }
    val copyCodeSafely: (String) -> Unit = { externalAction(onCopyCode, it) }
    val openFailureLogSafely: (String) -> Unit = { externalAction(onOpenFailureLog, it) }
    val openDiagnosticSafely: (String) -> Unit = { externalAction(onOpenDiagnostic, it) }
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
        PanelHeader(state, dispatch)
        HorizontalDivider()
        if (externalUnavailable) {
            Text(
                syncString(MR.strings.sync_external_fallback),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("sync-external-fallback"),
                style = MaterialTheme.typography.bodySmall,
            )
            failedBrowserAddress?.let { address ->
                Text(
                    address,
                    Modifier.padding(horizontal = 24.dp).testTag("sync-external-fallback-address"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when (state.page) {
            SyncPanelPage.MAIN -> MainPage(state, presentation, detailsExpanded, {
                detailsExpanded = !detailsExpanded
            }, detailScroll, dispatch, openFailureLogSafely, listState, Modifier.weight(1f))
            SyncPanelPage.SETTINGS -> SettingsPage(state, dispatch, Modifier.weight(1f))
            SyncPanelPage.DIAGNOSTICS -> DiagnosticPage(
                state,
                dispatch,
                openDiagnosticSafely,
                copyCodeSafely,
                Modifier.weight(1f),
            )
            SyncPanelPage.HISTORY -> RecordsPage(state, Modifier.weight(1f))
            SyncPanelPage.RECOVERY -> RecoveryPage(state, dispatch, openBrowserSafely, Modifier.weight(1f))
            SyncPanelPage.SETUP -> SetupPage(
                state,
                presentation,
                detailsExpanded,
                { detailsExpanded = !detailsExpanded },
                detailScroll,
                dispatch,
                openBrowserSafely,
                copyCodeSafely,
                panel::claimDeviceCodeBrowser,
                openFailureLogSafely,
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
                            SyncPanelQuestion.CREATE_NEW_SPACE -> MR.strings.sync_recovery_create
                            SyncPanelQuestion.CONNECT_SPACE -> MR.strings.sync_recovery_connect_confirm
                            SyncPanelQuestion.CANCEL_RECOVERY_SWITCH -> MR.strings.sync_recovery_cancel_switch
                            SyncPanelQuestion.CREATE_REPOSITORY -> MR.strings.sync_repository_create_confirm
                            SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES ->
                                MR.strings.sync_repository_properties_confirm
                            SyncPanelQuestion.AUTHORIZE_REPOSITORY_SCOPE -> MR.strings.sync_repository_scope_confirm
                            SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY -> MR.strings.sync_recovery_connect_confirm
                        },
                    ),
                )
            },
            text = {
                Column(
                    Modifier.testTag("sync-question-text").verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (question in setOf(
                            SyncPanelQuestion.CREATE_REPOSITORY,
                            SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY,
                            SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES,
                            SyncPanelQuestion.AUTHORIZE_REPOSITORY_SCOPE,
                        )
                    ) {
                        val target = state.setupRepository ?: state.connection?.repository
                        val targetName = target?.fullName ?: listOfNotNull(
                            state.setupAccountLogin,
                            state.repositoryCreationName,
                        ).joinToString("/")
                        Text(targetName)
                        if (question == SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES) {
                            if (state.repairMakePrivate) Text(syncString(MR.strings.sync_repository_private_change))
                            if (state.repairUnarchive) Text(syncString(MR.strings.sync_repository_unarchive_change))
                        }
                    }
                    if (question == SyncPanelQuestion.CONNECT_SPACE) {
                        state.switchTargetRepository?.let { target ->
                            Text(syncString(MR.strings.sync_recovery_connect_target, target.fullName))
                        }
                    } else {
                        Text(
                            syncString(
                                when (question) {
                                    SyncPanelQuestion.DISCONNECT -> MR.strings.sync_disconnect_body
                                    SyncPanelQuestion.SWITCH_SPACE -> MR.strings.sync_switch_body
                                    SyncPanelQuestion.ABANDON_LEGACY -> MR.strings.sync_setup_abandon_legacy_body
                                    SyncPanelQuestion.CREATE_NEW_SPACE -> MR.strings.sync_recovery_create_body
                                    SyncPanelQuestion.CONNECT_SPACE -> MR.strings.sync_recovery_connect_target
                                    SyncPanelQuestion.CANCEL_RECOVERY_SWITCH -> {
                                        MR.strings.sync_recovery_cancel_switch_body
                                    }
                                    SyncPanelQuestion.CREATE_REPOSITORY ->
                                        MR.strings.sync_repository_create_confirm_body
                                    SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES ->
                                        MR.strings.sync_repository_properties_confirm_body
                                    SyncPanelQuestion.AUTHORIZE_REPOSITORY_SCOPE ->
                                        MR.strings.sync_repository_scope_confirm_body
                                    SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY ->
                                        MR.strings.sync_repository_manual_confirm_body
                                },
                            ),
                        )
                    }
                    if (question == SyncPanelQuestion.CONNECT_SPACE || question == SyncPanelQuestion.CREATE_NEW_SPACE) {
                        Text(
                            syncString(MR.strings.sync_recovery_old_pending, state.switchPendingDecisions),
                            Modifier.testTag("sync-switch-pending-count"),
                        )
                    }
                }
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
                        SyncPanelPage.RECOVERY -> MR.strings.sync_recovery_choose
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

@Composable
private fun RecoverySummary(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit, modifier: Modifier) {
    val recovery = requireNotNull(state.recovery)
    LazyColumn(
        modifier.fillMaxWidth().padding(24.dp).testTag("sync-recovery-summary"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.recoveryExternalScopes.isNotEmpty() || state.recoveryOldScopes.isNotEmpty()) {
            item {
                ExternalRecoveryNotice(state, dispatch)
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("sync-recovery-card"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        syncString(
                            when (recovery.reason) {
                                SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED -> MR.strings.sync_recovery_auth_expired
                                SyncSpaceRecoveryReason.SPACE_DATA_INVALID -> MR.strings.sync_recovery_data_problem
                                SyncSpaceRecoveryReason.SPACE_UNAVAILABLE -> MR.strings.sync_recovery_title
                                SyncSpaceRecoveryReason.SWITCH_PENDING -> MR.strings.sync_recovery_switch_pending_title
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(syncString(recoveryBody(state)))
                    RecoveryFacts(state)
                    RecoveryCheckFeedback(state)
                    if (recovery.reason == SyncSpaceRecoveryReason.SWITCH_PENDING) {
                        ContinueSwitchAction(state, dispatch)
                        RecoveryCancelAction(state, dispatch)
                    } else if (recovery.reason == SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED &&
                        !state.recoveryAuthorizationConfirmed
                    ) {
                        Action(
                            "sync-recovery-authorization",
                            MR.strings.sync_reconnect,
                            enabled = state.recoveryActionsEnabled,
                            primary = true,
                        ) { dispatch(SyncPanelAction.CheckAuthorization) }
                    } else if (recovery.reason == SyncSpaceRecoveryReason.SPACE_DATA_INVALID) {
                        Action(
                            "sync-recovery-open",
                            MR.strings.sync_recovery_choose,
                            enabled = state.recoveryActionsEnabled,
                            primary = true,
                        ) {
                            dispatch(SyncPanelAction.OpenRecovery)
                        }
                    } else {
                        Action(
                            "sync-recovery-open",
                            MR.strings.sync_recovery_choose,
                            enabled = state.recoveryActionsEnabled,
                            primary = true,
                        ) { dispatch(SyncPanelAction.OpenRecovery) }
                        if (state.canChangeSpace) {
                            Action(
                                "sync-recovery-create",
                                MR.strings.sync_recovery_create,
                                enabled = state.recoveryActionsEnabled,
                            ) { dispatch(SyncPanelAction.CreateNewSpace) }
                        }
                    }
                }
            }
        }
        item {
            Text(
                syncString(recoveryWaiting(state)),
                Modifier.testTag("sync-recovery-waiting"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Action("sync-recovery-recheck", MR.strings.sync_recovery_recheck, state.recoveryActionsEnabled) {
                dispatch(SyncPanelAction.RecheckSpace)
            }
        }
        item {
            Action("sync-recovery-details", MR.strings.sync_recovery_details) {
                dispatch(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
            }
        }
    }
}

@Composable
private fun RecoveryPage(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    openBrowser: (String) -> Boolean,
    modifier: Modifier,
) {
    val reason = state.recovery?.reason
    val report = state.recoveryRepairReport
    val discovery = state.recoveryFailure?.discovery ?: state.setupProblem
    val pending = reason == SyncSpaceRecoveryReason.SWITCH_PENDING
    LazyColumn(
        modifier.fillMaxWidth().padding(24.dp).testTag("sync-recovery-page"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pending) {
                    Text(
                        syncString(MR.strings.sync_recovery_switch_pending_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (reason == null) {
                    Text(
                        syncString(MR.strings.sync_error_recovery_choice),
                        Modifier.testTag("sync-recovery-neutral"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(syncString(MR.strings.sync_error_recovery_choice_body))
                } else {
                    Text(syncString(recoveryBody(state)))
                }
                RecoveryFacts(state)
                (
                    discovery ?: SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE.takeIf {
                        reason == SyncSpaceRecoveryReason.SPACE_UNAVAILABLE
                    }
                    )?.let { Text(setupProblemText(it), Modifier.testTag("sync-recovery-cause")) }
                state.recoveryFailure?.networkPhase?.let { phase ->
                    val label = when (phase) {
                        SyncNetworkFailurePhase.DNS -> MR.strings.sync_network_phase_dns
                        SyncNetworkFailurePhase.CONNECT -> MR.strings.sync_network_phase_connect
                        SyncNetworkFailurePhase.PROXY_HANDSHAKE -> MR.strings.sync_network_phase_proxy
                        SyncNetworkFailurePhase.TLS -> MR.strings.sync_network_phase_tls
                        SyncNetworkFailurePhase.TIMEOUT -> MR.strings.sync_network_phase_timeout
                        SyncNetworkFailurePhase.HTTP_RESPONSE -> MR.strings.sync_network_phase_response
                        SyncNetworkFailurePhase.HTTP_BODY -> MR.strings.sync_network_phase_body
                        SyncNetworkFailurePhase.UNKNOWN -> MR.strings.sync_network_phase_unknown
                    }
                    Text(syncString(label), Modifier.testTag("sync-recovery-network-stage"))
                }
                if (state.recoveryRestartRequired) {
                    Text(
                        syncString(MR.strings.requires_app_restart),
                        Modifier.testTag("sync-recovery-restart-required"),
                    )
                }
                if (state.recoveryPersistenceFailed) {
                    Text(
                        syncString(MR.strings.sync_recovery_not_saved),
                        Modifier.testTag("sync-recovery-not-saved"),
                    )
                }
                state.recoveryOutcome?.let { outcome ->
                    Text(
                        syncString(
                            when (outcome) {
                                SyncRecoveryOutcome.ORIGINAL_VERIFIED -> MR.strings.sync_recovery_original_verified
                                SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING ->
                                    MR.strings.sync_recovery_new_verified
                                SyncRecoveryOutcome.REMAINING -> MR.strings.sync_recovery_still_remaining
                                SyncRecoveryOutcome.WAITING_EXTERNAL -> MR.strings.sync_recovery_waiting_external
                            },
                        ),
                        Modifier.testTag("sync-recovery-outcome"),
                    )
                }
                state.externalRecoveryOrigin?.let {
                    Text(
                        syncString(MR.strings.sync_recovery_unverified_origin, it),
                        Modifier.testTag("sync-recovery-origin"),
                    )
                }
                state.recoveryOldScopes.forEach {
                    Text(syncString(MR.strings.sync_recovery_unverified_origin, it.label))
                }
                if (state.recoveryPlatformRequest?.failed == true) {
                    Text(
                        syncString(MR.strings.sync_recovery_platform_failed),
                        Modifier.testTag("sync-recovery-platform-failed"),
                    )
                }
                state.recoveryPlatformResult?.let { result ->
                    Text(
                        syncString(
                            when (result) {
                                SyncRecoveryPlatformResult.Cancelled -> MR.strings.sync_recovery_return_cancelled
                                SyncRecoveryPlatformResult.NoChange -> MR.strings.sync_recovery_return_no_change
                                is SyncRecoveryPlatformResult.Changed -> MR.strings.sync_recovery_return_changed
                                is SyncRecoveryPlatformResult.PartialFailure -> MR.strings.sync_recovery_still_remaining
                                is SyncRecoveryPlatformResult.Failed -> MR.strings.sync_recovery_platform_failed
                                SyncRecoveryPlatformResult.RestartRequired -> MR.strings.requires_app_restart
                            },
                        ),
                        Modifier.testTag("sync-recovery-platform-result"),
                    )
                }
                state.recoveryStepFailure?.let { failure ->
                    Text(syncString(MR.strings.sync_error_retry_failed), Modifier.testTag("sync-recovery-step-failed"))
                    failure.discovery?.let { Text(setupProblemText(it)) }
                    failure.problem?.let { Text(problemText(it)) }
                }
                state.recoveryExternalScopes.forEach { scope ->
                    Text(
                        syncString(
                            if (scope.remaining == null) {
                                MR.strings.sync_recovery_backup_unknown_scope
                            } else {
                                MR.strings.sync_recovery_backup_remaining
                            },
                            scope.remaining ?: 0,
                        ),
                        Modifier.testTag("sync-recovery-external-scope-${scope.requestId}"),
                    )
                }
                if (pending) {
                    Text(
                        syncString(recoveryWaiting(state)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (reason != null) item { RecoveryCheckFeedback(state) }
        if (state.importRemaining > 0 || state.queuedTotal > 0) {
            item {
                if (state.importRemaining > 0) Text(syncString(MR.strings.sync_import_remaining, state.importRemaining))
                if (state.queuedTotal > 0) Text(syncString(MR.strings.sync_queued, state.queuedTotal))
            }
        }
        if (state.authRetryAtMillis > state.nowMillis) {
            item {
                val remainingSeconds = (state.authRetryAtMillis - state.nowMillis + 999) / 1000
                Text(syncString(MR.strings.sync_recovery_cooldown, remainingSeconds))
            }
        }
        item { RecoveryDecisionAction(state.recoveryPrimaryAction, state, dispatch, openBrowser, primary = true) }
        if (state.recoveryAlternativeActions.isNotEmpty()) {
            item {
                Text(syncString(MR.strings.sync_recovery_other_actions), style = MaterialTheme.typography.titleSmall)
            }
        }
        state.recoveryAlternativeActions.forEach { decision ->
            item { RecoveryDecisionAction(decision, state, dispatch, openBrowser) }
        }
        if (pending && state.canCancelRecoverySwitch) item { RecoveryCancelAction(state, dispatch) }
        item {
            Action("sync-recovery-details", MR.strings.sync_recovery_details) {
                dispatch(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
            }
        }
        report?.let { repair ->
            item {
                Text(
                    syncString(
                        MR.strings.sync_recovery_remaining_counts,
                        repair.counts.rejectedBatches,
                        repair.counts.projectionFields,
                        repair.counts.missingDependencies,
                        repair.counts.failedBulkItems,
                    ),
                    Modifier.testTag("sync-recovery-counts"),
                )
            }
            items(repair.batches, key = { "rejected:${it.evidenceId}:${it.batchId}" }) {
                RecoveryItemExplanation(
                    syncString(MR.strings.sync_recovery_affected_content),
                    it.reason,
                    listOf(it.batchId, it.path, recoveryReasonCode(it.reason)),
                )
            }
            items(repair.fields, key = { "failure:${it.objectIdentity}:${it.field}" }) { failure ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RecoveryItemExplanation(
                        failure.title,
                        failure.reason,
                        listOf(failure.objectIdentity, recoveryReasonCode(failure.reason)),
                    )
                    when (failure.reason) {
                        "SOURCE" -> RecoveryPlatformAction(
                            "sync-recovery-source-${failure.objectIdentity}",
                            MR.strings.sync_recovery_extensions,
                            SyncRecoveryPlatformAction.EXTENSIONS,
                            state,
                            dispatch,
                            objectKey = failure.objectKey,
                        )
                        "IDENTITY" -> RecoveryPlatformAction(
                            "sync-recovery-map-${failure.objectIdentity}",
                            if (failure.objectKey?.type == mihon.domain.sync.SyncObjectType.MANGA) {
                                MR.strings.sync_recovery_migration
                            } else {
                                MR.strings.sync_recovery_backup
                            },
                            if (failure.objectKey?.type == mihon.domain.sync.SyncObjectType.MANGA) {
                                SyncRecoveryPlatformAction.MIGRATION
                            } else {
                                SyncRecoveryPlatformAction.BACKUP
                            },
                            state,
                            dispatch,
                            objectKey = failure.objectKey,
                        )
                        "DESCRIPTION" -> RecoveryPlatformAction(
                            "sync-recovery-description-${failure.objectIdentity}",
                            MR.strings.sync_recovery_get_information,
                            SyncRecoveryPlatformAction.READER,
                            state,
                            dispatch,
                            objectKey = failure.objectKey,
                        )
                        "UNREADABLE" -> RecoveryPlatformAction(
                            "sync-recovery-unreadable-${failure.objectIdentity}",
                            MR.strings.sync_recovery_storage,
                            SyncRecoveryPlatformAction.STORAGE,
                            state,
                            dispatch,
                        )
                    }
                    if (failure.field == mihon.domain.sync.SyncField.RESUME_POSITION) {
                        RecoveryPlatformAction(
                            "sync-recovery-reader-${failure.objectIdentity}",
                            MR.strings.sync_recovery_reader,
                            SyncRecoveryPlatformAction.READER,
                            state,
                            dispatch,
                            objectKey = failure.objectKey,
                        )
                    }
                }
            }
            if (repair.counts.rejectedEvents > 0) {
                item {
                    Text(syncString(MR.strings.sync_recovery_rejected_records, repair.counts.rejectedEvents))
                }
            }
            items(repair.events, key = { "rejected-event:${it.eventId}" }) {
                RecoveryItemExplanation(
                    syncString(MR.strings.sync_recovery_affected_content),
                    it.reason,
                    listOf(it.eventId, it.batchId.orEmpty(), recoveryReasonCode(it.reason)),
                )
            }
            items(repair.missingDependencies, key = { "missing:$it" }) {
                RecoveryItemExplanation(syncString(MR.strings.sync_recovery_affected_content), "DEPENDENCY", listOf(it))
            }
            repair.failedBulkItems.groupBy { it.jobId }.forEach { (jobId, failures) ->
                item(key = "bulk:$jobId") {
                    Column {
                        failures.forEach { Text(it.title) }
                        Action(
                            "sync-recovery-bulk-$jobId",
                            MR.strings.sync_recovery_retry_failed,
                            !state.recoveryBusy,
                        ) {
                            dispatch(SyncPanelAction.RetryFailedBulk(jobId))
                        }
                    }
                }
            }
            if (repair.nextOffset != null) {
                item {
                    Action("sync-recovery-more", MR.strings.sync_recovery_more_failures, !state.recoveryBusy) {
                        dispatch(SyncPanelAction.LoadMoreRecoveryFailures)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryDecisionAction(
    decision: SyncRecoveryActionDecision,
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    openBrowser: (String) -> Boolean,
    primary: Boolean = false,
) {
    if (decision.availability == SyncRecoveryActionAvailability.NotApplicable) return
    val action = decision.action
    val setup = state.page == SyncPanelPage.SETUP
    val discovery = state.recoveryStepFailure?.discovery ?: state.setupProblem ?: state.recoveryFailure?.discovery
    val tag = when (action) {
        SyncRecoveryAction.CREATE_SPACE -> "sync-recovery-create"
        SyncRecoveryAction.CHOOSE_SPACE -> "sync-recovery-connect-other"
        SyncRecoveryAction.CONNECT_GITHUB -> if (setup) "sync-repo-reconnect" else "sync-recovery-authorization"
        SyncRecoveryAction.INSTALL_APP, SyncRecoveryAction.RESTORE_INSTALLATION -> "sync-install-app"
        SyncRecoveryAction.AUTHORIZE_REPOSITORY -> "sync-repository-authorize-native"
        SyncRecoveryAction.CHECK_CONDITIONS -> if (setup) "sync-setup-retry" else "sync-recovery-recheck"
        SyncRecoveryAction.VERIFY_SYNC -> "sync-recovery-verify"
        SyncRecoveryAction.REPAIR_REPOSITORY_PROPERTIES -> if (discovery == SyncDiscoveryProblem.REPOSITORY_ARCHIVED) {
            "sync-recovery-unarchive"
        } else {
            "sync-recovery-make-private"
        }
        SyncRecoveryAction.MANAGE_AUTHORIZATION -> if (setup) {
            "sync-install-app"
        } else {
            "sync-recovery-manage-authorization"
        }
        SyncRecoveryAction.CONTINUE_SETUP -> when {
            state.recovery?.reason == SyncSpaceRecoveryReason.SWITCH_PENDING -> "sync-recovery-continue"
            setup -> "sync-setup-retry"
            else -> "sync-recovery-continue-setup"
        }
        SyncRecoveryAction.EDIT_REPOSITORY_NAME -> "sync-recovery-edit-name"
        SyncRecoveryAction.OFFICIAL_CREATE -> "sync-create-private-repo"
        SyncRecoveryAction.RESTORE_REPOSITORY -> "sync-recovery-restore-repository"
        SyncRecoveryAction.ENABLE_SYNC -> "sync-recovery-enable"
        SyncRecoveryAction.NETWORK -> "sync-recovery-network"
        SyncRecoveryAction.STORAGE -> "sync-recovery-storage"
        SyncRecoveryAction.UPDATE -> "sync-recovery-update"
        SyncRecoveryAction.BACKUP -> "sync-recovery-backup"
        SyncRecoveryAction.EXTENSIONS -> "sync-recovery-extensions"
        SyncRecoveryAction.MIGRATION -> "sync-recovery-migration"
        SyncRecoveryAction.READER -> "sync-recovery-reader"
        SyncRecoveryAction.REPAIR_DATA -> "sync-recovery-repair"
        SyncRecoveryAction.RESUME_SYNC -> "sync-recovery-resume"
        SyncRecoveryAction.RESUME_IMPORT -> "sync-recovery-resume-import"
        SyncRecoveryAction.WAIT_EXTERNAL, SyncRecoveryAction.WAIT_SERVICE -> "sync-recovery-wait"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (primary && (
                action == SyncRecoveryAction.RESTORE_INSTALLATION ||
                    state.recoveryOfficialAction == SyncRecoveryAction.RESTORE_INSTALLATION
                )
        ) {
            Text(syncString(MR.strings.sync_recovery_installation_restriction))
        }
        if (primary && (
                action == SyncRecoveryAction.RESTORE_REPOSITORY ||
                    state.recoveryOfficialAction == SyncRecoveryAction.RESTORE_REPOSITORY
                )
        ) {
            Text(syncString(MR.strings.sync_recovery_repository_restriction))
        }
        if (state.recoveryOfficialAction != null && primary && !state.recoveryOfficialCheckAttempted) {
            Text(
                syncString(
                    if (state.recoveryOfficialAction in setOf(
                            SyncRecoveryAction.RESTORE_INSTALLATION,
                            SyncRecoveryAction.RESTORE_REPOSITORY,
                        )
                    ) {
                        MR.strings.sync_recovery_official_waiting_restricted
                    } else {
                        MR.strings.sync_recovery_official_waiting
                    },
                ),
                Modifier.testTag("sync-recovery-official-waiting"),
            )
        }
        (decision.availability as? SyncRecoveryActionAvailability.NeedsStep)?.let {
            Text(syncString(MR.strings.sync_recovery_prerequisite, syncString(recoveryActionLabel(it.step))))
        }
        if (decision.availability is SyncRecoveryActionAvailability.Waiting) {
            Text(
                syncString(
                    if (action ==
                        SyncRecoveryAction.WAIT_SERVICE
                    ) {
                        MR.strings.sync_recovery_service_wait
                    } else {
                        MR.strings.sync_recovery_waiting_external
                    },
                ),
            )
        } else {
            val nextStep = (decision.availability as? SyncRecoveryActionAvailability.NeedsStep)?.step
            Action(
                tag,
                if (action == SyncRecoveryAction.CHECK_CONDITIONS && state.recoveryOfficialAction != null) {
                    MR.strings.sync_setup_check_continue
                } else if (action == SyncRecoveryAction.MANAGE_AUTHORIZATION && state.repositoryPreparedName != null) {
                    MR.strings.sync_setup_authorize_selected_space
                } else if (action == SyncRecoveryAction.INSTALL_APP) {
                    if (state.recoveryOfficialAction == SyncRecoveryAction.INSTALL_APP) {
                        MR.strings.sync_setup_continue_install
                    } else {
                        MR.strings.sync_setup_install_authorize
                    }
                } else if (action == SyncRecoveryAction.CONTINUE_SETUP &&
                    state.recovery?.reason == SyncSpaceRecoveryReason.SWITCH_PENDING
                ) {
                    MR.strings.sync_recovery_continue
                } else {
                    recoveryActionLabel(nextStep ?: action)
                },
                state.recoveryActionsEnabled && !state.busy,
                primary = primary,
            ) {
                dispatch(SyncPanelAction.ExecuteRecoveryAction(action))
                val installationUrl = state.setupInstallation?.let { installation ->
                    state.setupAccountLogin?.let { githubInstallationManagementUrl(installation, it) }
                } ?: "https://github.com/settings/installations"
                val opened = when (action) {
                    SyncRecoveryAction.INSTALL_APP -> openBrowser(GITHUB_APP_INSTALL_URL)
                    SyncRecoveryAction.RESTORE_INSTALLATION, SyncRecoveryAction.MANAGE_AUTHORIZATION -> openBrowser(
                        installationUrl,
                    )
                    SyncRecoveryAction.OFFICIAL_CREATE -> openBrowser(
                        githubRepositoryCreationUrl(state.setupAccountLogin, state.repositoryCreationName),
                    )
                    SyncRecoveryAction.RESTORE_REPOSITORY -> {
                        val repository = state.setupRepository ?: state.connection?.repository
                        if (repository != null) {
                            openBrowser(
                                "https://github.com/${encodeQueryParameter(repository.owner)}/" +
                                    "${encodeQueryParameter(repository.name)}/settings",
                            )
                        } else {
                            openBrowser("https://github.com/settings/repositories")
                        }
                    }
                    else -> false
                }
                if (opened) dispatch(SyncPanelAction.RecoveryOfficialOpened(action))
            }
        }
    }
}

private fun recoveryActionLabel(action: SyncRecoveryAction): StringResource = when (action) {
    SyncRecoveryAction.CONNECT_GITHUB -> MR.strings.sync_reconnect
    SyncRecoveryAction.INSTALL_APP -> MR.strings.sync_setup_install_app
    SyncRecoveryAction.AUTHORIZE_REPOSITORY -> MR.strings.sync_repository_scope_confirm
    SyncRecoveryAction.CREATE_SPACE -> MR.strings.sync_recovery_create
    SyncRecoveryAction.CHOOSE_SPACE -> MR.strings.sync_recovery_connect_other
    SyncRecoveryAction.CHECK_CONDITIONS -> MR.strings.sync_recovery_recheck
    SyncRecoveryAction.NETWORK -> MR.strings.sync_recovery_network
    SyncRecoveryAction.STORAGE -> MR.strings.sync_recovery_storage
    SyncRecoveryAction.UPDATE -> MR.strings.sync_recovery_update
    SyncRecoveryAction.BACKUP -> MR.strings.sync_recovery_backup
    SyncRecoveryAction.EXTENSIONS -> MR.strings.sync_recovery_extensions
    SyncRecoveryAction.MIGRATION -> MR.strings.sync_recovery_migration
    SyncRecoveryAction.READER -> MR.strings.sync_recovery_reader
    SyncRecoveryAction.REPAIR_DATA -> MR.strings.sync_recovery_repair_data
    SyncRecoveryAction.VERIFY_SYNC -> MR.strings.sync_recovery_verify
    SyncRecoveryAction.CONTINUE_SETUP -> MR.strings.sync_setup_continue_title
    SyncRecoveryAction.EDIT_REPOSITORY_NAME -> MR.strings.sync_repository_name
    SyncRecoveryAction.REPAIR_REPOSITORY_PROPERTIES -> MR.strings.sync_repository_properties_confirm
    SyncRecoveryAction.MANAGE_AUTHORIZATION, SyncRecoveryAction.RESTORE_INSTALLATION -> {
        MR.strings.sync_setup_manage_installation
    }
    SyncRecoveryAction.RESUME_SYNC, SyncRecoveryAction.ENABLE_SYNC -> MR.strings.sync_resume
    SyncRecoveryAction.RESUME_IMPORT -> MR.strings.sync_resume
    SyncRecoveryAction.OFFICIAL_CREATE -> MR.strings.sync_setup_create_repo
    SyncRecoveryAction.RESTORE_REPOSITORY -> MR.strings.sync_recovery_restore_repository
    SyncRecoveryAction.WAIT_EXTERNAL, SyncRecoveryAction.WAIT_SERVICE -> MR.strings.sync_recovery_waiting_external
}

private fun recoveryReasonCode(reason: String): String {
    val candidate = reason.substringBefore(':').substringBefore(' ')
    return if (candidate.matches(Regex("[A-Z_]{1,80}"))) candidate else "UNKNOWN"
}

@Composable
private fun RecoveryItemExplanation(title: String, reason: String, details: List<String>) {
    var expanded by remember(title, details) { mutableStateOf(false) }
    val label = when (recoveryReasonCode(reason)) {
        "SOURCE" -> MR.strings.sync_recovery_item_source
        "DESCRIPTION" -> MR.strings.sync_recovery_item_description
        "IDENTITY" -> MR.strings.sync_recovery_item_identity
        "DEPENDENCY", "MISSING_PARENT", "MISSING_PARENT_EFFECT" -> MR.strings.sync_recovery_item_dependency
        "UNREADABLE" -> MR.strings.sync_recovery_item_unreadable
        "BATCH_TOO_LARGE", "TOO_MANY_EFFECTS", "PAYLOAD_TOO_LARGE" -> MR.strings.sync_recovery_item_limit
        "UNKNOWN_PROTOCOL" -> MR.strings.sync_recovery_item_protocol
        else -> MR.strings.sync_recovery_item_invalid
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.take(200), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(syncString(label), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) { Text(syncString(MR.strings.sync_recovery_item_details)) }
        if (expanded) {
            details.filter(String::isNotBlank).forEach {
                Text(it.take(1000), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecoveryPlatformAction(
    tag: String,
    label: StringResource,
    action: SyncRecoveryPlatformAction,
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    primary: Boolean = false,
    objectKey: mihon.domain.sync.SyncObjectKey? = null,
) {
    Action(tag, label, !state.recoveryBusy, primary = primary) {
        dispatch(SyncPanelAction.OpenRecoveryPlatform(action, objectKey))
    }
}

@Composable
private fun ContinueSwitchAction(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit) {
    Action(
        "sync-recovery-continue",
        when (state.pendingRecoveryPurpose) {
            SyncRecoveryContinuation.CREATE -> MR.strings.sync_recovery_continue_create
            SyncRecoveryContinuation.CONNECT -> MR.strings.sync_recovery_continue_connect
            null -> MR.strings.sync_recovery_continue
        },
        enabled = state.recoveryActionsEnabled,
        primary = true,
    ) { dispatch(SyncPanelAction.ContinueRecovery) }
}

private val SyncPanelState.recoveryAuthorizationConfirmed: Boolean
    get() = recoveryAuthorization == SyncRecoveryAuthorization.CONFIRMED ||
        recovery?.authorizationConfirmedAtMillis != null

private val SyncPanelState.recoveryActionsEnabled: Boolean
    get() = recovery?.busy != true && !setupBusy && !recoveryBusy && nowMillis >= authRetryAtMillis &&
        recoveryAuthorization !in setOf(
            SyncRecoveryAuthorization.CHECKING,
            SyncRecoveryAuthorization.WAITING,
            SyncRecoveryAuthorization.VERIFYING,
        )

@Composable
private fun RecoveryCancelAction(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit) {
    if (!state.canCancelRecoverySwitch) return
    Action("sync-recovery-cancel-switch", MR.strings.sync_recovery_cancel_switch, state.recoveryActionsEnabled) {
        dispatch(SyncPanelAction.Ask(SyncPanelQuestion.CANCEL_RECOVERY_SWITCH))
    }
}

@Composable
private fun RecoveryFacts(state: SyncPanelState) {
    val authorization = when (state.recoveryAuthorization) {
        SyncRecoveryAuthorization.CHECKING -> MR.strings.sync_recovery_authorization_checking
        SyncRecoveryAuthorization.WAITING -> MR.strings.sync_recovery_authorization_waiting
        SyncRecoveryAuthorization.VERIFYING -> MR.strings.sync_recovery_authorization_verifying
        SyncRecoveryAuthorization.CONFIRMED -> MR.strings.sync_recovery_authorization_confirmed
        SyncRecoveryAuthorization.CANCELLED -> MR.strings.sync_recovery_authorization_cancelled
        SyncRecoveryAuthorization.FAILED -> MR.strings.sync_recovery_authorization_failed
        SyncRecoveryAuthorization.IDLE -> if (state.recoveryAuthorizationConfirmed) {
            MR.strings.sync_recovery_authorization_confirmed
        } else if (state.recovery?.reason == SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED) {
            MR.strings.sync_recovery_auth_expired
        } else {
            MR.strings.sync_recovery_authorization_unknown
        }
    }
    Text(
        syncString(
            if (state.recoveryAuthorizationConfirmed) {
                MR.strings.sync_recovery_authorization_confirmed
            } else {
                authorization
            },
        ),
        Modifier.testTag("sync-recovery-authorization-fact"),
    )
    if (state.recoveryAuthorizationConfirmed &&
        state.recoveryAuthorization !in setOf(SyncRecoveryAuthorization.IDLE, SyncRecoveryAuthorization.CONFIRMED)
    ) {
        Text(
            syncString(authorization),
            Modifier.testTag("sync-recovery-authorization-feedback"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        syncString(
            when (state.recovery?.lastCheckReason ?: state.recovery?.reason) {
                SyncSpaceRecoveryReason.SPACE_UNAVAILABLE -> MR.strings.sync_recovery_check_unavailable
                SyncSpaceRecoveryReason.SPACE_DATA_INVALID -> MR.strings.sync_recovery_data_problem
                SyncSpaceRecoveryReason.SWITCH_PENDING -> MR.strings.sync_recovery_switch_pending_title
                SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED -> MR.strings.sync_recovery_waiting
                null -> when {
                    state.recoveryOutcome == SyncRecoveryOutcome.ORIGINAL_VERIFIED ||
                        state.recovery?.lastCheckSucceeded == true -> MR.strings.sync_recovery_check_available
                    state.recoveryOutcome == SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING ->
                        MR.strings.sync_recovery_new_verified
                    else -> MR.strings.sync_recovery_check_pending
                }
            },
        ),
        Modifier.testTag("sync-recovery-space-fact"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.recovery?.lastCheckedAtMillis?.let { checkedAt ->
        Text(
            syncString(MR.strings.sync_recovery_last_check, syncScheduleDateTime(checkedAt)),
            Modifier.testTag("sync-recovery-last-check"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun recoveryBody(state: SyncPanelState): StringResource =
    if (state.recovery?.reason == SyncSpaceRecoveryReason.SWITCH_PENDING) {
        MR.strings.sync_recovery_switch_pending_body
    } else {
        MR.strings.sync_recovery_preserved
    }

private fun recoveryWaiting(state: SyncPanelState): StringResource =
    if (state.recovery?.reason == SyncSpaceRecoveryReason.SWITCH_PENDING) {
        MR.strings.sync_recovery_switch_pending_waiting
    } else {
        MR.strings.sync_recovery_waiting
    }

@Composable
private fun RecoveryCheckFeedback(state: SyncPanelState) {
    if (state.recovery?.busy == true) {
        Text(
            syncString(MR.strings.sync_recovery_checking),
            Modifier.testTag("sync-recovery-checking").semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.recovery?.lastCheckSucceeded == false || state.problem in RECOVERY_CHECK_PROBLEMS) {
        Column(
            Modifier.testTag("sync-recovery-check-incomplete").semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(syncString(MR.strings.sync_recovery_check_incomplete))
            Text(
                problemText(state.recovery?.lastCheckProblem ?: state.problem ?: SyncRunProblem.UNKNOWN),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RECOVERY_CHECK_PROBLEMS = setOf(SyncRunProblem.NETWORK, SyncRunProblem.STORAGE)

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
    if (state.recovery != null) {
        RecoverySummary(state, dispatch, modifier)
        return
    }
    val continuingSetup = state.setupStep !in setOf(SyncSetupStep.SIGN_IN, SyncSetupStep.COMPLETE)
    LazyColumn(state = listState, modifier = modifier.testTag("sync-pending-list")) {
        if (state.recoveryExternalScopes.isNotEmpty() || state.recoveryOldScopes.isNotEmpty()) {
            item {
                ExternalRecoveryNotice(state, dispatch)
            }
        }
        item("summary") {
            if (state.run == null && !state.busy) {
                SyncStatusHeader(state, continuingSetup, dispatch)
                if (state.problem != null) {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        ErrorRecoveryExit(state, dispatch, "sync-main")
                    }
                }
                TextButton(
                    toggleDetails,
                    Modifier.padding(horizontal = 24.dp).testTag("sync-progress-details-toggle"),
                ) {
                    Text(
                        syncString(
                            if (detailsExpanded) MR.strings.sync_details_collapse else MR.strings.sync_details_expand,
                        ),
                    )
                }
                if (detailsExpanded) SyncQueueSummary(state)
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
            if (!state.showingCompactRun) {
                NextAutomaticSync(state)
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
            state.notice?.takeIf { !state.showingCompactRun }?.takeIf {
                it.spaceAddressUpdated || it.bulk != null || (it.exchange != null && state.run == null)
            }?.let { notice ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (notice.spaceAddressUpdated) {
                            Text(
                                syncString(MR.strings.sync_recovery_address_updated),
                                Modifier.testTag("sync-notice-address-updated"),
                            )
                        }
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
            if (detailsExpanded && state.run == null && state.importRemaining > 0) {
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
        if (!state.showingCompactRun && state.pendingTotal > 0) {
            stickyHeader(key = "selection-toolbar") { SelectionBar(state, dispatch) }
        }
        if (!state.showingCompactRun && state.pending.isEmpty()) {
            item("empty") {
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = 24.dp,
                        vertical = 16.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        syncString(MR.strings.sync_empty),
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!state.showingCompactRun) {
            items(state.pending, key = { it.id }) { item -> PendingRow(item, state, dispatch) }
        }
        if (!state.showingCompactRun && state.loading) {
            item("loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        if (!state.showingCompactRun && state.hasMore) {
            item("more") {
                Action("sync-load-more", MR.strings.sync_load_more, enabled = !state.loading) {
                    dispatch(SyncPanelAction.LoadMore)
                }
            }
        }
        if (!state.showingCompactRun) {
            item("history") {
                TextButton(
                    { dispatch(SyncPanelAction.Navigate(SyncPanelPage.HISTORY)) },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp).testTag("sync-history"),
                ) {
                    Icon(Icons.Outlined.History, null)
                    Text(syncString(MR.strings.sync_records), Modifier.weight(1f).padding(horizontal = 12.dp))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
            }
        }
    }
    LaunchedEffect(listState, state.hasMore, state.loading, state.pending.size) {
        if (state.showingCompactRun || !state.hasMore || state.loading) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= state.pending.size - 3 }
            .collect { dispatch(SyncPanelAction.LoadMore) }
    }
}

@Composable
private fun ExternalRecoveryNotice(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.recoveryExternalScopes.forEach { scope ->
            Text(
                syncString(
                    if (scope.remaining == null) {
                        MR.strings.sync_recovery_backup_unknown_scope
                    } else {
                        MR.strings.sync_recovery_backup_remaining
                    },
                    scope.remaining ?: 0,
                ),
            )
        }
        state.recoveryOldScopes.forEach { scope ->
            Text(syncString(MR.strings.sync_recovery_unverified_origin, scope.label))
        }
        if (state.recoveryOutcome == SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING) {
            Text(syncString(MR.strings.sync_recovery_new_verified))
        }
        Action("sync-main-external-recovery", MR.strings.sync_recovery_choose, !state.recoveryBusy) {
            dispatch(SyncPanelAction.OpenRecovery)
        }
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
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(28.dp).testTag("sync-status-icon"))
                Text(
                    statusText(state),
                    Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync-now"),
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
                            state.problem != null -> MR.strings.sync_setup_retry
                            else -> MR.strings.sync_now
                        },
                    ),
                )
            }
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
            Text(
                syncString(
                    MR.strings.sync_review_heading,
                    state.pendingTotal,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!state.selecting) {
                Text(
                    syncString(MR.strings.sync_review_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
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
            if (state.selecting) {
                Checkbox(
                    item.id in state.selected,
                    onCheckedChange = null,
                    modifier = Modifier.size(48.dp),
                )
            }
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
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Action("sync-keep-${item.id}", MR.strings.sync_keep, state.decisionsEnabled, tonal = true) {
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
private fun SettingsGroup(label: StringResource, tag: String, content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(syncString(label), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsPage(state: SyncPanelState, dispatch: (SyncPanelAction) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier.padding(horizontal = 24.dp).testTag("sync-settings-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("automatic") {
            SettingsGroup(MR.strings.sync_auto_group, "sync-settings-auto-group") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(syncString(MR.strings.sync_startup), Modifier.weight(1f))
                    Switch(
                        state.startup,
                        { dispatch(SyncPanelAction.SetStartup(it)) },
                        Modifier.testTag("sync-startup"),
                    )
                }
                Text(syncString(MR.strings.sync_periodic_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (minutes in listOf(0, 15, 60, 360, 1440)) {
                        FilterChip(
                            selected = state.periodMinutes == minutes,
                            onClick = { dispatch(SyncPanelAction.SetPeriod(minutes)) },
                            label = {
                                Text(
                                    if (minutes == 0) syncString(MR.strings.sync_off) else duration(minutes.toLong()),
                                )
                            },

                            modifier = Modifier.heightIn(min = 48.dp).testTag("sync-period-$minutes"),
                        )
                    }
                }
            }
        }
        item("account") {
            SettingsGroup(MR.strings.sync_account_devices, "sync-settings-account-group") {
                Text(syncString(MR.strings.sync_account), style = MaterialTheme.typography.titleSmall)
                state.connection?.let { Text("${it.repository.owner}/${it.repository.name}") }
                Action("sync-settings-connect", MR.strings.sync_reconnect) { dispatch(SyncPanelAction.Authorize) }
                OutlinedTextField(
                    state.deviceName,
                    { dispatch(SyncPanelAction.SetDeviceName(it)) },
                    Modifier.fillMaxWidth().testTag("sync-device-name"),
                    label = { Text(syncString(MR.strings.sync_device_name)) },
                    singleLine = true,
                )
                Text(
                    syncString(
                        when (state.connection?.protectionMode) {
                            "password" -> MR.strings.sync_password_enabled
                            "none" -> MR.strings.sync_password_disabled
                            else -> MR.strings.sync_password_unavailable
                        },
                    ),
                    Modifier.testTag("sync-password-status"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("more") {
            SettingsGroup(MR.strings.sync_more_group, "sync-settings-more-group") {
                Action("sync-settings-history", MR.strings.sync_records) {
                    dispatch(SyncPanelAction.Navigate(SyncPanelPage.HISTORY))
                }
                Action("sync-settings-diagnostics", MR.strings.sync_diagnostics) {
                    dispatch(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
                }
            }
        }
        item("disconnect") {
            Action("sync-disconnect", MR.strings.sync_disconnect, state.connection != null) {
                dispatch(SyncPanelAction.Ask(SyncPanelQuestion.DISCONNECT))
            }
        }
        item("switch") {
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
    onCopySummary: (String) -> Unit,
    modifier: Modifier,
) {
    var technicalExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier.padding(24.dp).testTag("sync-diagnostics-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(syncString(MR.strings.sync_diagnostic_intro))
            Action("sync-diagnostic-recovery", MR.strings.sync_recovery_choose, !state.recoveryBusy) {
                dispatch(SyncPanelAction.OpenRecovery)
            }
        }
        item {
            Action(
                "sync-diagnostic-capture",
                MR.strings.sync_diagnostic_capture,
                !state.diagnosticBusy,
                primary = true,
            ) {
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
            TextButton(
                { technicalExpanded = !technicalExpanded },
                Modifier.heightIn(min = 48.dp).testTag("sync-diagnostic-details-toggle"),
            ) {
                Text(syncString(MR.strings.sync_diagnostic_details_toggle))
            }
        }
        if (technicalExpanded) {
            item {
                Text(
                    syncString(MR.strings.sync_diagnostic_description),
                    style = MaterialTheme.typography.bodySmall,
                )
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
                        if (comparable) {
                            SyncPanelAction.EndDiagnosticSession
                        } else {
                            SyncPanelAction.BeginDiagnosticSession
                        },
                    )
                }
            }
            state.diagnosticSnapshot?.let { snapshot ->
                item {
                    Text(
                        syncString(
                            when {
                                snapshot.status != SyncDiagnosticStatus.OK -> MR.strings.sync_diagnostic_unknown
                                snapshot.connection.panelConnectionEnabled == true ->
                                    MR.strings.sync_diagnostic_connected
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
                    Action(
                        "sync-diagnostic-copy-summary",
                        MR.strings.sync_diagnostic_copy_summary,
                        !state.diagnosticBusy,
                    ) {
                        onCopySummary(snapshot.json())
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
    openBrowser: (String) -> Boolean,
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
            !(state.setupStep == SyncSetupStep.MERGING && state.showingCompactRun) &&
            !(state.setupStep == SyncSetupStep.MERGING && state.run?.state?.isTerminal() == true)
        ) {
            item { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
        state.setupProblem?.takeUnless {
            (state.setupStep == SyncSetupStep.MERGING && state.showingCompactRun) ||
                (
                    state.setupStep == SyncSetupStep.ERROR && (
                        it == SyncDiscoveryProblem.NEEDS_INSTALLATION ||
                            (state.repositoryPreparedName != null && it == SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)
                        )
                    )
        }
            ?.let { item { Text(setupProblemText(it), Modifier.testTag("sync-setup-error")) } }
        state.setupInstallation?.takeUnless {
            state.setupStep == SyncSetupStep.MERGING && state.showingCompactRun
        }?.let(::installationScopeWarning)?.let { warning ->
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
                    item {
                        Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            code.verificationUri.substringBefore('?').substringBefore('#').take(256),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                    item { Text(authFailureText(failure), Modifier.testTag("sync-auth-failure")) }
                    item {
                        RecoveryDecisionAction(
                            state.recoveryPrimaryAction,
                            state,
                            dispatch,
                            openBrowser,
                            primary = true,
                        )
                    }
                    item { ErrorRecoveryExit(state, dispatch, "sync-auth") }
                }
                if (state.authFailure == null && state.deviceCode == null) {
                    item {
                        Action(
                            "sync-authorize",
                            MR.strings.sync_connect,
                            !state.setupBusy && state.nowMillis >= state.authRetryAtMillis,
                        ) {
                            dispatch(SyncPanelAction.Authorize)
                        }
                    }
                }
                item {
                    Text(syncString(MR.strings.sync_recovery_backup_key_boundary))
                    RecoveryPlatformAction(
                        "sync-auth-preserve",
                        MR.strings.sync_recovery_storage,
                        SyncRecoveryPlatformAction.STORAGE,
                        state,
                        dispatch,
                    )
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
                if (!creating) {
                    item {
                        Text(syncString(MR.strings.sync_recovery_backup_key_boundary))
                        Action("sync-password-preserve", MR.strings.sync_recovery_storage, !state.setupBusy) {
                            dispatch(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.STORAGE))
                        }
                    }
                }
            }
            SyncSetupStep.CHOOSE_SPACE -> {
                if (state.spaces.isEmpty()) {
                    item {
                        Text(
                            syncString(MR.strings.sync_recovery_no_spaces),
                            Modifier.testTag("sync-recovery-no-spaces"),
                        )
                    }
                    item {
                        Action(
                            "sync-recovery-authorization",
                            MR.strings.sync_recovery_authorization,
                            !state.setupBusy,
                        ) {
                            dispatch(SyncPanelAction.CheckAuthorization)
                        }
                    }
                    item {
                        Action("sync-recovery-create", MR.strings.sync_recovery_create, !state.setupBusy) {
                            dispatch(SyncPanelAction.CreateNewSpace)
                        }
                    }
                } else {
                    item { Text(syncString(MR.strings.sync_setup_multiple)) }
                }
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
            SyncSetupStep.PREPARE_REPOSITORY -> {
                item { RepositoryPreparationContent(state, dispatch, openBrowser) }
            }
            SyncSetupStep.ERROR -> {
                val problem = state.setupProblem
                val installationGuidance = problem in INSTALLATION_RECOVERY_PROBLEMS &&
                    problem != SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE
                item { SetupErrorActions(state, dispatch, openBrowser, installationGuidance) }
                if (state.creationSubmitted && state.creationRepositoryId != null) {
                    item { Text(state.setupRepository?.fullName ?: state.repositoryCreationName) }
                }
                val needsRepositoryGuide = problem in setOf(
                    SyncDiscoveryProblem.NEEDS_INSTALLATION,
                    SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                )
                if (needsRepositoryGuide && !installationGuidance) {
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
                                openBrowser(githubRepositoryCreationUrl(login, state.repositoryCreationName))
                            }
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
                if (installationGuidance) {
                    item {
                        Action(
                            "sync-setup-error-details",
                            MR.strings.sync_diagnostic_information,
                            state.recoveryActionsEnabled && !state.busy,
                        ) { dispatch(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryPreparationContent(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    openBrowser: (String) -> Boolean,
) {
    var name by remember(state.repositoryCreationName) {
        mutableStateOf(state.repositoryCreationName)
    }
    val browserFirst = state.needsRepositoryPreparation
    val browserCreationOpened = state.recoveryOfficialAction == SyncRecoveryAction.OFFICIAL_CREATE &&
        name.trim() == state.repositoryCreationName
    val nativeAllowed = !browserFirst && !state.setupBusy && state.setupInstallation?.canCreateRepository == true &&
        state.creationPermissionProblem == null
    val validName = name.trim().isNotEmpty() && runCatching {
        mihon.domain.sync.transport.SyncRepository("validated-owner", name.trim(), "mihon-sync-v1")
    }.isSuccess
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            syncString(
                if (browserFirst) {
                    MR.strings.sync_setup_prepare_space_title
                } else {
                    MR.strings.sync_recovery_prepare_title
                },
            ),
            Modifier.testTag("sync-recovery-prepare"),
            style = MaterialTheme.typography.titleLarge,
        )
        state.setupAccountLogin?.let { login ->
            Text(
                syncString(MR.strings.sync_setup_current_account, login),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (browserFirst) {
            Text(syncString(MR.strings.sync_setup_repository_first_hint))
            Text(
                syncString(MR.strings.sync_setup_install_after_repository),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(syncString(MR.strings.sync_recovery_prepare_body))
        }
        if (state.setupBusy) {
            Text(syncString(MR.strings.sync_repository_permission_checking))
        } else if (!browserFirst && state.creationPermissionProblem != null) {
            Text(
                syncString(
                    if (state.creationPermissionProblem == SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION) {
                        MR.strings.sync_repository_permission_browser
                    } else {
                        MR.strings.sync_repository_permission_unknown
                    },
                ),
                Modifier.testTag("sync-repository-permission-status"),
            )
        }
        if (!state.creationSubmitted || browserFirst) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(syncString(MR.strings.sync_repository_name)) },
                singleLine = true,
                enabled = !state.setupBusy,
                modifier = Modifier.fillMaxWidth().testTag("sync-repository-name"),
            )
            if (nativeAllowed) {
                Action(
                    "sync-repository-create-native",
                    MR.strings.sync_repository_create_confirm,
                    !state.setupBusy && validName && state.nowMillis >= state.authRetryAtMillis,
                    primary = true,
                ) {
                    dispatch(SyncPanelAction.PrepareRepositoryCreation(name.trim()))
                }
            }
        } else {
            Text(state.setupRepository?.fullName ?: state.repositoryCreationName)
            Action(
                "sync-repository-authorize-native",
                MR.strings.sync_repository_scope_confirm,
                !state.setupBusy && state.nowMillis >= state.authRetryAtMillis,
                primary = true,
            ) {
                dispatch(SyncPanelAction.AuthorizeRepositoryScope)
            }
        }
        Action(
            "sync-create-private-repo",
            if (browserFirst) MR.strings.sync_setup_create_space_browser else MR.strings.sync_setup_create_repo,
            !state.setupBusy && validName,
            primary = !nativeAllowed && !browserCreationOpened,
        ) {
            if (browserFirst) dispatch(SyncPanelAction.PrepareBrowserRepository(name.trim()))
            openBrowser(githubRepositoryCreationUrl(state.setupAccountLogin, name.trim()))
                .also { opened ->
                    if (opened && browserFirst) {
                        dispatch(SyncPanelAction.RecoveryOfficialOpened(SyncRecoveryAction.OFFICIAL_CREATE))
                    }
                }
        }
        if (!browserFirst || browserCreationOpened) {
            Action(
                "sync-recovery-repository-created",
                if (browserFirst) {
                    MR.strings.sync_setup_created_continue
                } else {
                    MR.strings.sync_recovery_repository_created
                },
                !state.setupBusy && validName,
                primary = browserFirst && browserCreationOpened,
            ) {
                dispatch(
                    if (browserFirst) {
                        SyncPanelAction.ConfirmRepositoryPrepared(name.trim())
                    } else {
                        SyncPanelAction.PrepareManualRepository(name.trim())
                    },
                )
            }
        }
        if (browserFirst) {
            Action(
                "sync-setup-use-existing",
                MR.strings.sync_setup_use_existing_repository,
                !state.setupBusy && validName,
            ) {
                dispatch(SyncPanelAction.ConfirmRepositoryPrepared(name.trim()))
            }
            Text(
                syncString(MR.strings.sync_setup_browser_creation_unverified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.creationPermissionProblem != null && !state.setupBusy) {
            Action("sync-repository-permission-recheck", MR.strings.sync_recovery_recheck) {
                dispatch(SyncPanelAction.CheckRepositoryCreationPermission)
            }
        }
    }
}

@Composable
private fun SetupErrorActions(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    openBrowser: (String) -> Boolean,
    installationGuidance: Boolean,
) {
    if (state.needsRepositoryPreparation) {
        RepositoryPreparationContent(state, dispatch, openBrowser)
        return
    }
    var guideExpanded by remember(state.setupProblem) { mutableStateOf(false) }
    var alternativesExpanded by remember(state.setupProblem) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.setupProblem == SyncDiscoveryProblem.NEEDS_INSTALLATION ||
            (state.repositoryPreparedName != null && state.setupProblem == SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)
        ) {
            Text(syncString(MR.strings.sync_setup_install_space_title), style = MaterialTheme.typography.titleLarge)
            state.setupAccountLogin?.let { account ->
                Text(
                    syncString(MR.strings.sync_setup_current_account, account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp))
                        Text(
                            syncString(MR.strings.sync_setup_connected_status),
                            Modifier.padding(start = 12.dp).testTag("sync-setup-account-confirmed"),
                        )
                    }
                    Text(
                        syncString(
                            if (state.repositoryPreparedName != null) {
                                MR.strings.sync_setup_space_prepared_unverified
                            } else {
                                MR.strings.sync_setup_space_pending
                            },
                            state.repositoryPreparedName ?: state.repositoryCreationName,
                        ),
                        Modifier.testTag("sync-setup-space-pending"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        syncString(
                            if (state.setupInstallation != null) {
                                MR.strings.sync_setup_installed_status
                            } else {
                                MR.strings.sync_setup_installation_pending
                            },
                        ),
                        Modifier.testTag("sync-setup-install-pending"),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Text(
                syncString(
                    if (state.setupInstallation != null) {
                        MR.strings.sync_setup_selected_access_unconfirmed
                    } else {
                        MR.strings.sync_setup_install_selected_space
                    },
                    state.repositoryPreparedName ?: state.setupRepository?.name ?: state.repositoryCreationName,
                ),
            )
            if (state.recoveryOfficialCheckAttempted) {
                Text(syncString(MR.strings.sync_setup_installation_not_found))
            }
        }
        RecoveryDecisionAction(state.recoveryPrimaryAction, state, dispatch, openBrowser, primary = true)
        if (!installationGuidance) {
            state.recoveryAlternativeActions.firstOrNull {
                it.action == SyncRecoveryAction.CHECK_CONDITIONS
            }?.let { RecoveryDecisionAction(it, state, dispatch, openBrowser) }
        }
        if (installationGuidance) {
            if (state.setupProblem == SyncDiscoveryProblem.NEEDS_INSTALLATION && state.setupAccountLogin != null) {
                Action("sync-setup-edit-space", MR.strings.sync_setup_edit_prepared_space, !state.setupBusy) {
                    dispatch(SyncPanelAction.EditRepositoryPreparation)
                }
            }
            if (state.recoveryPrimaryAction.action != SyncRecoveryAction.CHECK_CONDITIONS) {
                Action("sync-recheck-installation", MR.strings.sync_setup_check_continue, !state.setupBusy) {
                    dispatch(
                        SyncPanelAction.ExecuteRecoveryAction(SyncRecoveryAction.CHECK_CONDITIONS),
                    )
                }
            }
            TextButton(
                onClick = { guideExpanded = !guideExpanded },
                modifier = Modifier.testTag("sync-setup-instructions-toggle"),
            ) { Text(syncString(MR.strings.sync_setup_instructions)) }
            if (guideExpanded) {
                Text(syncString(MR.strings.sync_setup_repository_guide))
                Text(syncString(MR.strings.sync_setup_app_guide))
                Text(syncString(MR.strings.sync_setup_recheck_guide))
            }
            if (state.setupProblem != SyncDiscoveryProblem.NEEDS_INSTALLATION) {
                TextButton(
                    onClick = { alternativesExpanded = !alternativesExpanded },
                    modifier = Modifier.testTag("sync-setup-alternatives-toggle"),
                ) { Text(syncString(MR.strings.sync_setup_other_options)) }
                if (alternativesExpanded) {
                    state.recoveryAlternativeActions.filter {
                        it.action != SyncRecoveryAction.CHECK_CONDITIONS
                    }.forEach { RecoveryDecisionAction(it, state, dispatch, openBrowser) }
                }
            }
        } else {
            state.recoveryAlternativeActions.firstOrNull {
                it.action == SyncRecoveryAction.MANAGE_AUTHORIZATION
            }?.let { RecoveryDecisionAction(it, state, dispatch, openBrowser) }
            ErrorRecoveryExit(state, dispatch, "sync-setup")
        }
    }
}

@Composable
private fun ErrorRecoveryExit(
    state: SyncPanelState,
    dispatch: (SyncPanelAction) -> Unit,
    prefix: String,
    showDiagnostics: Boolean = true,
) {
    val enabled = state.recoveryActionsEnabled && !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.canOpenRecovery) {
            Text(syncString(MR.strings.sync_error_recovery_hint))
            Action("$prefix-recovery-open", MR.strings.sync_recovery_choose, enabled) {
                dispatch(SyncPanelAction.OpenRecovery)
            }
        }
        if (showDiagnostics) {
            Action("$prefix-error-details", MR.strings.sync_recovery_details, enabled) {
                dispatch(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS))
            }
        }
    }
}

private val SyncPanelState.showingCompactRun: Boolean
    get() = (run != null || busy) && run?.state !in TERMINAL_STATES

private fun syncClockDuration(seconds: Long): String =
    "${(seconds.coerceAtLeast(0) / 60).toString().padStart(2, '0')}:" +
        (seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')

@Composable
private fun SyncCompactProgressCard(
    state: SyncPanelState,
    presentation: SyncProgressPresentation,
    dispatch: (SyncPanelAction) -> Unit,
    horizontalPadding: Dp,
) {
    val run = state.run
    val counting = run?.plannedItems == null
    val countingInProgress = counting && (
        presentation.active ||
            (run?.state == SyncRunState.RUNNING && presentation.fact?.hold == SyncProgressHold.RECOVERING) ||
            (run == null && state.busy)
        )
    val status = if (presentation.status == MR.strings.sync_busy) MR.strings.sync_round_active else presentation.status
    val statusText = syncString(
        if (countingInProgress ||
            (counting && presentation.status in setOf(MR.strings.sync_busy, MR.strings.sync_wait_start))
        ) {
            MR.strings.sync_round_counting
        } else {
            status
        },
    )
    val firstLine = if (counting) {
        statusText
    } else {
        syncString(
            MR.strings.sync_round_progress,
            syncString(status),
            run?.confirmedItems ?: 0L,
            run?.plannedItems?.toString() ?: "—",
        )
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 16.dp).testTag("sync-progress-card"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.padding(18.dp)) {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
            val largestCount = run?.plannedItems ?: run?.confirmedItems ?: 0L
            val statusHeight = reservedTextHeight(
                listOf(
                    if (counting) {
                        statusText
                    } else {
                        syncString(
                            MR.strings.sync_round_progress,
                            statusText,
                            largestCount,
                            run?.plannedItems?.toString() ?: "—",
                        )
                    },
                ),
                constraints.maxWidth,
                textStyle,
            )
            // Three positive integer increments in the bounded whole-run window limit the longest valid ETA.
            val maximumEta = run?.plannedItems?.takeIf { it > 0 }?.let {
                kotlin.math.ceil(
                    it.toDouble() * SYNC_ROUND_RATE_WINDOW_MILLIS /
                        1000 / mihon.data.sync.runtime.SyncProgressTimeline.MIN_INCREMENT_SAMPLES,
                ).toLong()
            }
            val longestDuration = syncClockDuration(maxOf(5999L, presentation.elapsedSeconds, maximumEta ?: 0L))
            val timeHeight = reservedTextHeight(
                listOf(
                    syncString(MR.strings.sync_round_time, longestDuration, "—"),
                    syncString(MR.strings.sync_round_time, longestDuration, longestDuration),
                ),
                constraints.maxWidth,
                textStyle,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(statusHeight).semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = statusText
                    },
                ) {
                    Text(firstLine, Modifier.fillMaxWidth().testTag("sync-progress-status"), style = textStyle)
                }
                if (counting) {
                    if (countingInProgress) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).testTag("sync-counting-track"),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(4.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .testTag("sync-counting-paused-track"),
                        )
                    }
                }
                presentation.fraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).testTag("sync-progress-track"),
                        drawStopIndicator = {},
                    )
                }
                if (!counting) {
                    Text(
                        presentation.wholeEta?.let { eta ->
                            syncString(
                                MR.strings.sync_round_time,
                                syncClockDuration(presentation.elapsedSeconds),
                                syncClockDuration(eta),
                            )
                        } ?: syncString(MR.strings.sync_elapsed, syncClockDuration(presentation.elapsedSeconds)),
                        Modifier.fillMaxWidth().height(timeHeight).testTag("sync-round-time"),
                        style = textStyle,
                    )
                }
                SyncMainOperation(state, presentation, false, {}, dispatch)
                state.bulk?.takeIf { it.remaining > 0 }?.let { bulk ->
                    Action(
                        if (bulk.running) "sync-pause-bulk" else "sync-resume-bulk",
                        if (bulk.running) MR.strings.sync_pause_bulk else MR.strings.sync_resume_bulk,
                    ) {
                        val action = if (bulk.running) {
                            SyncPanelAction.PauseBulk
                        } else {
                            SyncPanelAction.ResumeBulk
                        }
                        dispatch(action)
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
    if (state.showingCompactRun) {
        SyncCompactProgressCard(state, presentation, dispatch, horizontalPadding)
        return
    }
    val run = state.run
    val fact = presentation.fact
    val terminal = run?.state in TERMINAL_STATES
    val triggerFocus = remember { FocusRequester() }
    var detailsHaveFocus by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 16.dp).testTag("sync-progress-card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (run?.state == SyncRunState.SUCCEEDED) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                    null,
                    Modifier.size(28.dp),
                )
                Text(
                    syncString(presentation.status),
                    Modifier.weight(1f).padding(start = 12.dp).testTag("sync-progress-status").semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            val seconds = presentation.elapsedSeconds
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val text = syncString(
                    MR.strings.sync_result_summary,
                    presentation.confirmed ?: 0L,
                    syncClockDuration(seconds),
                )
                Text(
                    text,
                    Modifier.fillMaxWidth().heightIn(
                        min = reservedTextHeight(
                            listOf(text),
                            constraints.maxWidth,
                            MaterialTheme.typography.bodyLarge,
                        ),
                    ).testTag("sync-confirmed-count"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run?.state == SyncRunState.FAILED && run.stopReason == "retry_exhausted") {
                Text(syncString(MR.strings.sync_retry_exhausted))
            } else if (run?.state == SyncRunState.BLOCKED ||
                (run?.state in setOf(SyncRunState.FAILED, SyncRunState.PARTIAL) && state.problem != null)
            ) {
                Text(problemText(state.problem ?: SyncRunProblem.UNKNOWN), Modifier.testTag("sync-blocked-reason"))
            } else if (run?.state == SyncRunState.PARTIAL) {
                Text(
                    syncString(
                        when {
                            run.stopReason == "projection_pending" -> MR.strings.sync_projection_pending
                            state.pendingTotal > 0 -> MR.strings.sync_pending_decisions_count
                            else -> MR.strings.sync_pending_decisions
                        },
                        *if (state.pendingTotal > 0 && run.stopReason != "projection_pending") {
                            arrayOf(state.pendingTotal)
                        } else {
                            emptyArray()
                        },

                    ),
                )
            }
            SyncMainOperation(state, presentation, detailsExpanded, toggleDetails, dispatch)
            if (run?.state in setOf(SyncRunState.FAILED, SyncRunState.BLOCKED, SyncRunState.PARTIAL) &&
                state.problem != null
            ) {
                ErrorRecoveryExit(state, dispatch, "sync-main")
            }
            TextButton({
                toggleDetails()
                if (detailsExpanded && detailsHaveFocus) triggerFocus.requestFocus()
            }, Modifier.heightIn(min = 48.dp).testTag("sync-progress-details-toggle").focusRequester(triggerFocus)) {
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

/** Running slots reserve stable candidates; terminal text measures only the actual finished result. */
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
        (
            168.dp - padding.calculateLeftPadding(
                direction,
            ) - padding.calculateRightPadding(direction) - 32.dp
            ).roundToPx()
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
    val buttonModifier = if (terminal) {
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
    } else {
        Modifier.width(168.dp).height(operationHeight)
    }
    val containerModifier = if (terminal) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().heightIn(
            min = operationHeight,
        )
    }
    Box(containerModifier, contentAlignment = Alignment.CenterEnd) {
        when {
            terminal && state.busy -> Action(
                "sync-wait",
                MR.strings.sync_wait_start,
                false,
                shape = operationShape,
                primary = terminal,
                modifier = buttonModifier,
            ) {}
            terminal && operation != null -> Action(
                if (state.connection?.unsupportedFormat == true) "sync-view-reason" else "sync-now",
                operation.label,
                operation.enabled,
                shape = operationShape,
                primary = terminal,
                modifier = buttonModifier,
            ) {
                dispatch(operation.action)
            }
            run?.state == SyncRunState.PAUSED_USER -> SyncPauseResumeButton(
                paused = true,
                enabled = state.connection?.enabled == true,
                modifier = buttonModifier,
            ) { dispatch(SyncPanelAction.ResumeSync) }
            run?.state == SyncRunState.FAILED || run?.state == SyncRunState.PARTIAL -> Action(
                "sync-retry-run",
                MR.strings.sync_retry_run,
                shape = operationShape,
                primary = terminal,
                modifier = buttonModifier,
            ) {
                dispatch(SyncPanelAction.RetrySync)
            }
            run?.state == SyncRunState.BLOCKED -> when (state.problem) {
                SyncRunProblem.AUTHORIZATION -> Action(
                    "sync-reconnect",
                    MR.strings.sync_reconnect,
                    shape = operationShape,
                    primary = terminal,
                    modifier = buttonModifier,
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
                        primary = terminal,
                        modifier = buttonModifier,
                    ) {
                        dispatch(SyncPanelAction.BeginSetup)
                    }
                } else {
                    Action(
                        "sync-view-reason",
                        MR.strings.sync_view_reason,
                        shape = operationShape,
                        primary = terminal,
                        modifier = buttonModifier,
                    ) {
                        if (!detailsExpanded) toggleDetails()
                    }
                }
                else -> Action(
                    "sync-view-reason",
                    MR.strings.sync_view_reason,
                    shape = operationShape,
                    primary = terminal,
                    modifier = buttonModifier,
                ) {
                    if (!detailsExpanded) toggleDetails()
                }
            }
            terminal -> Action(
                "sync-now",
                MR.strings.sync_now,
                state.connection?.enabled == true,
                shape = operationShape,
                primary = terminal,
                modifier = buttonModifier,
            ) {
                dispatch(SyncPanelAction.Synchronize)
            }
            presentation.active -> SyncPauseResumeButton(
                paused = false,
                modifier = buttonModifier,
            ) { dispatch(SyncPanelAction.PauseSync) }
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
                primary = terminal,
                modifier = buttonModifier,
            ) {}
        }
    }
}

@Composable
private fun SyncPauseResumeButton(
    paused: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).testTag(if (paused) "sync-resume-run" else "sync-pause-run"),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
    ) {
        Icon(
            if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            null,
            Modifier.size(24.dp).testTag("sync-pause-resume-icon"),
        )
        Spacer(Modifier.width(8.dp))
        Text(syncString(if (paused) MR.strings.sync_resume_run else MR.strings.sync_pause_run))
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
private fun authFailureText(failure: GitHubAuthFailureReason): String = syncString(
    when (failure) {
        GitHubAuthFailureReason.HTTP -> MR.strings.sync_auth_request_failed
        GitHubAuthFailureReason.MALFORMED_RESPONSE -> MR.strings.sync_auth_malformed
        GitHubAuthFailureReason.ACCESS_DENIED -> MR.strings.sync_auth_denied
        GitHubAuthFailureReason.PERMISSION_DENIED -> MR.strings.sync_auth_permission_denied
        GitHubAuthFailureReason.EXPIRED -> MR.strings.sync_auth_expired
        GitHubAuthFailureReason.REVOKED -> MR.strings.sync_auth_revoked
        GitHubAuthFailureReason.RATE_LIMITED -> MR.strings.sync_setup_rate_limited
    },
)

@Composable
private fun setupProblemText(problem: SyncDiscoveryProblem): String = syncString(
    when (problem) {
        SyncDiscoveryProblem.NEEDS_INSTALLATION -> MR.strings.sync_setup_needs_installation
        SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS -> MR.strings.sync_setup_needs_repository_access
        SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION -> MR.strings.sync_setup_missing_contents
        SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION -> MR.strings.sync_permission_create_missing
        SyncDiscoveryProblem.NEEDS_ADMINISTRATION_PERMISSION -> MR.strings.sync_permission_admin_missing
        SyncDiscoveryProblem.NEEDS_INSTALLATION_ACCESS_PERMISSION -> MR.strings.sync_permission_installation_missing
        SyncDiscoveryProblem.INSTALLATION_SUSPENDED -> MR.strings.sync_setup_installation_suspended
        SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE -> MR.strings.sync_setup_not_writable
        SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE -> MR.strings.sync_setup_repository_unavailable
        SyncDiscoveryProblem.REPOSITORY_IDENTITY_MISMATCH -> MR.strings.sync_setup_repository_identity_mismatch
        SyncDiscoveryProblem.REPOSITORY_ARCHIVED -> MR.strings.sync_repository_archived
        SyncDiscoveryProblem.REPOSITORY_DISABLED -> MR.strings.sync_repository_disabled
        SyncDiscoveryProblem.AUTHORIZATION_REQUIRED -> MR.strings.sync_setup_authorization
        SyncDiscoveryProblem.RATE_LIMITED -> MR.strings.sync_setup_rate_limited
        SyncDiscoveryProblem.INCOMPATIBLE -> MR.strings.sync_setup_incompatible
        SyncDiscoveryProblem.NAME_OCCUPIED -> MR.strings.sync_setup_name_occupied
        SyncDiscoveryProblem.CREATION_UNCONFIRMED -> MR.strings.sync_setup_unconfirmed
        SyncDiscoveryProblem.INITIALIZATION_REQUIRES_ACTION -> MR.strings.sync_problem_remote
        SyncDiscoveryProblem.INITIALIZATION_UNCONFIRMED -> MR.strings.sync_setup_unconfirmed
        SyncDiscoveryProblem.STORAGE_ERROR -> MR.strings.sync_problem_storage
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

private fun githubRepositoryCreationUrl(owner: String?, name: String = SyncRepositoryTarget.NAME): String =
    "https://github.com/new?name=${encodeQueryParameter(name)}" +
        "&visibility=${encodeQueryParameter("private")}" +
        owner?.let { "&owner=${encodeQueryParameter(it)}" }.orEmpty()

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

internal expect fun syncScheduleDateTime(millis: Long): String

@Composable
private fun NextAutomaticSync(state: SyncPanelState) {
    val text = when {
        state.connection?.enabled != true -> return
        state.periodMinutes <= 0 -> syncString(MR.strings.sync_auto_off)
        state.problem == SyncRunProblem.NETWORK -> syncString(MR.strings.sync_auto_network)
        state.nextSyncAtMillis <= state.nowMillis -> syncString(MR.strings.sync_auto_due)
        else -> syncString(
            MR.strings.sync_auto_next,
            syncScheduleDateTime(state.nextSyncAtMillis),
            duration(((state.nextSyncAtMillis - state.nowMillis - 1) / 60_000) + 1),
        )
    }
    Text(
        text,
        Modifier.fillMaxWidth().padding(
            horizontal = 24.dp,
            vertical = 8.dp,
        ).testTag("sync-next-auto"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun statusText(state: SyncPanelState): String = when {
    state.setupProblem != null -> state.setupProblem?.let { setupProblemText(it) }.orEmpty()
    state.busy -> syncString(MR.strings.sync_busy)
    state.problem != null -> state.problem?.let { problemText(it) }.orEmpty()
    state.connection?.enabled != true -> syncString(MR.strings.sync_connect)
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
        SyncRunProblem.SPACE_UNAVAILABLE -> MR.strings.sync_recovery_title
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
    primary: Boolean = false,
    tonal: Boolean = false,
    onClick: () -> Unit,
) {
    if (tonal) {
        FilledTonalButton(
            onClick,
            modifier.heightIn(min = 48.dp).testTag(tag),
            enabled = enabled,
        ) { Text(syncString(label)) }
    } else if (primary) {
        Button(
            onClick,
            modifier.heightIn(min = 48.dp).testTag(tag),
            enabled = enabled,
            shape = shape ?: MaterialTheme.shapes.medium,
        ) {
            BoxWithConstraints {
                val text = syncString(label)
                Text(
                    text,
                    Modifier.fillMaxWidth().heightIn(
                        min = reservedTextHeight(
                            listOf(text),
                            constraints.maxWidth,
                            MaterialTheme.typography.labelLarge,
                        ),
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else if (shape == null) {
        TextButton(onClick, modifier.heightIn(min = 48.dp).testTag(tag), enabled = enabled) { Text(syncString(label)) }
    } else {
        TextButton(
            onClick,
            modifier.heightIn(min = 48.dp).testTag(tag),
            enabled = enabled,
            shape = shape,
        ) { Text(syncString(label)) }
    }
}
