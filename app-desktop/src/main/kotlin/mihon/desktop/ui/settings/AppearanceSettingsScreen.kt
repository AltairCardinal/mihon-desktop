package mihon.desktop.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.UiDateFormat
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopLocaleAdapter
import mihon.desktop.platform.DesktopLocaleApplyResult
import mihon.desktop.ui.library.categoryDialogEscape
import tachiyomi.i18n.MR
import mihon.desktop.settings.saveDesktopPreference
import java.time.LocalDate
import java.util.Locale

class AppearanceSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val prefs = dependencies.appPreferences
        val mode by prefs.themeMode.changes().collectAsState(initial = prefs.themeMode.get())
        val theme by prefs.appTheme.changes().collectAsState(initial = prefs.appTheme.get())
        val amoled by prefs.themeDarkAmoled.changes().collectAsState(initial = prefs.themeDarkAmoled.get())
        val tablet by prefs.tabletUiMode.changes().collectAsState(initial = prefs.tabletUiMode.get())
        val date by prefs.dateFormat.changes().collectAsState(initial = prefs.dateFormat.get())
        val relative by prefs.relativeTime.changes().collectAsState(initial = prefs.relativeTime.get())
        val images by prefs.imagesInDescription.changes().collectAsState(initial = prefs.imagesInDescription.get())
        val activeLanguage by dependencies.localeAdapter.activeLanguageTag.collectAsState()
        val systemDark = isSystemInDarkTheme()
        val isDark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && systemDark)
        val scope = rememberCoroutineScope()
        val feedback = remember { SnackbarHostState() }
        var dialog by remember { mutableStateOf<AppearanceChoice?>(null) }
        val dateFocus = remember { FocusRequester() }
        val tabletFocus = remember { FocusRequester() }
        var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
        LaunchedEffect(dialog) {
            if (dialog == null) {
                returnFocus?.let {
                    withFrameNanos { }
                    it.requestFocus()
                }
            }
        }

        fun announce(message: String) {
            scope.launch { feedback.showSnackbar(message) }
        }

        fun <T> save(preference: tachiyomi.core.common.preference.Preference<T>, value: T): Boolean {
            val saved = saveAppearancePreference(preference, value)
            if (!saved) announce(MR.strings.desktop_appearance_save_failed.localized())
            return saved
        }
        val defaultLanguage = MR.strings.label_default.localized()
        val currentLanguageName =
            dependencies.localeAdapter
                .availableLanguages()
                .firstOrNull { it.languageTag == activeLanguage }
                ?.displayName ?: defaultLanguage
        val today = LocalDate.now()
        val formattedDate = UiDateFormat.formatter(date).format(today)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(MR.strings.pref_category_appearance.localized())
                    },
                    navigationIcon = {
                        SettingsNavigationIcon()
                    },
                )
            },
            snackbarHost = { SnackbarHost(feedback) },
        ) { padding ->
            DesktopSettingsAnchorColumn(
                route = this@AppearanceSettingsScreen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                SectionTitle(MR.strings.pref_category_theme.localized())
                val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                MultiChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(IntrinsicSize.Min),
                ) {
                    modes.forEachIndexed { index, value ->
                        SegmentedButton(
                            modifier = Modifier.fillMaxHeight(),
                            checked = mode == value,
                            onCheckedChange = { if (mode != value) save(prefs.themeMode, value) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            label = {
                                Text(
                                    when (value) {
                                        ThemeMode.SYSTEM -> MR.strings.theme_system.localized()
                                        ThemeMode.LIGHT -> MR.strings.theme_light.localized()
                                        ThemeMode.DARK -> MR.strings.theme_dark.localized()
                                    },
                                )
                            },
                        )
                    }
                }
                AppearanceThemeCards(
                    if (theme == AppTheme.MONET) {
                        AppTheme.DEFAULT
                    } else {
                        theme
                    },
                    isDark,
                    amoled,
                    Modifier.desktopSettingsAnchor(MR.strings.pref_app_theme.localized()),
                ) {
                    if (theme != it) save(prefs.appTheme, it)
                }
                if (theme == AppTheme.MONET) {
                    Text(MR.strings.desktop_dynamic_theme_unavailable.localized(), Modifier.padding(16.dp))
                }
                if (mode != ThemeMode.LIGHT) {
                    SwitchSettingsItem(
                        title = MR.strings.pref_dark_theme_pure_black.localized(),
                        subtitle = null,
                        checked = amoled,
                        onCheckedChange = { save(prefs.themeDarkAmoled, it) },
                        modifier = Modifier.desktopSettingsAnchor(MR.strings.pref_dark_theme_pure_black.localized()),
                    )
                }
                SectionTitle(MR.strings.pref_category_display.localized())
                ListItem(
                    headlineContent = { Text(MR.strings.pref_app_language.localized()) },
                    supportingContent = { Text(currentLanguageName) },
                    modifier =
                    Modifier
                        .desktopSettingsAnchor(MR.strings.pref_app_language.localized())
                        .desktopSettingsAction(Role.Button) { navigator.push(LanguageSettingsScreen()) },
                )
                ListItem(
                    headlineContent = { Text(MR.strings.pref_tablet_ui_mode.localized()) },
                    supportingContent = { Text(tablet.titleRes.localized()) },
                    modifier =
                    Modifier
                        .focusRequester(tabletFocus)
                        .desktopSettingsAnchor(MR.strings.pref_tablet_ui_mode.localized())
                        .desktopSettingsAction(Role.Button) {
                            returnFocus = tabletFocus
                            dialog = AppearanceChoice.TABLET
                        },
                )
                ListItem(
                    headlineContent = { Text(MR.strings.pref_date_format.localized()) },
                    supportingContent = { Text(formattedDate) },
                    modifier =
                    Modifier
                        .focusRequester(dateFocus)
                        .desktopSettingsAnchor(MR.strings.pref_date_format.localized())
                        .desktopSettingsAction(Role.Button) {
                            returnFocus = dateFocus
                            dialog = AppearanceChoice.DATE
                        },
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_relative_format.localized(),
                    subtitle = MR.strings.pref_relative_format_summary.localized(
                        Locale.getDefault(),
                        MR.strings.relative_time_today.localized(),
                        formattedDate,
                    ),
                    checked = relative,
                    onCheckedChange = { save(prefs.relativeTime, it) },
                    modifier = Modifier.desktopSettingsAnchor(MR.strings.pref_relative_format.localized()),
                )
                SwitchSettingsItem(
                    title = MR.strings.pref_display_images_description.localized(),
                    subtitle = null,
                    checked = images,
                    onCheckedChange = { save(prefs.imagesInDescription, it) },
                    modifier = Modifier.desktopSettingsAnchor(MR.strings.pref_display_images_description.localized()),
                )

            }
        }
        when (dialog) {
            AppearanceChoice.DATE -> {
                AppearanceChoiceDialog(
                    MR.strings.pref_date_format.localized(),
                    UiDateFormat.patterns.map { value ->
                        ChoiceEntry(
                            value,
                            "${value.ifEmpty { defaultLanguage }} (${UiDateFormat.formatter(value).format(today)})",
                        )
                    },
                    date,
                    "date",
                    onSelect = { value -> if (save(prefs.dateFormat, value)) dialog = null },
                    onDismiss = { dialog = null },
                    onFailure = { announce(MR.strings.unknown_error.localized()) },
                )
            }
            AppearanceChoice.TABLET -> {
                AppearanceChoiceDialog(
                    MR.strings.pref_tablet_ui_mode.localized(),
                    TabletUiMode.entries.map { ChoiceEntry(it.name, it.titleRes.localized()) },
                    tablet.name,
                    "tablet",
                    onSelect = { value ->
                        if (save(prefs.tabletUiMode, TabletUiMode.valueOf(value))) {
                            dialog = null
                            announce(MR.strings.requires_app_restart.localized())
                        }
                    },
                    onDismiss = { dialog = null },
                    onFailure = { announce(MR.strings.unknown_error.localized()) },
                )
            }
            null -> {
                Unit
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.desktopSettingsAnchor(title).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private enum class AppearanceChoice { DATE, TABLET }

private data class ChoiceEntry(val value: String, val label: String)

@Composable
private fun AppearanceChoiceDialog(
    title: String,
    entries: List<ChoiceEntry>,
    selected: String,
    tag: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
    }
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(true, onDismiss).focusRequester(focus).focusable(),
        title = { Text(title) },
        onDismissRequest = onDismiss,
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(entries, key = { it.value }) { entry ->
                    RadioSettingsItem(entry.label, entry.value == selected, onClick = {
                        if (entry.value != selected) {
                            try {
                                onSelect(entry.value)
                            } catch (_: Exception) {
                                onFailure()
                            }
                        }
                    }, modifier = Modifier.testTag("appearance-choice-$tag-${entry.value.ifEmpty { "default" }}"))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) } },
    )
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
        val message =
            when (val result = feedback.result) {
                is DesktopLocaleApplyResult.Applied -> {
                    val selected =
                        if (result.languageTag.isEmpty()) {
                            MR.strings.desktop_language_follow_system.localized()
                        } else {
                            localeAdapter
                                .availableLanguages()
                                .first { it.languageTag == result.languageTag }
                                .displayName
                        }
                    "${MR.strings.pref_app_language.localized()}: $selected"
                }
                is DesktopLocaleApplyResult.Failed,
                is DesktopLocaleApplyResult.Fallback,
                -> {
                    MR.strings.unknown_error.localized()
                }
            }
        snackbar.showSnackbar(message)
        localeAdapter.consumeFeedback(feedback.id)
    }

    SnackbarHost(hostState = snackbar, modifier = modifier)
}

private fun <T> saveAppearancePreference(preference: tachiyomi.core.common.preference.Preference<T>, value: T): Boolean =
    saveDesktopPreference(preference, value)
