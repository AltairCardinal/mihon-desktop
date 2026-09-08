package mihon.desktop.ui.migration

import tachiyomi.i18n.MR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.migration.BatchMigrationOptions
import mihon.desktop.migration.BatchMigrationRequest

/**
 * Thin confirmation/configuration step for a library selection.
 *
 * The complete selection is kept until the user confirms. Target search and
 * the persistent queue remain owned by the existing migration flow.
 */
data class LibraryBatchMigrationConfigScreen(
    val selectedManga: List<BatchMigrationRequest>,
) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val controller = LocalDesktopUiDependencies.current.batchMigrationController
        val scope = rememberCoroutineScope()
        var copyChapters by remember { mutableStateOf(true) }
        var copyCategories by remember { mutableStateOf(true) }
        var copyNotes by remember { mutableStateOf(true) }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_migration.localized()) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.desktop_ui_back.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(MR.strings.migrationConfigScreen_dataToMigrateHeader.localized())
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(selectedManga, key = { it.mangaId }) { manga ->
                        ListItem(headlineContent = { Text(manga.title) })
                    }
                }
                ConfigCheckRow(
                    label = MR.strings.desktop_ui_copy_chapter_read_status.localized(),
                    checked = copyChapters,
                    onCheckedChange = { copyChapters = it },
                )
                ConfigCheckRow(
                    label = MR.strings.desktop_ui_copy_categories.localized(),
                    checked = copyCategories,
                    onCheckedChange = { copyCategories = it },
                )
                ConfigCheckRow(
                    label = MR.strings.desktop_ui_copy_notes.localized(),
                    checked = copyNotes,
                    onCheckedChange = { copyNotes = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = navigator::pop) {
                        Text(MR.strings.action_cancel.localized())
                    }
                    Button(
                        enabled = selectedManga.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                val queueId = controller.submit(
                                    requests = selectedManga,
                                    defaultOptions = BatchMigrationOptions(
                                        copyChapters = copyChapters,
                                        copyCategories = copyCategories,
                                        copyNotes = copyNotes,
                                    ),
                                )
                                navigator.replace(MigrationBatchQueueScreen(queueId))
                            }
                        },
                    ) {
                        Text(MR.strings.action_migrate.localized())
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigCheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}
