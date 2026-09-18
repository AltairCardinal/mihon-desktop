package eu.kanade.tachiyomi.ui.browse.author

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import tachiyomi.domain.creator.service.CreatorIdentityEditor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun CreatorNameLink(
    name: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).alpha(0.78f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(16.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatorIdentityHeader(
    editor: CreatorIdentityEditor,
    fallbackName: String = "",
    modifier: Modifier = Modifier,
) {
    val state by editor.state.collectAsState()
    val titleFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val aliasesFocus = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(state.focusSequence) {
        if (state.focusSequence > 0) {
            withFrameNanos { }
            when {
                state.focusTarget == "title" -> titleFocus.requestFocus()
                state.focusTarget == "add" -> addFocus.requestFocus()
                state.focusTarget.startsWith(
                    "alias:",
                ) -> aliasesFocus[state.focusTarget.removePrefix("alias:")]?.requestFocus()
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            state.identity?.displayName ?: fallbackName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() },
        )
        if (state.loading && state.identity == null) CircularProgressIndicator(Modifier.size(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.identity?.aliases.orEmpty().forEach { name ->
                CreatorNameLink(
                    name,
                    Modifier.focusRequester(aliasesFocus.getOrPut(name) { FocusRequester() }),
                    enabled = !state.submitting,
                ) { editor.chooseDisplayName(name) }
            }
        }
        TextButton(
            onClick = { editor.openAliases() },
            enabled = !state.submitting,
            modifier = Modifier.focusRequester(addFocus),
        ) {
            Text(stringResource(MR.strings.desktop_ui_add_author_alias))
        }
        state.feedback?.let {
            Text(
                stringResource(
                    if (it ==
                        "已添加别名"
                    ) {
                        MR.strings.creator_identity_aliases_added
                    } else {
                        MR.strings.creator_identity_name_saved
                    },
                ),
            )
        }
        if (!state.open && state.pendingName == null) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = editor::retryIdentity) { Text(stringResource(MR.strings.action_retry)) }
            }
        }
    }
    if (state.open) {
        val searchFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = editor::dismiss,
            properties = DialogProperties(
                dismissOnBackPress = !state.submitting,
                dismissOnClickOutside = !state.submitting,
            ),
            title = { Text(stringResource(MR.strings.desktop_ui_add_author_alias)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(MR.strings.creator_alias_picker_hint))
                    OutlinedTextField(
                        state.query,
                        editor::search,
                        enabled = !state.submitting,
                        label = { Text(stringResource(MR.strings.action_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                    )
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
                    if (!state.loading && state.candidates?.candidates?.isEmpty() == true) {
                        Text(stringResource(MR.strings.desktop_ui_no_other_author_identities))
                    }
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(state.visibleCandidates, key = { it.id }) { row ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !state.submitting) { editor.select(row.id) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(row.id in state.selected, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        row.representativeTitle
                                            ?: stringResource(MR.strings.creator_no_representative_work),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (row.followed) {
                                        Text(
                                            stringResource(MR.strings.desktop_ui_followed),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.selected.isEmpty()) Text(stringResource(MR.strings.creator_alias_select_required))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.error != null ||
                        state.stale
                    ) {
                        TextButton(onClick = { editor.refreshCandidates() }, enabled = !state.submitting) {
                            Text(stringResource(MR.strings.creator_identity_refresh))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editor.submit()
                }, enabled = state.canSubmit) { Text(stringResource(MR.strings.action_add)) }
            },
            dismissButton = {
                TextButton(onClick = editor::dismiss, enabled = !state.submitting) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
    state.pendingName?.let { name ->
        AlertDialog(
            onDismissRequest = editor::cancelDisplayName,
            properties = DialogProperties(
                dismissOnBackPress = !state.submitting,
                dismissOnClickOutside = !state.submitting,
            ),
            text = {
                Column {
                    Text(stringResource(MR.strings.creator_display_name_confirm, name))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.stale) {
                        TextButton(onClick = {
                            editor.refreshDisplayName()
                        }) { Text(stringResource(MR.strings.creator_identity_refresh)) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { editor.saveDisplayName() },
                    enabled =
                    !state.submitting && !state.stale,
                ) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = editor::cancelDisplayName, enabled = !state.submitting) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun CreatorWorkFilters(
    query: String,
    sourceId: Long?,
    sources: Map<Long, String>,
    onSearch: (String) -> Unit,
    onSource: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { expanded = true }) {
                Text(sources[sourceId] ?: stringResource(MR.strings.creator_work_sources_all))
            }
            androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(stringResource(MR.strings.creator_work_sources_all))
                    },
                    onClick = {
                        expanded = false
                        onSource(null)
                    },
                )
                sources.forEach { (id, name) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onSource(id)
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            query,
            onSearch,
            modifier = Modifier.fillMaxWidth().testTag("creator-work-search"),
            label = { Text(stringResource(MR.strings.creator_work_search)) },
            singleLine = true,
        )
    }
}
