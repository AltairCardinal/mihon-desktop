package mihon.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.domain.DesktopCategoryManager
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.LibraryUpdateInterval
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibrarySettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<DesktopAppPreferences>() }
        val categoryManager = remember { Injekt.get<DesktopCategoryManager>() }
        val updateInterval by prefs.libraryUpdateInterval.changes().collectAsState(
            initial = prefs.libraryUpdateInterval.get(),
        )
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
        var excludeIds by remember {
            mutableStateOf(
                prefs.updateCategoryExcludes.get()
                    .split(",").mapNotNull { it.trim().toLongOrNull() }.toSet(),
            )
        }

        LaunchedEffect(Unit) {
            categories = categoryManager.getAll()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Library") },
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
                    text = "Auto-Update Library",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                val intervalLabels = mapOf(
                    LibraryUpdateInterval.OFF to "Off",
                    LibraryUpdateInterval.EVERY_6H to "Every 6 hours",
                    LibraryUpdateInterval.EVERY_12H to "Every 12 hours",
                    LibraryUpdateInterval.EVERY_24H to "Every 24 hours",
                    LibraryUpdateInterval.WEEKLY to "Weekly",
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
                    text = "Update Behavior",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = "You can also use the Refresh button in the Library tab to check for new chapters manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                if (categories.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Exclude from Updates",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "Manga in checked categories will be skipped during auto-updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    excludeIds = if (cat.id in excludeIds) excludeIds - cat.id else excludeIds + cat.id
                                    prefs.updateCategoryExcludes.set(excludeIds.joinToString(","))
                                }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = cat.id in excludeIds,
                                onCheckedChange = { checked ->
                                    excludeIds = if (checked) excludeIds + cat.id else excludeIds - cat.id
                                    prefs.updateCategoryExcludes.set(excludeIds.joinToString(","))
                                },
                            )
                            Text(cat.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
