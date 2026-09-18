package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.suggestion.SuggestionBatchItem
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.SuggestionBatchState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The caller owns the frozen package list and revalidates replacements and source conflicts. */
data class ExtensionSuggestionBatchConfirmation(
    val artifacts: List<ExtensionArtifact>,
    val unavailablePackages: Set<String> = emptySet(),
    val replacements: Map<String, List<ExtensionArtifact>> = emptyMap(),
    val hasSourceConflict: Boolean = false,
)

/** Render outside the collapsible suggestion list so completed and paused results remain reachable. */
@Composable
internal fun ExtensionSuggestionBatchSection(
    batch: SuggestionBatchState,
    canStart: Boolean,
    matchingSearch: Boolean,
    onRequestStart: () -> Unit,
    onRequestResume: () -> Unit,
    onRequestRetry: () -> Unit,
    onStop: () -> Unit,
    pauseExplanation: String? = null,
    onResolvePause: (() -> Unit)? = null,
) {
    val canBegin = canStart && !batch.running && batch.remaining.isEmpty()
    if (!canBegin && batch.items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (canBegin) {
            TextButton(onClick = onRequestStart) {
                Text(
                    stringResource(
                        if (matchingSearch) {
                            MR.strings.extension_batch_install_matching
                        } else {
                            MR.strings.extension_batch_install_all
                        },
                    ),
                )
            }
        }
        if (batch.items.isNotEmpty()) {
            Text(stringResource(MR.strings.extension_batch_progress, batch.completed, batch.items.size))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                items(batch.items, key = { it.artifact.packageName }) { item ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${item.artifact.name} · ${item.artifact.versionName}")
                        Text(batchResultText(item), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            pauseExplanation?.let { Text(it) }
            onResolvePause?.let { action ->
                TextButton(onClick = action) { Text(stringResource(MR.strings.action_settings)) }
            }
            when {
                batch.running -> TextButton(onClick = onStop, enabled = !batch.stopping) {
                    Text(stringResource(MR.strings.extension_batch_stop))
                }
                batch.remaining.isNotEmpty() -> {
                    Text(stringResource(MR.strings.extension_batch_reconfirm))
                    TextButton(onClick = onRequestResume) { Text(stringResource(MR.strings.extension_batch_review)) }
                    TextButton(onClick = onStop) { Text(stringResource(MR.strings.extension_batch_stop)) }
                }
                batch.items.any { it.result is SuggestionBatchResult.Failed } -> {
                    TextButton(onClick = onRequestRetry) { Text(stringResource(MR.strings.extension_batch_retry)) }
                }
            }
        }
    }
}

/** A rejected transaction stays open for review; this dialog never starts an unconfirmed replacement. */
@Composable
internal fun ExtensionSuggestionBatchDialog(
    confirmation: ExtensionSuggestionBatchConfirmation,
    onConfirm: (List<ExtensionArtifact>) -> Boolean,
    onSelectReplacement: (ExtensionArtifact) -> Unit,
    onDismiss: () -> Unit,
) {
    var rejected by remember(confirmation.artifacts) { mutableStateOf(false) }
    val missing = confirmation.artifacts.any { it.packageName in confirmation.unavailablePackages }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.extension_batch_confirm)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (confirmation.hasSourceConflict) {
                    item { Text(stringResource(MR.strings.extension_batch_conflict)) }
                }
                if (missing || rejected) {
                    item { Text(stringResource(MR.strings.extension_batch_unavailable)) }
                }
                items(confirmation.artifacts, key = { it.packageName }) { artifact ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        BatchArtifactIdentity(artifact)
                        if (artifact.packageName in confirmation.unavailablePackages) {
                            confirmation.replacements[artifact.packageName].orEmpty().forEach { candidate ->
                                BatchArtifactIdentity(candidate)
                                TextButton(onClick = { onSelectReplacement(candidate) }) {
                                    Text(stringResource(MR.strings.extension_batch_use_repository))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    rejected = !onConfirm(confirmation.artifacts)
                    if (!rejected) onDismiss()
                },
                enabled = confirmation.artifacts.isNotEmpty() && !missing && !confirmation.hasSourceConflict,
            ) {
                Text(stringResource(MR.strings.extension_batch_install_count, confirmation.artifacts.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun BatchArtifactIdentity(artifact: ExtensionArtifact) {
    Text("${artifact.name} · ${artifact.versionName}")
    Text(artifact.repository.baseUrl, style = MaterialTheme.typography.bodySmall)
    Text(artifact.repository.signingKeyFingerprint, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun batchResultText(item: SuggestionBatchItem): String = when (val result = item.result) {
    SuggestionBatchResult.Installed -> stringResource(MR.strings.ext_installed)
    SuggestionBatchResult.Busy -> stringResource(MR.strings.extension_batch_busy)
    SuggestionBatchResult.Stopped -> stringResource(MR.strings.extension_batch_stopped)
    SuggestionBatchResult.Cancelled -> stringResource(MR.strings.cancelled)
    is SuggestionBatchResult.Paused -> stringResource(MR.strings.paused)
    is SuggestionBatchResult.Invalidated -> stringResource(MR.strings.extension_batch_changed)
    is SuggestionBatchResult.Failed -> stringResource(MR.strings.extension_batch_failed) + ": " + stringResource(
        extensionInstallErrorMessage(result.error) ?: MR.strings.extension_install_error_unknown,
    )
    null -> stringResource(
        when (item.progress) {
            null, ExtensionInstallState.Queued -> MR.strings.ext_pending
            ExtensionInstallState.Preparing -> MR.strings.ext_downloading
            else -> MR.strings.ext_installing
        },
    )
}
