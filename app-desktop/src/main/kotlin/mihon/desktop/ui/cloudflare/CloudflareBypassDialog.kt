package mihon.desktop.ui.cloudflare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.desktop.network.ChallengeRecoveryAction
import mihon.desktop.network.ChallengeRecoveryFailure
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.network.ChallengeRecoveryState
import mihon.desktop.network.CloudflareChallenge
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.i18n.MR
import java.util.Locale

data class DesktopChallengeLoginUiState(
    val targetHost: String,
    val feedback: String,
    val runningAction: ChallengeRecoveryAction? = null,
    val allowConflictingActions: Boolean = true,
    val showSolver: Boolean = false,
    val solverGuidance: String? = null,
    val showRetry: Boolean = false,
    val dismiss: Boolean = false,
    val cookieNames: Set<String> = emptySet(),
    val cookieCount: Int = 0,
    val terminalClose: Boolean = false,
)

class DesktopChallengeLoginController(
    private val manager: CloudflareChallengeManager,
    private val browserBridge: DesktopChallengeBrowserLoginBridge,
    private val preferences: DesktopAppPreferences,
    private val locale: Locale = Locale.getDefault(),
) {
    fun uiState(
        challenge: CloudflareChallenge,
        state: ChallengeRecoveryState = challenge.state.value,
    ): DesktopChallengeLoginUiState {
        val solverAvailable = preferences.flareSolverrRuntimeConfig() != null
        val recovered = state as? ChallengeRecoveryState.Recovered
        return DesktopChallengeLoginUiState(
            targetHost = challenge.request.url.host,
            feedback = state.feedback(locale),
            runningAction = (state as? ChallengeRecoveryState.Running)?.action,
            allowConflictingActions = state !is ChallengeRecoveryState.Running,
            showSolver = solverAvailable && state != ChallengeRecoveryState.TimedOut,
            solverGuidance = if (solverAvailable) {
                null
            } else if (preferences.flareSolverrEnabled.get()) {
                MR.strings.desktop_challenge_solver_invalid.localized(locale)
            } else {
                MR.strings.desktop_challenge_solver_disabled.localized(locale)
            },
            showRetry = state is ChallengeRecoveryState.RecoverableFailure,
            dismiss = state is ChallengeRecoveryState.Recovered || state == ChallengeRecoveryState.Cancelled,
            cookieNames = recovered?.cookieNames.orEmpty(),
            cookieCount = recovered?.cookieCount ?: 0,
            terminalClose = state == ChallengeRecoveryState.TimedOut,
        )
    }

    suspend fun dispatch(
        challenge: CloudflareChallenge,
        intent: ChallengeRecoveryIntent,
    ): ChallengeRecoveryState {
        val current = challenge.state.value
        if (current is ChallengeRecoveryState.Running && intent != ChallengeRecoveryIntent.Cancel) return current
        if (intent == ChallengeRecoveryIntent.UseFlareSolverr && preferences.flareSolverrRuntimeConfig() == null) {
            return current
        }
        return manager.recover(challenge, intent)
    }

    suspend fun submitClearance(challenge: CloudflareChallenge, value: String): ChallengeRecoveryState {
        val normalized = value.trim()
        if (normalized.isEmpty()) return challenge.state.value
        val url = challenge.request.url
        val session = AuthenticatedSession(
            cookies = listOf(
                AuthenticatedCookie(
                    name = "cf_clearance",
                    value = normalized,
                    domain = url.host,
                    hostOnly = true,
                    path = "/",
                    expiresAt = null,
                    secure = url.isHttps,
                    httpOnly = true,
                ),
            ),
        )
        return if (challenge.state.value == ChallengeRecoveryState.Running(ChallengeRecoveryAction.Browser)) {
            browserBridge.complete(challenge, session)
            challenge.state.value
        } else {
            dispatch(challenge, ChallengeRecoveryIntent.SubmitManualCookies(session))
        }
    }

    fun shouldDismiss(
        activeChallenge: CloudflareChallenge?,
        completedChallenge: CloudflareChallenge,
        state: ChallengeRecoveryState,
    ): Boolean = activeChallenge === completedChallenge &&
        (state is ChallengeRecoveryState.Recovered || state == ChallengeRecoveryState.Cancelled)
}

sealed interface DesktopChallengeHomeAction {
    data class Recover(val intent: ChallengeRecoveryIntent) : DesktopChallengeHomeAction
    data class SubmitClearance(val value: String) : DesktopChallengeHomeAction
    data object Close : DesktopChallengeHomeAction
}

data class DesktopChallengeHomeResult(
    val dismiss: Boolean = false,
    val feedback: String? = null,
)

class DesktopChallengeHomeActionAdapter(
    private val controller: DesktopChallengeLoginController,
) {
    suspend fun execute(
        activeChallenge: () -> CloudflareChallenge?,
        challenge: CloudflareChallenge,
        action: DesktopChallengeHomeAction,
    ): DesktopChallengeHomeResult {
        if (action == DesktopChallengeHomeAction.Close) {
            return DesktopChallengeHomeResult(
                dismiss = activeChallenge() === challenge && challenge.state.value == ChallengeRecoveryState.TimedOut,
            )
        }
        when (action) {
            is DesktopChallengeHomeAction.Recover -> controller.dispatch(challenge, action.intent)
            is DesktopChallengeHomeAction.SubmitClearance -> controller.submitClearance(challenge, action.value)
            DesktopChallengeHomeAction.Close -> error("handled above")
        }
        return observe(activeChallenge, challenge)
    }

    fun observe(
        activeChallenge: () -> CloudflareChallenge?,
        challenge: CloudflareChallenge,
    ): DesktopChallengeHomeResult {
        val state = challenge.state.value
        val dismiss = controller.shouldDismiss(activeChallenge(), challenge, state)
        val feedback = if (dismiss) controller.uiState(challenge, state).feedback else null
        return DesktopChallengeHomeResult(dismiss, feedback)
    }
}

@Composable
fun CloudflareBypassDialog(
    state: DesktopChallengeLoginUiState,
    onIntent: (ChallengeRecoveryIntent) -> Unit,
    onCookieSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    var cookieValue by remember { mutableStateOf("") }
    val locale = Locale.getDefault()
    val text: (dev.icerock.moko.resources.StringResource) -> String = { it.localized(locale) }

    AlertDialog(
        onDismissRequest = {
            if (state.terminalClose) onClose() else onIntent(ChallengeRecoveryIntent.Cancel)
        },
        title = { Text(text(MR.strings.desktop_challenge_title)) },
        text = {
            Column {
                Text(text(MR.strings.desktop_challenge_description))
                Text(text(MR.strings.desktop_challenge_domain))
                Text(state.targetHost)
                Text(state.feedback)
                state.solverGuidance?.let { Text(it) }
                Spacer(Modifier.height(8.dp))
                if (!state.terminalClose) {
                    TextButton(
                        onClick = { onIntent(ChallengeRecoveryIntent.OpenBrowser) },
                        enabled = state.allowConflictingActions,
                    ) { Text(text(MR.strings.desktop_challenge_open_browser)) }
                    if (state.showSolver) {
                        TextButton(
                            onClick = { onIntent(ChallengeRecoveryIntent.UseFlareSolverr) },
                            enabled = state.allowConflictingActions,
                        ) { Text(text(MR.strings.desktop_challenge_use_solver)) }
                    }
                    OutlinedTextField(
                        value = cookieValue,
                        onValueChange = { cookieValue = it },
                        label = { Text(text(MR.strings.desktop_challenge_manual_cookie)) },
                        placeholder = { Text(text(MR.strings.desktop_challenge_manual_placeholder)) },
                        singleLine = true,
                        enabled = state.allowConflictingActions || state.runningAction == ChallengeRecoveryAction.Browser,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.cookieCount > 0) {
                    Text(text(MR.strings.desktop_challenge_cookie_names))
                    Text(state.cookieNames.sorted().joinToString())
                    Text(text(MR.strings.desktop_challenge_cookie_count))
                    Text(state.cookieCount.toString())
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                if (state.terminalClose) {
                    TextButton(onClick = onClose) { Text(text(MR.strings.desktop_challenge_close)) }
                } else if (state.showRetry) {
                    TextButton(onClick = { onIntent(ChallengeRecoveryIntent.Retry) }) {
                        Text(text(MR.strings.desktop_challenge_retry))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (!state.terminalClose) {
                    TextButton(onClick = { onIntent(ChallengeRecoveryIntent.Cancel) }) {
                        Text(text(MR.strings.desktop_challenge_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onCookieSubmit(cookieValue) },
                        enabled = cookieValue.isNotBlank() &&
                            (state.allowConflictingActions || state.runningAction == ChallengeRecoveryAction.Browser),
                    ) { Text(text(MR.strings.desktop_challenge_manual_submit)) }
                }
            }
        },
    )
}

private fun ChallengeRecoveryState.feedback(locale: Locale): String = when (this) {
    ChallengeRecoveryState.AwaitingUserAction -> MR.strings.desktop_challenge_awaiting.localized(locale)
    is ChallengeRecoveryState.Running -> when (action) {
        ChallengeRecoveryAction.Browser -> MR.strings.desktop_challenge_browser_running.localized(locale)
        ChallengeRecoveryAction.ManualCookies -> MR.strings.desktop_challenge_manual_running.localized(locale)
        ChallengeRecoveryAction.FlareSolverr -> MR.strings.desktop_challenge_solver_running.localized(locale)
    }
    is ChallengeRecoveryState.RecoverableFailure -> when (reason) {
        ChallengeRecoveryFailure.BrowserUnavailable -> MR.strings.desktop_challenge_browser_unavailable.localized(locale)
        ChallengeRecoveryFailure.SolverUnavailable -> MR.strings.desktop_challenge_solver_disabled.localized(locale)
        ChallengeRecoveryFailure.SolverFailed -> MR.strings.desktop_challenge_solver_failed.localized(locale)
        ChallengeRecoveryFailure.InvalidCookies -> MR.strings.desktop_challenge_invalid_cookie.localized(locale)
        ChallengeRecoveryFailure.CommitFailed -> MR.strings.desktop_challenge_commit_failed.localized(locale)
    }
    is ChallengeRecoveryState.Recovered -> MR.strings.desktop_challenge_recovered.localized(locale)
    ChallengeRecoveryState.Cancelled -> MR.strings.desktop_challenge_cancelled.localized(locale)
    ChallengeRecoveryState.TimedOut -> MR.strings.desktop_challenge_timed_out.localized(locale)
}
