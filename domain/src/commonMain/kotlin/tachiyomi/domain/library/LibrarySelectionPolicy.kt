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
): LibrarySelectionResult {
    val anchor = state.anchor
    if (anchor.id == null) {
        return LibrarySelectionResult(
            selectedIds = state.selectedIds + targetId,
            anchor = LibrarySelectionAnchor(targetId, categoryId),
        )
    }
    if (anchor.id != null && anchor.categoryId != categoryId) {
        return LibrarySelectionResult(
            selectedIds = state.selectedIds + targetId,
            anchor = LibrarySelectionAnchor(targetId, categoryId),
        )
    }

    val anchorIndex = visibleIds.indexOf(anchor.id)
    val targetIndex = visibleIds.indexOf(targetId)
    if (anchorIndex < 0 || targetIndex < 0) {
        return LibrarySelectionResult(
            selectedIds = state.selectedIds + targetId,
            anchor = LibrarySelectionAnchor(targetId, categoryId),
        )
    }

    val range = if (anchorIndex <= targetIndex) anchorIndex..targetIndex else targetIndex..anchorIndex
    return LibrarySelectionResult(
        selectedIds = state.selectedIds + range.map { visibleIds[it] },
        anchor = LibrarySelectionAnchor(targetId, categoryId),
    )
}

fun selectAllLibraryItems(
    state: LibrarySelectionResult,
    visibleIds: List<Long>,
): LibrarySelectionResult = LibrarySelectionResult(
    selectedIds = state.selectedIds + visibleIds,
    anchor = LibrarySelectionAnchor(),
)

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
