package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.selectableAppThemes
import tachiyomi.i18n.MR
import java.util.Locale

class AppearanceSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences
        var themeMode by remember { mutableStateOf(prefs.themeMode.get()) }
        var appTheme by remember { mutableStateOf(prefs.appTheme.get()) }
        var isAmoled by remember { mutableStateOf(prefs.themeDarkAmoled.get()) }
        var gridColumns by remember { mutableStateOf(prefs.libraryGridColumns.get().toFloat()) }
        val themeTitle = MR.strings.pref_category_theme.localized()
        val appThemeTitle = MR.strings.pref_app_theme.localized()
        val amoledTitle = MR.strings.pref_dark_theme_pure_black.localized()
        val gridTitle = MR.strings.desktop_appearance_library_grid.localized()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_appearance.localized()) },
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
                route = this@AppearanceSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Theme section
                Text(
                    text = themeTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(themeTitle).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ThemeMode.entries.forEach { mode ->
                    RadioSettingsItem(
                        title = when (mode) {
                            ThemeMode.SYSTEM -> MR.strings.theme_system.localized()
                            ThemeMode.LIGHT -> MR.strings.theme_light.localized()
                            ThemeMode.DARK -> MR.strings.theme_dark.localized()
                        },
                        selected = themeMode == mode,
                        onClick = {
                            themeMode = mode
                            prefs.themeMode.set(mode)
                        },
                    )
                }
                Text(
                    text = appThemeTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(appThemeTitle).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                selectableAppThemes(dynamicColorAvailable = false).forEach { theme ->
                    RadioSettingsItem(
                        title = requireNotNull(theme.titleRes).localized(),
                        selected = appTheme == theme,
                        onClick = {
                            appTheme = theme
                            prefs.appTheme.set(theme)
                        },
                    )
                }
                val amoledEnabled = themeMode != ThemeMode.LIGHT
                ListItem(
                    headlineContent = { Text(amoledTitle) },
                    trailingContent = {
                        Switch(
                            checked = isAmoled,
                            enabled = amoledEnabled,
                            onCheckedChange = {
                                isAmoled = it
                                prefs.themeDarkAmoled.set(it)
                            },
                        )
                    },
                    modifier = Modifier
                        .desktopSettingsAnchor(amoledTitle)
                        .clickable(enabled = amoledEnabled) {
                            isAmoled = !isAmoled
                            prefs.themeDarkAmoled.set(isAmoled)
                        },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Grid columns section
                Text(
                    text = gridTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.desktopSettingsAnchor(gridTitle).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = MR.strings.desktop_appearance_grid_columns.localized(Locale.getDefault(), gridColumns.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Slider(
                    value = gridColumns,
                    onValueChange = {
                        gridColumns = it
                        prefs.libraryGridColumns.set(it.toInt())
                    },
                    valueRange = 2f..6f,
                    steps = 3,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
