package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.splitPageBounds

internal object SinglePagedPresentation : ReaderPresentationStrategy {
    override val mode = ReaderPresentationMode.SINGLE_PAGED

    override fun present(request: ReaderPresentationRequest): ReaderPresentationSnapshot =
        ReaderPresentationSnapshot(
            mode = mode,
            displayUnits = request.chapter.pages.flatMap { page -> request.unitsFor(page) },
        )

    private fun ReaderPresentationRequest.unitsFor(page: ReaderPageSession): List<DisplayUnit> {
        val halves = if (page.id in splitPageIds) {
            if (direction == ReaderDirection.RTL) {
                listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT)
            } else {
                listOf(PageSplitHalf.LEFT, PageSplitHalf.RIGHT)
            }
        } else {
            listOf(null)
        }
        return halves.map { splitHalf ->
            val slotId = DisplaySlotId(page.id, splitHalf)
            val size = pageSizes[page.id]
            val sourceBounds = if (splitHalf != null && size != null) {
                splitPageBounds(
                    imageWidth = size.width,
                    imageHeight = size.height,
                    half = splitHalf,
                    rotation = pageRotations[page.id] ?: PageRotation.NONE,
                )
            } else {
                null
            }
            DisplayUnit(
                id = DisplayUnitId(mode, listOf(slotId)),
                slots = listOf(
                    DisplaySlot(
                        id = slotId,
                        page = page,
                        sourceBounds = sourceBounds,
                    ),
                ),
            )
        }
    }
}
