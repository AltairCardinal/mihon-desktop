package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.ReaderDefaultMode

class ReaderSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences
        var readerMode by remember { mutableStateOf(prefs.defaultReaderMode.get()) }
        var isRtl by remember { mutableStateOf(prefs.defaultRtl.get()) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Reader") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Default Reading Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ReaderDefaultMode.entries.forEach { mode ->
                    RadioSettingsItem(
                        title = when (mode) {
                            ReaderDefaultMode.PAGER -> "Pager (horizontal flip)"
                            ReaderDefaultMode.WEBTOON -> "Webtoon (vertical scroll)"
                        },
                        selected = readerMode == mode,
                        onClick = {
                            readerMode = mode
                            prefs.defaultReaderMode.set(mode)
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SwitchSettingsItem(
                    title = "Right-to-Left by default",
                    subtitle = "Flip pages from right to left (Japanese manga order)",
                    checked = isRtl,
                    onCheckedChange = {
                        isRtl = it
                        prefs.defaultRtl.set(it)
                    },
                )
            }
        }
    }
}
