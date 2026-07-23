package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import mihon.desktop.settings.DesktopAppPreferences
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
        val updateTitle = MR.strings.pref_category_library_update.localized()
        val displayTitle = MR.strings.pref_category_display.localized()

        LaunchedEffect(Unit) {
            categories = getCategories.await()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_library.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = MR.strings.action_bar_up_description.localized(),
                            )
                        }
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
