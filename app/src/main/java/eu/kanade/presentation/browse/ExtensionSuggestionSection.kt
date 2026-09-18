package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.suggestion.ExtensionSuggestionPanel
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelState
import mihon.domain.extension.suggestion.SuggestionProblem
import mihon.domain.extension.suggestion.suggestionIdentityKey
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** A bounded view of the shared panel; source matching and selection remain in domain. */
@Composable
internal fun ExtensionSuggestionSection(
    state: SuggestionPanelState,
    controller: ExtensionSuggestionPanel?,
    onInstall: (SuggestionIdentity) -> Unit,
    onWebsite: (ExtensionSourceDescriptor) -> Unit,
    onRepositories: () -> Unit,
    onMigrate: (Long) -> Unit,
    onDiagnose: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    errors: Map<String, AppError> = emptyMap(),
) {
    DisposableEffect(controller) { onDispose { controller?.dismissUndo() } }
    if (!state.loading && state.total == 0 && state.unmatched.isEmpty() && !state.incomplete && !state.canUndo) return
    var chooseSource by remember { mutableStateOf<Long?>(null) }
    var websites by remember { mutableStateOf<List<ExtensionSourceDescriptor>>(emptyList()) }
    val expandDescription = suggestionText(
        if (state.expanded) MR.strings.extension_suggestions_collapse else MR.strings.extension_suggestions_expand,
    )
    Column(modifier.fillMaxWidth().padding(12.dp)) {
        TextButton(
            onClick = { controller?.toggle() },
            modifier = Modifier.semantics { stateDescription = expandDescription },
        ) {
            Text("${suggestionText(MR.strings.extension_suggestions_title)} (${state.total})")
        }
        if (state.activeCount > 0) {
            Text(suggestionText(MR.strings.extension_suggestions_active, state.activeCount))
        }
        if (state.canUndo) {
            FlowRow {
                Text(suggestionText(MR.strings.extension_suggestions_ignored))
                TextButton(onClick = { controller?.undo() }) { Text(suggestionText(MR.strings.action_undo)) }
            }
        }
        if (state.expanded) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading) item { Text(suggestionText(MR.strings.loading)) }
                if (state.incomplete) {
                    item {
                        Text(suggestionText(MR.strings.extension_catalog_cached_failure))
                        TextButton(onClick = onRefresh) { Text(suggestionText(MR.strings.action_retry)) }
                    }
                }
                if (state.choices.isNotEmpty()) {
                    item {
                        Text(
                            suggestionText(MR.strings.extension_suggestions_choice_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.choices.forEach { (id, candidates) ->
                            val source = candidates.flatMap { it.sources }.firstOrNull { it.source.id == id }?.source
                            val selected = candidates.find { it.identity == state.selected[id] }
                            val title = suggestionText(
                                MR.strings.extension_suggestions_choose,
                                source?.name ?: id.toString(),
                            )
                            TextButton(onClick = { chooseSource = id }) {
                                Text(title + (selected?.let { " · ${it.artifact.repository.name}" } ?: ""))
                            }
                        }
                    }
                }
                if (!state.loading && state.total > 0 && state.rows.isEmpty()) {
                    item { Text(suggestionText(MR.strings.no_results_found)) }
                }
                items(state.rows, key = { suggestionIdentityKey(it.suggestion.identity) }) { row ->
                    val suggestion = row.suggestion
                    val artifact = suggestion.artifact
                    Column(Modifier.fillMaxWidth()) {
                        Text(artifact.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            suggestionText(
                                MR.strings.extension_suggestions_count,
                                suggestion.mangaCount,
                                suggestion.sources.size,
                            ),
                        )
                        Text(
                            suggestion.sources.joinToString { it.source.name },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${artifact.repository.name} · ${artifact.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!row.canIgnore) {
                            val progress = when (row.step) {
                                ExtensionPresentationInstallStep.Downloading -> MR.strings.ext_downloading
                                ExtensionPresentationInstallStep.Installing -> MR.strings.ext_installing
                                else -> MR.strings.ext_pending
                            }
                            Text(suggestionText(progress))
                        }
                        if (row.step == ExtensionPresentationInstallStep.Error) {
                            val error = errors[artifact.packageName] ?: AppError.Unknown()
                            val message = extensionInstallErrorMessage(error)
                                ?: MR.strings.extension_install_error_unknown
                            Text(suggestionText(message), color = MaterialTheme.colorScheme.error)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onInstall(suggestion.identity) }, enabled = row.canInstall) {
                                val action = if (row.step == ExtensionPresentationInstallStep.Error) {
                                    MR.strings.action_retry
                                } else {
                                    MR.strings.ext_install
                                }
                                Text(suggestionText(action))
                            }
                            TextButton(
                                onClick = {
                                    if (row.websites.size == 1) {
                                        onWebsite(row.websites.single())
                                    } else {
                                        websites = row.websites
                                    }
                                },
                                enabled = row.websites.isNotEmpty(),
                            ) {
                                Text(suggestionText(MR.strings.extension_suggestions_open_website))
                            }
                            TextButton(
                                onClick = { controller?.ignore(suggestion.identity) },
                                enabled = row.canIgnore,
                            ) {
                                Text(suggestionText(MR.strings.extension_suggestions_ignore))
                            }
                        }
                        if (row.websites.isEmpty()) {
                            Text(
                                suggestionText(MR.strings.extension_suggestions_no_website),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                items(state.unmatched, key = { "missing-${it.sourceId}" }) { missing ->
                    Column {
                        Text(
                            suggestionText(
                                MR.strings.extension_suggestions_source,
                                missing.name ?: missing.sourceId.toString(),
                                missing.count,
                            ),
                        )
                        val problem = when (missing.problem) {
                            SuggestionProblem.NOT_IN_CATALOG -> MR.strings.extension_suggestions_not_found
                            SuggestionProblem.INCOMPATIBLE -> MR.strings.extension_suggestions_incompatible
                            SuggestionProblem.CONTENT_RESTRICTED -> MR.strings.extension_suggestions_restricted
                            SuggestionProblem.INSTALLED_UNAVAILABLE ->
                                MR.strings.extension_suggestions_installed_unavailable
                            SuggestionProblem.INVENTORY_UNKNOWN -> MR.strings.extension_suggestions_inventory_unknown
                        }
                        Text(suggestionText(problem))
                        FlowRow {
                            if (missing.problem == SuggestionProblem.INSTALLED_UNAVAILABLE ||
                                missing.problem == SuggestionProblem.INVENTORY_UNKNOWN
                            ) {
                                TextButton(onClick = onDiagnose) {
                                    Text(suggestionText(MR.strings.extension_suggestions_diagnose))
                                }
                            }
                            TextButton(onClick = onRepositories) {
                                Text(suggestionText(MR.strings.label_extension_repos))
                            }
                            TextButton(onClick = { onMigrate(missing.sourceId) }) {
                                Text(suggestionText(MR.strings.label_migration))
                            }
                        }
                    }
                }
            }
        }
    }
    chooseSource?.let { sourceId ->
        val candidates = state.choices[sourceId].orEmpty()
        AlertDialog(
            onDismissRequest = { chooseSource = null },
            title = { Text(suggestionText(MR.strings.extension_suggestions_title)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { suggestionIdentityKey(it.identity) }) { candidate ->
                        TextButton(
                            onClick = {
                                controller?.choose(sourceId, candidate.identity)
                                chooseSource = null
                            },
                        ) {
                            val artifact = candidate.artifact
                            Text(
                                "${artifact.name} · ${artifact.repository.name} · ${artifact.versionName}\n" +
                                    "${artifact.repository.baseUrl}\n${artifact.repository.signingKeyFingerprint}",
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseSource = null }) { Text(suggestionText(MR.strings.action_cancel)) }
            },
        )
    }
    if (websites.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { websites = emptyList() },
            title = { Text(suggestionText(MR.strings.extension_suggestions_website)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(websites, key = { it.id }) { source ->
                        TextButton(onClick = {
                            onWebsite(source)
                            websites = emptyList()
                        }) { Text(source.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { websites = emptyList() }) { Text(suggestionText(MR.strings.action_cancel)) }
            },
        )
    }
}

@Composable
private fun suggestionText(resource: StringResource, vararg args: Any): String = stringResource(resource, *args)
