package mihon.desktop.ui.extension

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.suggestion.SuggestionBatchItem
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.service.ExtensionInstallState
import tachiyomi.i18n.MR
import java.util.Locale

/** Batch feedback remains visible when the suggestions are collapsed or installed rows disappear. */
@Composable
internal fun ExtensionSuggestionBatchSection(model: ExtensionsScreenModel) {
    extensionSuggestionBatchContent(model)()
}

/** Keep confirmations with the page even when the batch summary is scrolled out of its lazy viewport. */
@Composable
internal fun extensionSuggestionBatchContent(model: ExtensionsScreenModel): @Composable () -> Unit {
    val screen by model.state.collectAsState()
    val batch by model.suggestionBatch.state.collectAsState()
    var confirmation by remember { mutableStateOf<List<ExtensionArtifact>?>(null) }
    var retry by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    val canStart = !batch.running && batch.remaining.isEmpty() && screen.suggestionPanel.rows.any { it.canInstall }
    if (!canStart && batch.items.isEmpty()) return {}

    confirmation?.let { snapshot ->
        val conflict = model.batchSourcesConflict(snapshot, resume || retry)
        val missing = snapshot.any { !model.isCurrentBatchArtifact(it) }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(MR.strings.extension_batch_confirm.localized()) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (conflict) item { Text(MR.strings.extension_batch_conflict.localized()) }
                    if (missing || rejected) item { Text(MR.strings.extension_batch_unavailable.localized()) }
                    items(snapshot, key = { it.packageName }) { artifact ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text("${artifact.name} · ${artifact.versionName}")
                            Text(artifact.repository.baseUrl)
                            Text(artifact.repository.signingKeyFingerprint)
                            if (!model.isCurrentBatchArtifact(artifact)) {
                                model.batchReplacementCandidates(artifact).forEach { candidate ->
                                    Text("${candidate.name} · ${candidate.versionName}")
                                    Text(candidate.repository.baseUrl)
                                    Text(candidate.repository.signingKeyFingerprint)
                                    TextButton(onClick = {
                                        rejected = false
                                        confirmation = snapshot.map { if (it.packageName == candidate.packageName) candidate else it }
                                    }) { Text(MR.strings.extension_batch_use_repository.localized()) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = when {
                        resume -> model.suggestionBatch.resume(snapshot)
                        retry -> model.suggestionBatch.retryFailed(snapshot)
                        else -> model.confirmSuggestionBatch(snapshot)
                    }
                    rejected = !accepted
                    if (accepted) confirmation = null
                }, enabled = snapshot.isNotEmpty() && !batch.running && !conflict && !missing) {
                    Text(MR.strings.extension_batch_install_count.localized(Locale.getDefault(), snapshot.size))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(MR.strings.action_cancel.localized()) }
            },
        )
    }
    return {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (canStart) {
                TextButton(onClick = { retry = false; resume = false; confirmation = model.suggestionSnapshot() }) {
                    Text(
                        if (screen.searchQuery.isBlank()) MR.strings.extension_batch_install_all.localized()
                        else MR.strings.extension_batch_install_matching.localized(),
                    )
                }
            }
            if (batch.items.isNotEmpty()) {
                Text(MR.strings.extension_batch_progress.localized(Locale.getDefault(), batch.completed, batch.items.size))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(batch.items, key = { it.artifact.packageName }) { item ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${item.artifact.name} · ${item.artifact.versionName}")
                            Text(item.resultText())
                        }
                    }
                }
                if (!batch.running && batch.remaining.isEmpty() && batch.items.any { it.result is SuggestionBatchResult.Failed }) {
                    TextButton(onClick = {
                        retry = true
                        resume = false
                        confirmation = model.updatedBatchSnapshot(batch.items.filter { it.result is SuggestionBatchResult.Failed }.map { it.artifact })
                    }) { Text(MR.strings.extension_batch_retry.localized()) }
                }
                if (!batch.running && batch.remaining.isNotEmpty()) {
                    Text(MR.strings.extension_batch_reconfirm.localized())
                    TextButton(onClick = {
                        retry = false
                        resume = true
                        confirmation = model.updatedBatchSnapshot(batch.remaining)
                    }) { Text(MR.strings.extension_batch_review.localized()) }
                    TextButton(onClick = { model.suggestionBatch.stop() }) {
                        Text(MR.strings.extension_batch_stop.localized())
                    }
                }
                if (batch.running) {
                    TextButton(onClick = { model.suggestionBatch.stop() }, enabled = !batch.stopping) {
                        Text(MR.strings.extension_batch_stop.localized())
                    }
                }
            }
        }
    }
}

private fun SuggestionBatchItem.resultText(): String = when (val outcome = result) {
    SuggestionBatchResult.Installed -> MR.strings.ext_installed.localized()
    SuggestionBatchResult.Busy -> MR.strings.extension_batch_busy.localized()
    SuggestionBatchResult.Stopped -> MR.strings.extension_batch_stopped.localized()
    SuggestionBatchResult.Cancelled -> MR.strings.cancelled.localized()
    is SuggestionBatchResult.Failed -> MR.strings.extension_batch_failed.localized() + ": " +
        extensionInstallErrorCopy(artifact.name, outcome.error)
    is SuggestionBatchResult.Invalidated -> MR.strings.extension_batch_changed.localized()
    is SuggestionBatchResult.Paused -> MR.strings.paused.localized()
    null -> when (progress) {
        null, ExtensionInstallState.Queued -> MR.strings.ext_pending.localized()
        ExtensionInstallState.Preparing -> MR.strings.ext_downloading.localized()
        else -> MR.strings.ext_installing.localized()
    }
}
