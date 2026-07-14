package mihon.feature.migration.list.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.feature.migration.list.MigrationListScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MigrationFailureDialog(
    failures: List<MigrationListScreenModel.BatchFailure>,
    retry: () -> Unit,
    close: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(MR.strings.migrationListScreen_failureDialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                failures.forEachIndexed { index, failure ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    Text(failure.title, style = MaterialTheme.typography.titleSmall)
                    Text(failure.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = retry) {
                Text(stringResource(MR.strings.action_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = close) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}
