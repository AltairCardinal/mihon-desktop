package mihon.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopLibraryCategoryPolicy
import mihon.desktop.ui.library.categoryDialogEscape
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR

@Composable
internal fun LibraryUpdateCategorySettings(
    policy: DesktopLibraryCategoryPolicy,
    preferences: LibraryPreferences,
    categories: List<Category>,
    categoriesLoaded: Boolean,
) {
    val state by policy.state.collectAsState()
    val scope = rememberCoroutineScope()
    val triggerFocus = remember { FocusRequester() }
    val dialogFocus = remember { FocusRequester() }
    var visible by remember { mutableStateOf(false) }
    var included by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var excluded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(0) }
    val title = MR.strings.categories.localized()
    val choices = listOf(0L to MR.strings.label_default.localized()) + categories.map { it.id to it.name }
    val blocked = (state as? DesktopLibraryCategoryPolicy.State.Unavailable)?.recoveryRequired == true
    fun close() {
        visible = false
        returnFocus++
    }
    fun showEditor() {
        val validIds = choices.map { it.first }.toSet()
        included = preferences.updateCategories().get().mapNotNull(String::toLongOrNull).toSet().intersect(validIds)
        excluded =
            preferences.updateCategoriesExclude().get().mapNotNull(String::toLongOrNull).toSet().intersect(validIds)
        failed = false
        visible = true
    }

    LaunchedEffect(returnFocus) {
        if (returnFocus > 0) {
            withFrameNanos { }
            triggerFocus.requestFocus()
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            withFrameNanos { }
            dialogFocus.requestFocus()
        }
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(MR.strings.pref_library_update_categories_details.localized()) },
        modifier = Modifier.desktopSettingsAnchor(title).focusRequester(triggerFocus)
            .desktopSettingsActivationKeys(Role.Button, enabled = !blocked && categoriesLoaded, onClick = ::showEditor)
            .clickable(enabled = !blocked && categoriesLoaded, role = Role.Button, onClick = ::showEditor),
    )
    if (state is DesktopLibraryCategoryPolicy.State.Unavailable) {
        Text(
            text = if (blocked) {
                MR.strings.desktop_library_update_scope_recovery.localized()
            } else {
                MR.strings.desktop_library_update_scope_invalid.localized()
            },
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (blocked) {
            TextButton(onClick = { scope.launch { policy.recover() } }) { Text(MR.strings.action_retry.localized()) }
        }
    }
    if (!visible) return
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(!busy, ::close).focusRequester(dialogFocus).focusable(),
        onDismissRequest = { if (!busy) close() },
        title = { Text(title) },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(choices, key = { it.first }) { (id, name) ->
                        val toggleState = when (id) {
                            in excluded -> ToggleableState.Indeterminate
                            in included -> ToggleableState.On
                            else -> ToggleableState.Off
                        }
                        val description = when (toggleState) {
                            ToggleableState.On -> MR.strings.desktop_ui_filter_include.localized()
                            ToggleableState.Indeterminate -> MR.strings.desktop_ui_filter_exclude.localized()
                            ToggleableState.Off -> MR.strings.none.localized()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().semantics { stateDescription = description }
                                .triStateToggleable(toggleState, enabled = !busy, role = Role.Checkbox) {
                                    when (toggleState) {
                                        ToggleableState.Off -> included = included + id
                                        ToggleableState.On -> {
                                            included = included - id
                                            excluded = excluded + id
                                        }
                                        ToggleableState.Indeterminate -> {
                                            included = included - id
                                            excluded =
                                                excluded - id
                                        }
                                    }
                                }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(toggleState, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                            Text(name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (failed) Text(MR.strings.internal_error.localized(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !blocked, onClick = {
                busy = true
                scope.launch {
                    val saved = policy.save(included, excluded)
                    busy = false
                    failed = !saved
                    if (saved) close()
                }
            }) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = ::close) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}
