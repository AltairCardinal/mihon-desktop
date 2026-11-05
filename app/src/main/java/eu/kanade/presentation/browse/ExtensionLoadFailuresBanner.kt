package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.extension.model.ExtensionLoadFailure
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ExtensionLoadFailuresBanner(
    failures: Map<String, ExtensionLoadFailure>,
    retrying: Set<String>,
    onRetry: (ExtensionLoadFailure) -> Unit,
) {
    if (failures.isEmpty()) return
    Column {
        WarningBanner(MR.strings.source_error_extension_incompatible)
        failures.values.forEach { failure ->
            Text(
                "${failure.name} · ${failure.versionName}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { onRetry(failure) },
                enabled = failure.pkgName !in retrying,
                modifier = Modifier.testTag("extension-load-retry-${failure.pkgName}"),
            ) {
                Text(stringResource(MR.strings.action_retry))
            }
        }
    }
}
