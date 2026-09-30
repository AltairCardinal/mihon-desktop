package mihon.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR

/** Shared More/detail destination. Each instance owns its existing LibraryScreenModel. */
class CategoryManagementScreen : Screen {
    override val key = "category-management-${UUID.randomUUID()}"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val factory = LocalLibraryScreenModelFactory.current
        val model = rememberScreenModel { factory() }
        val state by model.state.collectAsState()
        LaunchedEffect(model) { model.observeCategories() }
        val categories = state.allCategories.filterNot(Category::isSystemCategory).sortedBy { it.order }
        var projected by remember { mutableStateOf<List<Category>?>(null) }
        var draggingId by remember { mutableStateOf<Long?>(null) }
        var saving by remember { mutableStateOf(false) }
        var editor by remember { mutableStateOf<CategoryEditor?>(null) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val rootFocus = remember { FocusRequester() }
        val addFocus = remember { FocusRequester() }
        val cardFocus = remember { mutableMapOf<Long, FocusRequester>() }
        var returnId by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            rootFocus.requestFocus()
        }
        LaunchedEffect(editor) {
            if (editor == null) {
                androidx.compose.runtime.withFrameNanos { }
                val target = returnId?.takeIf { id -> categories.any { it.id == id } }?.let(cardFocus::get) ?: addFocus
                target.requestFocus()
            }
        }
        LaunchedEffect(state.operationFeedback) {
            state.operationFeedback?.let { message ->
                snackbar.showSnackbar(message)
                model.clearOperationResults()
            }
        }
        fun move(id: Long, index: Int) {
            if (saving) return
            saving = true
            scope.launch {
                try {
                    model.reorderCategory(id, index)
                } finally {
                    projected = null
                    draggingId = null
                    saving = false
                }
            }
        }
        val visible = projected ?: categories
        val listState = rememberLazyListState()
        val reorderable = rememberReorderableLazyListState(listState) { from, to ->
            val current = projected ?: categories
            val source = current.indexOfFirst { it.id == from.key }
            val target = current.indexOfFirst { it.id == to.key }
            if (!saving && source >= 0 && target >= 0) {
                projected = current.toMutableList().also { it.add(target, it.removeAt(source)) }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent {
                if (editor == null && it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    navigator.pop()
                    true
                } else false
            }.focusRequester(rootFocus).focusable(),
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.action_edit_categories.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    modifier = Modifier.focusRequester(addFocus).testTag("category-add"),
                    text = { Text(MR.strings.action_add.localized()) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    onClick = { returnId = null; editor = CategoryEditor.Name(null) },
                    expanded = true,
                )
            },
        ) { padding ->
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(MR.strings.information_empty_category.localized())
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { category ->
                        ReorderableItem(reorderable, key = category.id) {
                            val focus = cardFocus.getOrPut(category.id) { FocusRequester() }
                            ElevatedCard(Modifier.fillMaxWidth().testTag("category-card-${category.id}")) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                        .clickable { returnId = category.id; editor = CategoryEditor.Name(category) }
                                        .padding(vertical = 8.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.DragHandle,
                                        MR.strings.desktop_ui_drag_to_reorder.localized(),
                                        modifier = Modifier.padding(16.dp).testTag("category-handle-${category.id}")
                                            .semantics {
                                                customActions = buildList {
                                                    val index = categories.indexOfFirst { it.id == category.id }
                                                    if (index > 0) add(CustomAccessibilityAction(MR.strings.action_move_to_top.localized()) { move(category.id, 0); true })
                                                    if (index < categories.lastIndex) add(CustomAccessibilityAction(MR.strings.action_move_to_bottom.localized()) { move(category.id, categories.lastIndex); true })
                                                }
                                            }.onPreviewKeyEvent { event ->
                                                // Desktop keyboard adapter shares the same persistent reorder action.
                                                val offset = when (event.key) {
                                                    Key.DirectionUp -> -1
                                                    Key.DirectionDown -> 1
                                                    else -> 0
                                                }
                                                if (event.isAltPressed && offset != 0) {
                                                    if (event.type == KeyEventType.KeyDown && !saving) {
                                                        val index = categories.indexOfFirst { it.id == category.id }
                                                        val target = index + offset
                                                        if (target in categories.indices) move(category.id, target)
                                                    }
                                                    true
                                                } else false
                                            }.focusable().draggableHandle(
                                                enabled = !saving,
                                                onDragStarted = { draggingId = category.id },
                                                onDragStopped = {
                                                    draggingId?.let { id ->
                                                        val index = (projected ?: categories).indexOfFirst { it.id == id }
                                                        if (index >= 0) move(id, index)
                                                    }
                                                },
                                            ),
                                    )
                                    Text(category.name, Modifier.weight(1f))
                                    IconButton(
                                        modifier = Modifier.testTag("category-rename-${category.id}"),
                                        onClick = { returnId = category.id; editor = CategoryEditor.Name(category) },
                                    ) { Icon(Icons.Outlined.Edit, MR.strings.action_rename_category.localized()) }
                                    IconButton(
                                        modifier = Modifier.testTag("category-delete-${category.id}"),
                                        onClick = { returnId = category.id; editor = CategoryEditor.Delete(category) },
                                    ) { Icon(Icons.Outlined.Delete, MR.strings.action_delete.localized()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        when (val active = editor) {
            is CategoryEditor.Name -> CategoryNameDialog(
                categories = categories,
                category = active.category,
                onDismiss = { editor = null },
                onConfirm = { name ->
                    val saved = if (active.category == null) model.createCategory(name) else model.renameCategory(active.category.id, name)
                    if (saved) editor = null
                    saved
                },
            )
            is CategoryEditor.Delete -> {
                var busy by remember(active.category.id) { mutableStateOf(false) }
                AlertDialog(
                    modifier = Modifier.categoryDialogEscape(!busy) { editor = null },
                    onDismissRequest = { if (!busy) editor = null },
                    title = { Text(MR.strings.delete_category.localized()) },
                    text = { Text(MR.strings.delete_category_confirmation.localized(Locale.getDefault(), active.category.name)) },
                    confirmButton = {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                if (model.deleteCategory(active.category.id)) { returnId = null; editor = null }
                                busy = false
                            }
                        }) { Text(MR.strings.action_delete.localized()) }
                    },
                    dismissButton = {
                        TextButton(enabled = !busy, onClick = { editor = null }) { Text(MR.strings.action_cancel.localized()) }
                    },
                )
            }
            null -> Unit
        }
    }
}

private sealed interface CategoryEditor {
    data class Name(val category: Category?) : CategoryEditor
    data class Delete(val category: Category) : CategoryEditor
}

@Composable
private fun CategoryNameDialog(
    categories: List<Category>,
    category: Category?,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Boolean,
) {
    var name by remember(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    // Match the upstream exact-name rule; trimming never depends on the current locale.
    val reason = when {
        trimmed.isEmpty() -> MR.strings.information_required_plain.localized()
        category != null && trimmed == category.name -> MR.strings.desktop_category_name_unchanged.localized()
        categories.any { it.id != category?.id && it.name == trimmed } -> MR.strings.error_category_exists.localized()
        else -> null
    }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    AlertDialog(
        modifier = Modifier.categoryDialogEscape(!busy, onDismiss),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (category == null) MR.strings.action_add_category.localized() else MR.strings.action_rename_category.localized()) },
        text = {
            OutlinedTextField(
                modifier = Modifier.focusRequester(focus),
                value = name,
                onValueChange = { name = it; failed = false },
                label = { Text(MR.strings.name.localized()) },
                supportingText = { if (reason != null || failed) Text(reason ?: MR.strings.internal_error.localized()) },
                isError = reason != null || failed,
                singleLine = true,
                enabled = !busy,
            )
        },
        confirmButton = {
            TextButton(enabled = reason == null && !busy, onClick = {
                busy = true
                scope.launch { failed = !onConfirm(trimmed); busy = false }
            }) { Text(if (category == null) MR.strings.action_add.localized() else MR.strings.action_ok.localized()) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) } },
    )
    LaunchedEffect(focus) { focus.requestFocus() }
}

internal fun Modifier.categoryDialogEscape(enabled: Boolean, dismiss: () -> Unit) = onPreviewKeyEvent {
    if (enabled && it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
        dismiss()
        true
    } else false
}
