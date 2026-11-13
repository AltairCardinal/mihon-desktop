package mihon.desktop.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import mihon.desktop.ui.library.categoryDialogEscape
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR

@Composable
internal fun ReadDownloadDeletionSettings(preferences: DownloadPreferences, getCategories: GetCategories) {
    val manual by preferences.removeAfterMarkedAsRead().changes().collectAsState(
        preferences.removeAfterMarkedAsRead().get(),
    )
    val slots by preferences.removeAfterReadSlots().changes().collectAsState(preferences.removeAfterReadSlots().get())
    val bookmarks by preferences.removeBookmarkedChapters().changes().collectAsState(
        preferences.removeBookmarkedChapters().get(),
    )
    val excluded by preferences.removeExcludeCategories().changes().collectAsState(
        preferences.removeExcludeCategories().get(),
    )
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    LaunchedEffect(getCategories) {
        getCategories.subscribe().collect {
            categories =
                it.filterNot(Category::isSystemCategory).distinctBy(Category::id)
        }
    }
    Text(
        MR.strings.pref_category_delete_chapters.localized(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    val manualTitle = MR.strings.pref_remove_after_marked_as_read.localized()
    SwitchSettingsItem(
        manualTitle,
        null,
        manual,
        { preferences.removeAfterMarkedAsRead().set(it) },
        Modifier.desktopSettingsAnchor(manualTitle),
    )
    val choices = listOf(
        -1 to MR.strings.disabled,
        0 to MR.strings.last_read_chapter,
        1 to MR.strings.second_to_last,
        2 to MR.strings.third_to_last,
        3 to MR.strings.fourth_to_last,
        4 to MR.strings.fifth_to_last,
    )
    ReadDeletionDialogSetting(
        MR.strings.pref_remove_after_read.localized(),
        choices.firstOrNull { it.first == slots }?.second?.localized().orEmpty(),
    ) { close ->
        choices.forEach { (value, label) ->
            RadioSettingsItem(label.localized(), value == slots, {
                preferences.removeAfterReadSlots().set(value)
                close()
            })
        }
    }
    val bookmarkTitle = MR.strings.pref_remove_bookmarked_chapters.localized()
    SwitchSettingsItem(
        bookmarkTitle,
        null,
        bookmarks,
        { preferences.removeBookmarkedChapters().set(it) },
        Modifier.desktopSettingsAnchor(bookmarkTitle),
    )
    val categoryChoices =
        listOf("0" to MR.strings.label_default.localized()) + categories.map { it.id.toString() to it.name }
    ReadDeletionDialogSetting(
        MR.strings.pref_remove_exclude_categories.localized(),
        categoryChoices.filter {
            it.first in excluded
        }.joinToString { it.second }.ifEmpty { MR.strings.none.localized() },
    ) { close ->
        var selected by remember { mutableStateOf(excluded) }
        categoryChoices.forEach { (id, name) ->
            CheckboxSettingsRow(name, id in selected, onCheckedChange = {
                selected = if (it) selected + id else selected - id
            })
        }
        TextButton(onClick = {
            preferences.removeExcludeCategories().set(selected)
            close()
        }) {
            Text(MR.strings.action_ok.localized())
        }
    }
}

/** Same immediate preference and draft category interaction used by the existing library settings. */
@Composable
private fun ReadDeletionDialogSetting(
    title: String,
    summary: String,
    content: @Composable (() -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var returned by remember { mutableStateOf(0) }
    val trigger = remember { FocusRequester() }
    val dialog = remember { FocusRequester() }
    fun close() {
        visible = false
        returned++
    }
    LaunchedEffect(returned) {
        if (returned > 0) {
            withFrameNanos {}
            trigger.requestFocus()
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            withFrameNanos {}
            dialog.requestFocus()
        }
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier.desktopSettingsAnchor(title).focusRequester(trigger).desktopSettingsAction(Role.Button) {
            visible =
                true
        },
    )
    if (visible) {
        AlertDialog(
            modifier = Modifier.categoryDialogEscape(true, ::close).focusRequester(dialog).focusable(),
            onDismissRequest = ::close,
            title = { Text(title) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Column { content(::close) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = ::close) { Text(MR.strings.action_cancel.localized()) } },
        )
    }
}
