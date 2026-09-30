package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import mihon.desktop.settings.LibraryUpdateInterval
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
        val updateInterval by prefs.libraryUpdateInterval.changes().collectAsState(
            initial = prefs.libraryUpdateInterval.get(),
        )
        val hideMissingChapterIndicators by prefs.hideMissingChapterIndicators.changes().collectAsState(
            initial = prefs.hideMissingChapterIndicators.get(),
        )
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
        var excludeIds by remember {
            mutableStateOf(
                prefs.updateCategoryExcludes.get()
                    .split(",").mapNotNull { it.trim().toLongOrNull() }.toSet(),
            )
        }
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
        var categorizedDisplaySettings by remember {
            mutableStateOf(libraryPreferences?.categorizedDisplaySettings()?.get() ?: false)
        }
        val updateTitle = MR.strings.pref_category_library_update.localized()
        val displayTitle = MR.strings.pref_category_display.localized()

        LaunchedEffect(Unit) {
            categories = getCategories.await().filterNot(Category::isSystemCategory)
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
                        selected = updateInterval == interval,
                        onClick = { prefs.libraryUpdateInterval.set(interval) },
                    )
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
                    modifier = Modifier.desktopSettingsAnchor(displayTitle).padding(horizontal = 16.dp, vertical = 8.dp),
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
                    Triple(MR.strings.action_display_download_badge.localized(), showDownloadBadge) { checked: Boolean ->
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
                    Triple(MR.strings.action_display_language_badge.localized(), showLanguageBadge) { checked: Boolean ->
                        showLanguageBadge = checked
                        libraryPreferences?.languageBadge()?.set(checked)
                        Unit
                    },
                    Triple(MR.strings.action_display_show_continue_reading_button.localized(), showContinueReading) { checked: Boolean ->
                        showContinueReading = checked
                        libraryPreferences?.showContinueReadingButton()?.set(checked)
                        Unit
                    },
                ).forEach { (title, checked, onCheckedChange) ->
                    CheckboxSettingsRow(title, checked, onCheckedChange)
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
                    onCheckedChange = { checked ->
                        categorizedDisplaySettings = checked
                        libraryPreferences?.categorizedDisplaySettings()?.set(checked)
                    },
                )

                if (categories.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = MR.strings.desktop_library_excluded_categories.localized(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        text = MR.strings.desktop_library_excluded_categories_summary.localized(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    categories.forEach { cat ->
                        CheckboxSettingsRow(
                            title = cat.name,
                            checked = cat.id in excludeIds,
                            onCheckedChange = { checked ->
                                excludeIds = if (checked) excludeIds + cat.id else excludeIds - cat.id
                                prefs.updateCategoryExcludes.set(excludeIds.joinToString(","))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CheckboxSettingsRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val description = if (checked) MR.strings.on.localized() else MR.strings.off.localized()
    Row(
        modifier = Modifier
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
