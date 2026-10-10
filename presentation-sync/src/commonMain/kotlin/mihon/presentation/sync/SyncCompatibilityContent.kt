package mihon.presentation.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR

enum class SyncCompatibilityFeedback {
    NOT_CHECKED,
    NO_TRUSTED_SOURCE,
    SELECTING,
    VERIFIED,
    REJECTED,
    MANUAL_ONLY,
    CANCELLED,
    FAILED,
    RESTART_REQUIRED,
}

/** Shared recovery UI. Platform adapters inspect actual release trust and packages; this view never infers success. */
@Composable
fun SyncCompatibilityContent(
    version: String,
    feedback: SyncCompatibilityFeedback,
    onCheck: () -> Unit,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(syncString(MR.strings.sync_recovery_update))
        Text(syncString(MR.strings.sync_compatibility_version, version))
        Text(syncString(MR.strings.sync_compatibility_boundary))
        if (feedback != SyncCompatibilityFeedback.NOT_CHECKED) {
            val message = when (feedback) {
                SyncCompatibilityFeedback.NO_TRUSTED_SOURCE -> MR.strings.sync_compatibility_no_trusted_source
                SyncCompatibilityFeedback.SELECTING -> MR.strings.sync_compatibility_selecting
                SyncCompatibilityFeedback.VERIFIED -> MR.strings.sync_compatibility_verified
                SyncCompatibilityFeedback.REJECTED -> MR.strings.sync_compatibility_rejected
                SyncCompatibilityFeedback.MANUAL_ONLY -> MR.strings.sync_compatibility_manual_only
                SyncCompatibilityFeedback.CANCELLED -> MR.strings.sync_compatibility_cancelled
                SyncCompatibilityFeedback.RESTART_REQUIRED -> MR.strings.sync_compatibility_restart
                else -> MR.strings.sync_recovery_platform_failed
            }
            Text(
                syncString(message),
                Modifier.testTag(
                    if (feedback == SyncCompatibilityFeedback.NO_TRUSTED_SOURCE) {
                        "sync-compatibility-no-trusted-source"
                    } else {
                        "sync-compatibility-feedback"
                    },
                ),
            )
        }
        Button(
            onClick = onCheck,
            enabled = feedback != SyncCompatibilityFeedback.SELECTING,
            modifier = Modifier.testTag("sync-compatibility-check"),
        ) {
            Text(syncString(MR.strings.sync_recovery_update))
        }
        Button(
            onClick = onChoose,
            enabled = feedback != SyncCompatibilityFeedback.SELECTING,
            modifier = Modifier.testTag("sync-compatibility-local-package"),
        ) {
            Text(syncString(MR.strings.sync_compatibility_choose))
        }
        if (feedback == SyncCompatibilityFeedback.VERIFIED) {
            Button(onClick = onInstall, modifier = Modifier.testTag("sync-compatibility-install")) {
                Text(syncString(MR.strings.sync_compatibility_install))
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("sync-native-recovery-return")) {
            Text(syncString(MR.strings.sync_recovery_platform_return))
        }
    }
}
