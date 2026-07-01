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
import androidx.compose.material3.Slider
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
import mihon.desktop.settings.ThemeMode

class AppearanceSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = LocalDesktopUiDependencies.current.appPreferences
        var themeMode by remember { mutableStateOf(prefs.themeMode.get()) }
        var gridColumns by remember { mutableStateOf(prefs.libraryGridColumns.get().toFloat()) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Appearance") },
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
                // Theme section
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ThemeMode.entries.forEach { mode ->
                    RadioSettingsItem(
                        title = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = themeMode == mode,
                        onClick = {
                            themeMode = mode
                            prefs.themeMode.set(mode)
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Grid columns section
                Text(
                    text = "Library Grid",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = "Columns: ${gridColumns.toInt()}",
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
