package mihon.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.domain.settings.SettingsLayoutDirection
import tachiyomi.i18n.MR

class SettingsSearchScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val focusManager = LocalFocusManager.current
        val focusRequester = remember { FocusRequester() }
        var query by remember { mutableStateOf("") }
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) SettingsLayoutDirection.Ltr else SettingsLayoutDirection.Rtl
        val results = remember(query, direction) { DesktopSettingsCatalog.search(query, direction) }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(MR.strings.action_search_settings.localized()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent {
                                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                                    focusManager.clearFocus()
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    },
                )
            },
        ) { padding ->
            when {
                query.isEmpty() -> Feedback(MR.strings.desktop_settings_search_empty.localized(), Modifier.fillMaxSize())
                results.isEmpty() -> Feedback(MR.strings.no_results_found.localized(), Modifier.fillMaxSize())
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    items(results) { result ->
                        ListItem(
                            headlineContent = { Text(result.title) },
                            supportingContent = { Text(result.breadcrumb) },
                            modifier = Modifier.clickable {
                                DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
                                navigator.replace(result.route)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Feedback(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text) }
}
