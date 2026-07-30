package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = MR.strings.action_bar_up_description.localized(),
                            )
                        }
                    },
                    title = {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .desktopSettingsEnterKey(focusManager::clearFocus),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text(
                                            MR.strings.action_search_settings.localized(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = MR.strings.action_reset.localized(),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when {
                query.isEmpty() -> Feedback(
                    MR.strings.desktop_settings_search_empty.localized(),
                    Modifier.fillMaxSize().padding(padding),
                )
                results.isEmpty() -> Feedback(
                    MR.strings.no_results_found.localized(),
                    Modifier.fillMaxSize().padding(padding),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    items(results) { result ->
                        ListItem(
                            headlineContent = { Text(result.title) },
                            supportingContent = { Text(result.breadcrumb) },
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .desktopSettingsAction(Role.Button) {
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
