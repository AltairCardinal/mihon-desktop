package mihon.desktop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tachiyomi.domain.library.LibraryRangeAnchorPolicy
import tachiyomi.domain.library.LibraryRangeSelectionMode
import tachiyomi.domain.library.LibrarySelectionResult
import tachiyomi.domain.library.invertLibraryItems
import tachiyomi.domain.library.selectAllLibraryItems
import tachiyomi.domain.library.selectLibraryRange
import tachiyomi.domain.library.toggleLibraryItem

/**
 * Manages multi-select state for the library grid.
 * Ctrl+Click toggles individual items; the action bar appears when [isInSelectionMode].
 */
class LibrarySelectionState {

    private var selection = LibrarySelectionResult()
    private var revision = 0L

    /** Observable snapshot — recompose when this changes via the delegated state. */
    var selectedIds: Set<Long> by mutableStateOf(emptySet())
        private set

    val isInSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun toggle(id: Long, categoryId: Long? = null) {
        publish(toggleLibraryItem(selection, id, categoryId))
    }

    fun selectRange(
        visibleIds: List<Long>,
        targetId: Long,
        categoryId: Long? = null,
        mode: LibraryRangeSelectionMode = LibraryRangeSelectionMode.APPEND,
    ) {
        if (targetId !in visibleIds) return
        publish(
            selectLibraryRange(
                selection,
                visibleIds,
                targetId,
                categoryId,
                mode = mode,
                anchorPolicy = LibraryRangeAnchorPolicy.KEEP_START,
            ),
        )
    }

    fun handlePrimaryClick(
        visibleIds: List<Long>,
        targetId: Long,
        shiftPressed: Boolean,
        categoryId: Long? = null,
        ctrlPressed: Boolean = false,
        onOpen: (Long) -> Unit,
    ) {
        if (targetId !in visibleIds) return
        when {
            shiftPressed -> selectRange(
                visibleIds,
                targetId,
                categoryId,
                if (ctrlPressed) LibraryRangeSelectionMode.APPEND else LibraryRangeSelectionMode.REPLACE_VISIBLE,
            )
            ctrlPressed -> toggle(targetId, categoryId)
            isInSelectionMode -> toggle(targetId, categoryId)
            else -> onOpen(targetId)
        }
    }

    fun isSelected(id: Long): Boolean = id in selectedIds

    fun selectAll(ids: List<Long>, categoryId: Long? = null) {
        publish(selectAllLibraryItems(selection, ids))
    }

    fun invertVisible(visibleIds: List<Long>, categoryId: Long? = null) {
        publish(invertLibraryItems(selection, visibleIds))
    }

    fun clear() {
        publish(LibrarySelectionResult())
    }

    fun captureClear(): () -> Unit {
        val acceptedRevision = revision
        return { if (revision == acceptedRevision) clear() }
    }

    fun retainExistingIds(validIds: Set<Long>) {
        val retained = selection.selectedIds.intersect(validIds)
        if (retained == selection.selectedIds) return
        publish(
            if (retained.isNotEmpty() && selection.anchor.id in validIds) {
                selection.copy(selectedIds = retained)
            } else {
                LibrarySelectionResult(selectedIds = retained)
            },
        )
    }

    private fun publish(next: LibrarySelectionResult) {
        if (selection == next) return
        revision++
        selection = next
        selectedIds = next.selectedIds
    }
}
