package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.LocalDesktopUiDependencies
import tachiyomi.i18n.MR

/** Language preferences use the same child navigator as every other Settings screen. */
class LanguageSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val adapter = LocalDesktopUiDependencies.current.localeAdapter
        val selected by adapter.activeLanguageTag.collectAsState()
        val options = listOf(
            mihon.desktop.platform.DesktopLanguageOption("", MR.strings.label_default.localized(), null),
        ) + adapter.availableLanguages()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(MR.strings.pref_app_language.localized())
                    },
                    navigationIcon = {
                        SettingsNavigationIcon()
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("desktop-language-list")) {
                items(options, key = { it.languageTag }) { option ->
                    ListItem(
                        headlineContent = { Text(option.displayName) },
                        supportingContent = option.localizedDisplayName?.let { { Text(it) } },
                        trailingContent = {
                            if (selected == option.languageTag) {
                                Icon(Icons.Default.Check, MR.strings.selected.localized())
                            }
                        },
                        modifier =
                        Modifier.testTag("language-${option.languageTag}").desktopSettingsAction(Role.Button) {
                            if (selected != option.languageTag) adapter.select(option.languageTag)
                        },
                    )
                }
            }
        }
    }
}
