package mihon.desktop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tracks which chapters are selected in the manga detail screen.
 * Mirrors [LibrarySelectionState] pattern from the library tab.
 */
class ChapterSelectionState {
    var selectedIds by mutableStateOf<Set<Long>>(emptySet())

    val isActive get() = selectedIds.isNotEmpty()

    fun toggle(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll(ids: List<Long>) {
        selectedIds = ids.toSet()
    }

    fun clear() {
        selectedIds = emptySet()
    }
}
