package mihon.desktop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tachiyomi.domain.library.LibraryRangeAnchorPolicy
import tachiyomi.domain.library.LibraryRangeSelectionMode
import tachiyomi.domain.library.LibrarySelectionAnchor
import tachiyomi.domain.library.LibrarySelectionResult
import tachiyomi.domain.library.invertLibraryItems
import tachiyomi.domain.library.selectLibraryRange
import tachiyomi.domain.library.toggleLibraryItem

/** Detail selection belongs to one manga and retains only its visible chapter identities. */
class ChapterSelectionState(private val mangaId: Long = 0L) {
    private var selection by mutableStateOf(LibrarySelectionResult())
    private var revision = 0L

    val selectedIds: Set<Long> get() = selection.selectedIds
    val anchorId: Long? get() = selection.anchor.id
    val isActive get() = selectedIds.isNotEmpty()

    fun toggle(id: Long) {
        publish(toggleLibraryItem(selection, id, mangaId))
    }

    fun selectRange(visibleIds: List<Long>, targetId: Long, ownerId: Long = mangaId) {
        if (ownerId != mangaId || targetId !in visibleIds) return
        publish(
            selectLibraryRange(
                selection,
                visibleIds,
                targetId,
                mangaId,
                mode = LibraryRangeSelectionMode.APPEND,
                anchorPolicy = LibraryRangeAnchorPolicy.KEEP_START,
            ),
        )
    }

    fun invertVisible(visibleIds: List<Long>) {
        publish(invertLibraryItems(selection.copy(selectedIds = selectedIds.intersect(visibleIds.toSet())), visibleIds))
    }

    internal fun handlePrimaryClick(
        visibleIds: List<Long>,
        targetId: Long,
        modifiers: LibraryClickModifiers,
        ownerId: Long = mangaId,
        onOpen: () -> Unit,
    ) {
        if (ownerId != mangaId || targetId !in visibleIds) return
        when {
            modifiers.shiftPressed -> selectRange(visibleIds, targetId, ownerId)
            modifiers.ctrlPressed || isActive -> toggle(targetId)
            else -> onOpen()
        }
    }

    /** Accepted batch results cannot change a selection the user has subsequently edited. */
    fun captureCompletion(): (Collection<Long>) -> Unit {
        val acceptedRevision = revision
        return { succeeded ->
            if (revision == acceptedRevision) {
                val remaining = selectedIds - succeeded.toSet()
                publish(
                    selection.copy(
                        selectedIds = remaining,
                        anchor = if (remaining.isEmpty()) LibrarySelectionAnchor() else selection.anchor,
                    ),
                    userEdit = false,
                )
            }
        }
    }

    fun selectAll(ids: List<Long>) {
        publish(LibrarySelectionResult(selectedIds = ids.toSet()))
    }

    /** Unlike library selection, filters remove hidden chapter IDs and invalidate hidden anchors. */
    fun retainVisibleIds(ids: Collection<Long>) {
        val visible = ids.toSet()
        val retained = selectedIds.intersect(visible)
        publish(
            selection.copy(
                selectedIds = retained,
                anchor = if (retained.isNotEmpty() &&
                    anchorId in visible
                ) {
                    selection.anchor
                } else {
                    LibrarySelectionAnchor()
                },
            ),
            userEdit = false,
        )
    }

    fun clear() {
        publish(LibrarySelectionResult())
    }

    private fun publish(next: LibrarySelectionResult, userEdit: Boolean = true) {
        if (next == selection) return
        if (userEdit) revision++
        selection = next
    }
}
