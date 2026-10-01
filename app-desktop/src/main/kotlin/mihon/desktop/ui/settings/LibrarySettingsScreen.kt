package mihon.desktop.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.LibraryUpdateInterval
import mihon.desktop.ui.library.categoryDialogEscape
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR

class LibrarySettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences
        val getCategories = LocalDesktopUiDependencies.current.getCategories
        val libraryPreferences = LocalDesktopUiDependencies.current.libraryPreferences
        val updateInterval by libraryPreferences?.autoUpdateInterval()?.changes()?.collectAsState(
            initial = libraryPreferences.autoUpdateInterval().get(),
        ) ?: remember { mutableStateOf(prefs.libraryUpdateInterval.get().hours.toInt()) }
        val hideMissingChapterIndicators by prefs.hideMissingChapterIndicators.changes().collectAsState(
            initial = prefs.hideMissingChapterIndicators.get(),
        )
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
        var categoriesLoaded by remember { mutableStateOf(false) }
        var showDownloadBadge by remember { mutableStateOf(libraryPreferences?.downloadBadge()?.get() ?: false) }
        var showUnreadBadge by remember { mutableStateOf(libraryPreferences?.unreadBadge()?.get() ?: true) }
        var showLocalBadge by remember { mutableStateOf(libraryPreferences?.localBadge()?.get() ?: true) }
        var showLanguageBadge by remember { mutableStateOf(libraryPreferences?.languageBadge()?.get() ?: false) }
        var showContinueReading by remember {
            mutableStateOf(libraryPreferences?.showContinueReadingButton()?.get() ?: false)
        }
        var showCategoryTabs by remember { mutableStateOf(libraryPreferences?.categoryTabs()?.get() ?: true) }
        var showCategoryCounts by remember {
            mutableStateOf(libraryPreferences?.categoryNumberOfItems()?.get() ?: false)
        }
        val categorizedDisplaySettings by libraryPreferences?.categorizedDisplaySettings()?.changes()?.collectAsState(
            initial = libraryPreferences.categorizedDisplaySettings().get(),
        ) ?: remember { mutableStateOf(false) }
        val categorySortSettings = LocalDesktopUiDependencies.current.categorySortSettings
        val resetFailed by categorySortSettings?.failed?.collectAsState() ?: remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var sortingBusy by remember { mutableStateOf(false) }
        var intervalFailed by remember { mutableStateOf(false) }
        val invalidInterval by prefs.libraryUpdateIntervalMigrationInvalid.changes().collectAsState(
            initial = prefs.libraryUpdateIntervalMigrationInvalid.get(),
        )
        val updateTitle = MR.strings.pref_category_library_update.localized()
        val displayTitle = MR.strings.pref_category_display.localized()

        LaunchedEffect(getCategories) {
            getCategories.subscribe().collect {
                categories = it.filterNot(Category::isSystemCategory)
                categoriesLoaded = true
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_library.localized()) },
                    navigationIcon = {
                        mihon.desktop.ui.settings.SettingsNavigationIcon()
                    },
                )
            },
        ) { padding ->
            DesktopSettingsAnchorColumn(
                route = this@LibrarySettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                libraryPreferences?.let { DefaultLibraryCategorySettings(it, categories) }
                Text(
                    text = updateTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(updateTitle).padding(horizontal = 16.dp, vertical = 8.dp),
                )

                val intervalLabels = mapOf(
                    LibraryUpdateInterval.OFF to MR.strings.update_never.localized(),
                    LibraryUpdateInterval.EVERY_6H to MR.strings.update_6hour.localized(),
                    LibraryUpdateInterval.EVERY_12H to MR.strings.update_12hour.localized(),
                    LibraryUpdateInterval.EVERY_24H to MR.strings.update_24hour.localized(),
                    LibraryUpdateInterval.WEEKLY to MR.strings.update_weekly.localized(),
                )
                LibraryUpdateInterval.entries.forEach { interval ->
                    RadioSettingsItem(
                        title = intervalLabels[interval] ?: interval.name,
                        selected = updateInterval == interval.hours.toInt(),
                        onClick = {
                            libraryPreferences?.let {
                                intervalFailed =
                                    !mihon.desktop.settings.saveDesktopPreference(
                                        it.autoUpdateInterval(),
                                        interval.hours.toInt(),
                                    )
                                if (!intervalFailed) {
                                    try {
                                        prefs.libraryUpdateIntervalMigrationInvalid.delete()
                                    } catch (
                                        _: Exception,
                                    ) {
                                        intervalFailed =
                                            true
                                    }
                                }
                            }
                        },
                    )
                }
                if (updateInterval !in LibraryUpdateInterval.entries.map { it.hours.toInt() }) {
                    Text(
                        "${MR.strings.pref_library_update_interval.localized()}: $updateInterval h",
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (intervalFailed) Text(MR.strings.internal_error.localized(), color = MaterialTheme.colorScheme.error)
                if (invalidInterval) {
                    Text(
                        MR.strings.desktop_library_update_interval_invalid.localized(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LocalDesktopUiDependencies.current.libraryCategoryPolicy?.let { policy ->
                    libraryPreferences?.let { LibraryUpdateCategorySettings(policy, it, categories, categoriesLoaded) }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = MR.strings.pref_behavior.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = MR.strings.desktop_library_manual_refresh_summary.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                AuthorDiscoverySettingsSection(
                    scheduler = LocalDesktopUiDependencies.current.creatorDiscoveryScheduler,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(
                        displayTitle,
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val missingChapterIndicatorItem = missingChapterIndicatorSettingsItem(
                    prefs = prefs,
                    checked = hideMissingChapterIndicators,
                ).copy(title = MR.strings.pref_hide_missing_chapter_indicators.localized())
                CheckboxSettingsRow(
                    title = missingChapterIndicatorItem.title,
                    checked = missingChapterIndicatorItem.checked,
                    onCheckedChange = missingChapterIndicatorItem.onCheckedChange,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = MR.strings.action_display_mode.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                libraryPreferences?.let { LibraryColumnControls(it) }
                listOf(
                    Triple(
                        MR.strings.action_display_download_badge.localized(),
                        showDownloadBadge,
                    ) { checked: Boolean ->
                        showDownloadBadge = checked
                        libraryPreferences?.downloadBadge()?.set(checked)
                        Unit
                    },
                    Triple(MR.strings.action_display_unread_badge.localized(), showUnreadBadge) { checked: Boolean ->
                        showUnreadBadge = checked
                        libraryPreferences?.unreadBadge()?.set(checked)
                        Unit
                    },
                    Triple(MR.strings.action_display_local_badge.localized(), showLocalBadge) { checked: Boolean ->
                        showLocalBadge = checked
                        libraryPreferences?.localBadge()?.set(checked)
                        Unit
                    },
                    Triple(
                        MR.strings.action_display_language_badge.localized(),
                        showLanguageBadge,
                    ) { checked: Boolean ->
                        showLanguageBadge = checked
                        libraryPreferences?.languageBadge()?.set(checked)
                        Unit
                    },
                    Triple(
                        MR.strings.action_display_show_continue_reading_button.localized(),
                        showContinueReading,
                    ) { checked: Boolean ->
                        showContinueReading = checked
                        libraryPreferences?.showContinueReadingButton()?.set(checked)
                        Unit
                    },
                ).forEach { (title, checked, onCheckedChange) ->
                    CheckboxSettingsRow(title, checked, onCheckedChange = onCheckedChange)
                }
                CheckboxSettingsRow(
                    title = MR.strings.action_display_show_tabs.localized(),
                    checked = showCategoryTabs,
                    onCheckedChange = { checked ->
                        showCategoryTabs = checked
                        libraryPreferences?.categoryTabs()?.set(checked)
                    },
                )
                CheckboxSettingsRow(
                    title = MR.strings.action_display_show_number_of_items.localized(),
                    checked = showCategoryCounts,
                    onCheckedChange = { checked ->
                        showCategoryCounts = checked
                        libraryPreferences?.categoryNumberOfItems()?.set(checked)
                    },
                )
                CheckboxSettingsRow(
                    title = MR.strings.categorized_display_settings.localized(),
                    checked = categorizedDisplaySettings,
                    modifier = Modifier.desktopSettingsAnchor(MR.strings.categorized_display_settings.localized()),
                    onCheckedChange = { checked ->
                        if (!sortingBusy && categorySortSettings != null) {
                            sortingBusy = true
                            scope.launch {
                                try {
                                    categorySortSettings.set(checked)
                                } finally {
                                    sortingBusy = false
                                }
                            }
                        }
                    },
                )
                if (resetFailed) Text(MR.strings.internal_error.localized(), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun CheckboxSettingsRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    val description = if (checked) MR.strings.on.localized() else MR.strings.off.localized()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = description
            }
            .desktopSettingsAction(Role.Checkbox) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(title, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun DefaultLibraryCategorySettings(
    preferences: tachiyomi.domain.library.service.LibraryPreferences,
    categories: List<Category>,
) {
    val preference = remember(preferences) { preferences.defaultCategory() }
    val current by preference.changes().collectAsState(initial = preference.get())
    var visible by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(0) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val dialogFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val title = MR.strings.default_category.localized()
    val choices =
        listOf(-1 to MR.strings.default_category_summary.localized(), 0 to MR.strings.label_default.localized()) +
            categories.map { it.id.toInt() to it.name }
    fun close() {
        visible = false
        returnFocus++
    }
    LaunchedEffect(returnFocus) {
        if (returnFocus > 0) {
            androidx.compose.runtime.withFrameNanos { }
            focus.requestFocus()
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            androidx.compose.runtime.withFrameNanos { }
            dialogFocus.requestFocus()
        }
    }
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(choices.firstOrNull { it.first == current }?.second ?: choices.first().second) },
        modifier = Modifier.desktopSettingsAnchor(title)
            .then(Modifier.focusRequester(focus))
            .desktopSettingsAction(Role.Button) {
                failed = false
                visible = true
            },
    )
    if (visible) {
        androidx.compose.material3.AlertDialog(
            modifier = Modifier.categoryDialogEscape(true, ::close).focusRequester(dialogFocus).focusable(),
            onDismissRequest = ::close,
            title = { Text(title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (failed) Text(MR.strings.internal_error.localized(), color = MaterialTheme.colorScheme.error)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(choices.size) { index ->
                            val choice = choices[index]
                            RadioSettingsItem(choice.second, choice.first == current, onClick = {
                                if (choice.first != current) {
                                    failed = !mihon.desktop.settings.saveDesktopPreference(preference, choice.first)
                                    if (!failed) close()
                                }
                            })
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = ::close) { Text(MR.strings.action_cancel.localized()) }
            },
        )
    }
}
