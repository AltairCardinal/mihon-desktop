package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.ui.home.DesktopWindowLayout
import mihon.desktop.ui.home.LocalDesktopWindowLayout
import tachiyomi.i18n.MR

internal data class SettingsNavigation(val expanded: Boolean, val onRootBack: () -> Unit)
internal val LocalSettingsNavigation = staticCompositionLocalOf<SettingsNavigation?> { null }

@Composable
internal fun SettingsNavigationIcon() {
    val navigator = LocalNavigator.currentOrThrow
    val host = LocalSettingsNavigation.current
    if (host?.expanded == true && navigator.size == 1) return
    IconButton(onClick = { if (!navigator.pop()) host?.onRootBack?.invoke() }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.action_bar_up_description.localized())
    }
}

class SettingsRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val parent = LocalNavigator.currentOrThrow
        val entries = DesktopSettingsCatalog.directoryItems()
        val directoryState = rememberLazyListState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layout = LocalDesktopWindowLayout.current ?: DesktopWindowLayout(maxWidth, maxHeight)
            var childSelected by remember { mutableStateOf(layout.expanded) }
            LaunchedEffect(layout.expanded) { if (layout.expanded) childSelected = true }
            Navigator(AppearanceSettingsScreen()) { child ->
                val back: () -> Unit = {
                    if (!child.pop()) {
                        if (layout.expanded || !childSelected) parent.pop() else childSelected = false
                    }
                }
                Row(
                    Modifier.fillMaxSize().onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { back(); true } else false
                    },
                ) {
                    if (layout.expanded || !childSelected) {
                        Scaffold(
                            modifier = if (layout.expanded) {
                                Modifier.width(minOf(layout.width / 2, 450.dp)).fillMaxHeight()
                            } else Modifier.fillMaxSize(),
                            topBar = {
                                TopAppBar(
                                    title = { Text(MR.strings.label_settings.localized()) },
                                    navigationIcon = {
                                        IconButton(onClick = parent::pop) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.action_bar_up_description.localized())
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            if (layout.expanded || childSelected) child.push(SettingsSearchScreen())
                                            else child.replaceAll(SettingsSearchScreen())
                                            childSelected = true
                                        }) {
                                            Icon(Icons.Default.Search, MR.strings.action_search_settings.localized())
                                        }
                                    },
                                )
                            },
                        ) { padding ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(padding).testTag("desktop-settings-directory"),
                                state = directoryState,
                            ) {
                                entries.forEach { entry ->
                                    item(key = entry.route::class.qualifiedName) {
                                        SettingsEntry(entry.icon, entry.title, entry.subtitle) {
                                            childSelected = true
                                            child.replaceAll(entry.route)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (layout.expanded || childSelected) {
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("desktop-settings-detail")) {
                            CompositionLocalProvider(
                                LocalSettingsNavigation provides SettingsNavigation(layout.expanded) { childSelected = false },
                            ) { CurrentScreen() }
                        }
                    }
                }
            }
        }
    }
}
