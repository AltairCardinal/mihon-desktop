package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.CreatorDiscoveryTaskState
import mihon.domain.task.TaskStatus
import tachiyomi.i18n.MR
import java.util.Locale

/**
 * Settings surface for the independent author discovery task (AA2-03).
 *
 * Renders the scheduler's reactive state (idle/running/finished/failed/cancelled), exposes
 * "check now" and cancel actions and never blocks the UI thread.
 */
@Composable
fun AuthorDiscoverySettingsSection(
    scheduler: CreatorDiscoveryScheduler?,
    modifier: Modifier = Modifier,
) {
    val state = scheduler?.state?.collectAsState()?.value ?: CreatorDiscoveryTaskState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = MR.strings.desktop_ui_author_discovery_title.localized(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        when (state.status) {
            TaskStatus.Pending -> {
                Text(
                    MR.strings.desktop_ui_author_discovery_waiting_network.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    enabled = scheduler != null,
                    onClick = { scheduler?.cancel() },
                ) {
                    Text(MR.strings.desktop_ui_author_discovery_cancel.localized())
                }
            }
            TaskStatus.Running -> {
                Text(
                    MR.strings.desktop_ui_author_discovery_running.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.progress != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    MR.strings.desktop_ui_author_discovery_sources.localized(
                        Locale.getDefault(),
                        state.completedSources,
                        state.totalSources,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = scheduler != null,
                    onClick = { scheduler?.cancel() },
                ) {
                    Text(MR.strings.desktop_ui_author_discovery_cancel.localized())
                }
            }
            TaskStatus.Failed -> {
                Text(
                    MR.strings.desktop_ui_author_discovery_failed.localized(
                        Locale.getDefault(),
                        state.failureMessage ?: state.failedUnits.joinToString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.lastFinishedAt != null) {
                    Text(
                        MR.strings.desktop_ui_author_discovery_result.localized(
                            Locale.getDefault(),
                            state.newCandidateCount,
                            state.errorCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TaskStatus.Cancelled -> {
                Text(
                    MR.strings.desktop_ui_author_discovery_cancelled.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TaskStatus.Completed -> {
                if (state.lastFinishedAt != null) {
                    Text(
                        MR.strings.desktop_ui_author_discovery_finished.localized(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        MR.strings.desktop_ui_author_discovery_result.localized(
                            Locale.getDefault(),
                            state.newCandidateCount,
                            state.errorCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        MR.strings.desktop_ui_author_discovery_idle.localized(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        TextButton(
            enabled = scheduler != null && state.status != TaskStatus.Running,
            onClick = { scope.launch { scheduler?.runNow()?.join() } },
        ) {
            Text(MR.strings.desktop_ui_author_discovery_run_now.localized())
        }
    }
}
