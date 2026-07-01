package mihon.desktop.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category

@Composable
fun CategoryManagementDialog(
    categories: List<Category>,
    onCreate: suspend (String) -> Unit,
    onRename: suspend (Long, String) -> Unit,
    onDelete: suspend (Long) -> Unit,
    onReorder: suspend (Long, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var visibleCategories by remember { mutableStateOf(categories) }
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }

    LaunchedEffect(categories) { visibleCategories = categories }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
        val toIdx = visibleCategories.indexOfFirst { it.id == (to.key as? Long) }
        if (toIdx >= 0) {
            // Optimistic update for smooth UI, then persist
            visibleCategories = visibleCategories.toMutableList().also { list ->
                val fromIdx = list.indexOfFirst { it.id == fromId }
                if (fromIdx >= 0) list.add(toIdx, list.removeAt(fromIdx))
            }
            scope.launch { onReorder(fromId, toIdx) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Categories") },
        text = {
            Column {
                // Add new category
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                onCreate(newName)
                                newName = ""
                            }
                        },
                        enabled = newName.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }

                // Category list with drag-to-reorder
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visibleCategories, key = { it.id }) { cat ->
                        ReorderableItem(reorderableState, key = cat.id) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Drag handle
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.draggableHandle(),
                                )

                                if (editingId == cat.id) {
                                    OutlinedTextField(
                                        value = editingName,
                                        onValueChange = { editingName = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        scope.launch {
                                            onRename(cat.id, editingName)
                                            editingId = null
                                        }
                                    }) { Text("Save") }
                                } else {
                                    Text(
                                        cat.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    IconButton(onClick = {
                                        editingId = cat.id
                                        editingName = cat.name
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename")
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            onDelete(cat.id)
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
