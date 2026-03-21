package mihon.desktop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Manages multi-select state for the library grid.
 * Ctrl+Click toggles individual items; the action bar appears when [isInSelectionMode].
 */
class LibrarySelectionState {

    private val _selectedIds = mutableSetOf<Long>()

    /** Observable snapshot — recompose when this changes via the delegated state. */
    var selectedIds: Set<Long> by mutableStateOf(emptySet())
        private set

    val isInSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun toggle(id: Long) {
        if (id in _selectedIds) _selectedIds.remove(id) else _selectedIds.add(id)
        selectedIds = _selectedIds.toSet()
    }

    fun isSelected(id: Long): Boolean = id in selectedIds

    fun selectAll(ids: List<Long>) {
        _selectedIds.addAll(ids)
        selectedIds = _selectedIds.toSet()
    }

    fun clear() {
        _selectedIds.clear()
        selectedIds = emptySet()
    }
}
