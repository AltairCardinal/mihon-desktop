package mihon.desktop.ui.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.migration.BatchMigrationItemState
import mihon.desktop.migration.BatchMigrationItemStatus

data class MigrationBatchQueueScreen(val queueId: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val batchMigrationController = LocalDesktopUiDependencies.current.batchMigrationController
        val queues by batchMigrationController.queues.collectAsState()
        val queue = queues[queueId] ?: batchMigrationController.queue(queueId)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Migration queue") },
                    navigationIcon = { IconButton(navigator::pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
        ) { padding ->
            if (queue == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("This migration queue is no longer available")
                }
                return@Scaffold
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val controls: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${queue.completedCount}/${queue.items.size} finished")
                        LinearProgressIndicator(progress = { queue.progress }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (queue.paused) batchMigrationController.resume(queueId)
                                    else batchMigrationController.pause(queueId)
                                },
                                enabled = !queue.cancelled,
                            ) { Text(if (queue.paused) "Resume" else "Pause") }
                            TextButton(
                                onClick = { batchMigrationController.cancelAll(queueId) },
                                enabled = !queue.cancelled,
                            ) { Text("Cancel all") }
                        }
                        Text(
                            when {
                                queue.cancelled -> "Queue cancelled"
                                queue.paused -> "Queue paused; progress is saved"
                                else -> "Failures do not stop the remaining queue"
                            },
                        )
                    }
                }
                val items: @Composable () -> Unit = {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(queue.items, key = { it.mangaId }) { item ->
                            ListItem(
                                headlineContent = { Text(item.title) },
                                supportingContent = { Text(statusLabel(item)) },
                                trailingContent = {
                                    Row {
                                        when (item.status) {
                                            BatchMigrationItemStatus.WAITING_FOR_USER -> TextButton(
                                                onClick = {
                                                    navigator.push(MigrationSearchScreen(item.mangaId, item.title, queueId))
                                                },
                                            ) { Text("Choose target") }
                                            BatchMigrationItemStatus.ERROR -> TextButton(
                                                onClick = { batchMigrationController.retryItem(queueId, item.mangaId) },
                                            ) { Text("Retry") }
                                            else -> Unit
                                        }
                                        if (item.status !in setOf(BatchMigrationItemStatus.SUCCESS, BatchMigrationItemStatus.CANCELLED)) {
                                            TextButton(onClick = { batchMigrationController.cancelItem(queueId, item.mangaId) }) {
                                                Text("Cancel")
                                            }
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                if (maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.34f).padding(16.dp)) { controls() }
                        Box(Modifier.weight(0.66f)) { items() }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().padding(12.dp)) { controls() }
                        Box(Modifier.weight(1f)) { items() }
                    }
                }
            }
        }
    }
}

private fun statusLabel(item: BatchMigrationItemState): String = when (item.status) {
    BatchMigrationItemStatus.QUEUED -> "Queued"
    BatchMigrationItemStatus.RUNNING -> "Running"
    BatchMigrationItemStatus.WAITING_FOR_USER -> "Waiting for target selection"
    BatchMigrationItemStatus.SUCCESS -> "Completed"
    BatchMigrationItemStatus.ERROR -> "Failed: ${item.error ?: "Unknown error"}"
    BatchMigrationItemStatus.CANCELLED -> "Cancelled"
}
