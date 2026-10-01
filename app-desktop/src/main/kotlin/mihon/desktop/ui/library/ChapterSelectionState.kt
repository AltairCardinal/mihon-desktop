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
        private set

    var anchorId by mutableStateOf<Long?>(null)
        private set

    val isActive get() = selectedIds.isNotEmpty()

    fun toggle(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        anchorId = id.takeIf { selectedIds.isNotEmpty() }
    }

    fun selectAll(ids: List<Long>) {
        selectedIds = ids.toSet()
        anchorId = null
    }

    /** Detail selection follows visible chapters; library selection deliberately uses different retention. */
    fun retainVisibleIds(ids: Collection<Long>) {
        val visible = ids.toSet()
        selectedIds = selectedIds.intersect(visible)
        if (selectedIds.isEmpty() || anchorId !in visible) anchorId = null
    }

    fun clear() {
        selectedIds = emptySet()
        anchorId = null
    }
}
