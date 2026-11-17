package tachiyomi.domain.library

/** The last item used as a range-selection anchor. */
data class LibrarySelectionAnchor(
    val id: Long? = null,
    val categoryId: Long? = null,
)

/** Selection plus its range anchor, kept independent from UI state containers. */
data class LibrarySelectionResult(
    val selectedIds: Set<Long> = emptySet(),
    val anchor: LibrarySelectionAnchor = LibrarySelectionAnchor(),
)

enum class LibraryRangeSelectionMode { APPEND, REPLACE_VISIBLE }

enum class LibraryRangeAnchorPolicy { MOVE_TO_TARGET, KEEP_START }

fun toggleLibraryItem(
    state: LibrarySelectionResult,
    id: Long,
    categoryId: Long?,
): LibrarySelectionResult {
    val selected = state.selectedIds.toMutableSet()
    if (!selected.add(id)) selected.remove(id)
    return LibrarySelectionResult(
        selectedIds = selected,
        anchor = if (selected.isEmpty()) {
            LibrarySelectionAnchor()
        } else {
            LibrarySelectionAnchor(id, categoryId)
        },
    )
}

fun selectLibraryRange(
    state: LibrarySelectionResult,
    visibleIds: List<Long>,
    targetId: Long,
    categoryId: Long?,
    mode: LibraryRangeSelectionMode = LibraryRangeSelectionMode.APPEND,
    anchorPolicy: LibraryRangeAnchorPolicy = LibraryRangeAnchorPolicy.MOVE_TO_TARGET,
): LibrarySelectionResult {
    val anchor = state.anchor
    val anchorIndex = visibleIds.indexOf(anchor.id)
    val targetIndex = visibleIds.indexOf(targetId)
    val validAnchor = anchor.id != null && anchor.categoryId == categoryId && anchorIndex >= 0 && targetIndex >= 0
    val rangeIds = if (validAnchor) {
        val range = if (anchorIndex <= targetIndex) anchorIndex..targetIndex else targetIndex..anchorIndex
        range.map { visibleIds[it] }
    } else {
        listOf(targetId)
    }
    val retained = when (mode) {
        LibraryRangeSelectionMode.APPEND -> state.selectedIds
        LibraryRangeSelectionMode.REPLACE_VISIBLE -> state.selectedIds - visibleIds.toSet()
    }
    return LibrarySelectionResult(
        selectedIds = retained + rangeIds,
        anchor = if (validAnchor && anchorPolicy == LibraryRangeAnchorPolicy.KEEP_START) {
            anchor
        } else {
            LibrarySelectionAnchor(targetId, categoryId)
        },
    )
}

fun selectAllLibraryItems(
    state: LibrarySelectionResult,
    visibleIds: List<Long>,
): LibrarySelectionResult = LibrarySelectionResult(
    selectedIds = state.selectedIds + visibleIds,
    anchor = LibrarySelectionAnchor(),
)

/** One immediate range-fill undo. Platform anchors must be immutable values. */
data class ChapterRangeSelectionUndo<A>(
    val selectedIds: Set<Long>,
    val anchor: A,
    val visibleIds: List<Long>,
    val filledIds: Set<Long>,
) {
    fun isValidFor(selectedIds: Set<Long>, visibleIds: List<Long>): Boolean =
        filledIds == selectedIds && this.visibleIds == visibleIds
}

data class ChapterRangeSelectionResult<A>(
    val selectedIds: Set<Long>,
    val anchor: A,
    val undo: ChapterRangeSelectionUndo<A>?,
)

/** Toggle only a still-valid fill; unrelated selection actions must discard the snapshot. */
fun <A> toggleChapterRangeSelection(
    selectedIds: Set<Long>,
    anchor: A,
    visibleIds: List<Long>,
    undo: ChapterRangeSelectionUndo<A>?,
): ChapterRangeSelectionResult<A> {
    if (undo != null && undo.isValidFor(selectedIds, visibleIds)) {
        return ChapterRangeSelectionResult(undo.selectedIds, undo.anchor, null)
    }
    val filled = selectBetweenSelectedLibraryItems(selectedIds, visibleIds)
    return ChapterRangeSelectionResult(
        selectedIds = filled,
        anchor = anchor,
        undo = if (filled == selectedIds) {
            null
        } else {
            ChapterRangeSelectionUndo(selectedIds.toSet(), anchor, visibleIds.toList(), filled.toSet())
        },
    )
}

/** Fill only the displayed interval between selected endpoints, preserving any existing selection. */
fun selectBetweenSelectedLibraryItems(selectedIds: Set<Long>, visibleIds: List<Long>): Set<Long> {
    val first = visibleIds.indexOfFirst { it in selectedIds }
    val last = visibleIds.indexOfLast { it in selectedIds }
    if (first < 0 || first == last) return selectedIds
    return selectedIds + visibleIds.subList(first, last + 1)
}

fun invertLibraryItems(
    state: LibrarySelectionResult,
    visibleIds: List<Long>,
): LibrarySelectionResult {
    val selected = state.selectedIds.toMutableSet()
    visibleIds.forEach { id ->
        if (!selected.add(id)) selected.remove(id)
    }
    return LibrarySelectionResult(
        selectedIds = selected,
        anchor = LibrarySelectionAnchor(),
    )
}
