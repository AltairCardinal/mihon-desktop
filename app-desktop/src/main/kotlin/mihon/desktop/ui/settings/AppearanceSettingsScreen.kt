package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.selectableAppThemes
import mihon.desktop.platform.DesktopLocaleApplyResult
import mihon.desktop.platform.DesktopLocaleAdapter
import tachiyomi.i18n.MR
import java.util.Locale

class AppearanceSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val prefs = dependencies.appPreferences
        val localeAdapter = dependencies.localeAdapter
        val activeLanguageTag by localeAdapter.activeLanguageTag.collectAsState()
        var showLanguageDialog by remember { mutableStateOf(false) }
        var themeMode by remember { mutableStateOf(prefs.themeMode.get()) }
        var appTheme by remember { mutableStateOf(prefs.appTheme.get()) }
        var isAmoled by remember { mutableStateOf(prefs.themeDarkAmoled.get()) }
        var gridColumns by remember { mutableStateOf(prefs.libraryGridColumns.get().toFloat()) }
        val themeTitle = MR.strings.pref_category_theme.localized()
        val appThemeTitle = MR.strings.pref_app_theme.localized()
        val amoledTitle = MR.strings.pref_dark_theme_pure_black.localized()
        val gridTitle = MR.strings.desktop_appearance_library_grid.localized()
        val languageTitle = MR.strings.pref_app_language.localized()
        val defaultLanguageLabel = MR.strings.desktop_language_follow_system.localized()
        val languageOptions = localeAdapter.availableLanguages()
        val activeLanguageName = if (activeLanguageTag.isEmpty()) {
            defaultLanguageLabel
        } else {
            languageOptions.firstOrNull { it.languageTag == activeLanguageTag }?.displayName ?: defaultLanguageLabel
        }

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
                ListItem(
                    headlineContent = { Text(languageTitle) },
                    supportingContent = { Text(activeLanguageName) },
                    modifier = Modifier
                        .desktopSettingsAnchor(languageTitle)
                        .desktopSettingsAction(Role.Button) { showLanguageDialog = true },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
                SwitchSettingsItem(
                    title = amoledTitle,
                    subtitle = null,
                    checked = isAmoled,
                    enabled = amoledEnabled,
                    onCheckedChange = {
                        isAmoled = it
                        prefs.themeDarkAmoled.set(it)
                    },
                    modifier = Modifier.desktopSettingsAnchor(amoledTitle),
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

        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(MR.strings.pref_app_language.localized()) },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                        item {
                            LanguageSettingsItem(
                                title = MR.strings.desktop_language_follow_system.localized(),
                                subtitle = null,
                                selected = activeLanguageTag.isEmpty(),
                                onClick = {
                                    showLanguageDialog = false
                                    localeAdapter.select("")
                                },
                            )
                        }
                        items(languageOptions, key = { it.languageTag }) { language ->
                            LanguageSettingsItem(
                                title = language.displayName,
                                subtitle = language.localizedDisplayName,
                                selected = activeLanguageTag == language.languageTag,
                                onClick = {
                                    showLanguageDialog = false
                                    localeAdapter.select(language.languageTag)
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
        }
    }
}

@Composable
internal fun DesktopLocaleFeedbackHost(
    localeAdapter: DesktopLocaleAdapter,
    modifier: Modifier = Modifier,
) {
    val pendingFeedback by localeAdapter.pendingFeedback.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(pendingFeedback?.id) {
        val feedback = pendingFeedback ?: return@LaunchedEffect
        val message = when (val result = feedback.result) {
            is DesktopLocaleApplyResult.Applied -> {
                val selected = if (result.languageTag.isEmpty()) {
                    MR.strings.desktop_language_follow_system.localized()
                } else {
                    localeAdapter.availableLanguages()
                        .first { it.languageTag == result.languageTag }
                        .displayName
                }
                "${MR.strings.pref_app_language.localized()}: $selected"
            }
            is DesktopLocaleApplyResult.Failed,
            is DesktopLocaleApplyResult.Fallback,
            -> MR.strings.unknown_error.localized()
        }
        snackbar.showSnackbar(message)
        localeAdapter.consumeFeedback(feedback.id)
    }

    SnackbarHost(hostState = snackbar, modifier = modifier)
}

@Composable
private fun LanguageSettingsItem(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = if (selected) MR.strings.selected.localized() else MR.strings.not_selected.localized()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        modifier = Modifier
            .semantics(mergeDescendants = true) { stateDescription = description }
            .desktopSettingsActivationKeys(role = Role.RadioButton, onClick = onClick)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}
