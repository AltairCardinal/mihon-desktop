@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package eu.kanade.tachiyomi.uicatalog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
internal fun ContentState(s: DemoState, send: Dispatch) {
    when (s.phase) {
        Phase.LOADING -> {
            Text(stringResource(R.string.lab_loading))
            LoadingScreen(Modifier.fillMaxWidth().height(180.dp).testTag("loading"))
            LabButton(R.string.lab_tick, "resolve-loading") { send(DemoAction.Retry) }
        }
        Phase.EMPTY, Phase.NO_RESULTS, Phase.ERROR, Phase.MISSING -> {
            val message = when (s.phase) {
                Phase.EMPTY -> R.string.lab_empty
                Phase.NO_RESULTS -> R.string.lab_no_results
                Phase.MISSING -> R.string.lab_missing
                else -> R.string.lab_error
            }
            EmptyScreen(message = stringResource(message), modifier = Modifier.fillMaxWidth().height(220.dp).testTag("empty-or-error"))
            LabButton(if (s.phase == Phase.EMPTY) R.string.lab_add else R.string.lab_retry, "retry") {
                send(if (s.phase == Phase.NO_RESULTS) DemoAction.Query(null) else DemoAction.Retry)
            }
        }
        else -> FixtureRows(s, send)
    }
}

@Composable
internal fun TaskControls(s: DemoState, send: Dispatch) {
    Text(stringResource(R.string.lab_progress, s.progress), Modifier.testTag("progress"))
    LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
    val canStart = s.phase != Phase.RUNNING && (s.category != 29 || s.order.isNotEmpty()) &&
        (s.category != 32 || s.directorySelected && s.permission && s.phase != Phase.ERROR) &&
        (s.category != 30 || s.trusted && s.permission)
    FlowRow {
        LabButton(R.string.lab_start, "start-task", canStart) { send(DemoAction.Start) }
        LabButton(R.string.lab_pause, "pause-task", s.phase == Phase.RUNNING) { send(DemoAction.Pause) }
        LabButton(R.string.lab_tick, "tick-task", s.phase == Phase.RUNNING) { send(DemoAction.Tick) }
        LabButton(R.string.lab_fail, "fail-task", s.phase == Phase.RUNNING) { send(DemoAction.Fail) }
        LabButton(R.string.lab_retry, "retry-task", s.phase == Phase.ERROR && s.category != 32) { send(DemoAction.Retry) }
    }
}

@Composable
internal fun AsyncExamples(s: DemoState, send: Dispatch, host: SnackbarHostState) {
    when (s.category) {
        18 -> ContentState(s, send)
        19 -> {
            StateText(s)
            TaskControls(s, send)
            // Content is deliberately retained during refresh and error phases.
            if (s.phase == Phase.ERROR) Text(stringResource(R.string.lab_error))
            FixtureRows(s, send)
        }
        20 -> {
            val scope = rememberCoroutineScope()
            val success = stringResource(R.string.lab_success)
            val error = stringResource(R.string.lab_error)
            val retry = stringResource(R.string.lab_retry)
            NameField(s, send)
            LabButton(R.string.lab_message, "show-message") {
                scope.launch {
                    host.currentSnackbarData?.dismiss()
                    val failed = s.phase == Phase.ERROR
                    val result = host.showSnackbar(
                        message = if (failed) error else success,
                        actionLabel = if (failed) retry else null,
                        duration = if (failed) SnackbarDuration.Indefinite else SnackbarDuration.Short,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) send(DemoAction.Retry)
                }
            }
            StateText(s)
        }
        21 -> {
            // A component preview inside an already inset-aware catalog. Production placement remains at the app root.
            AppStateBanners(downloadedOnlyMode = s.downloadedOnly, incognitoMode = s.privateMode, indexing = s.secondary)
            if (s.downloadedOnly) Text(stringResource(R.string.lab_filter_locked))
            LabButton(R.string.lab_clear, "reset-filter", !s.downloadedOnly) { send(DemoAction.ResetFilter) }
            Text(stringResource(R.string.lab_history, s.historyCount), Modifier.testTag("history-count"))
            LabButton(R.string.lab_fake_read, "fake-read") { send(DemoAction.FakeRead) }
            LabButton(R.string.lab_indexing, "toggle-indexing") { send(DemoAction.ToggleSecondary) }
        }
    }
}
