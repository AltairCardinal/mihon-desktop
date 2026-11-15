package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPagePresentationFrame
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.readerChapterBoundary
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.splitPageBounds

internal data class DualPagedPresentationOptions(
    val spreadPageIds: Set<ReaderPageId> = emptySet(),
) {
    internal val pageIds: Set<ReaderPageId>
        get() = spreadPageIds
}

internal object DualPagedPresentation : ReaderPresentationStrategy {
    override val mode = ReaderPresentationMode.DUAL_PAGED

    override fun present(request: ReaderPresentationRequest): ReaderPresentationSnapshot {
        require(request.direction != ReaderDirection.VERTICAL) {
            "Dual-page presentation requires a horizontal direction"
        }
        val pages = request.chapter.pages
        val groups = request.dualPageFrames ?: requireNotNull(request.dualPageUnits) {
            "Dual presentation requires shared owner units"
        }.map { ReaderDualPagePresentationFrame(it, null) }
        return ReaderPresentationSnapshot(
            mode = mode,
            displayUnits = buildList {
                if (request.alwaysShowChapterTransition ||
                    readerChapterBoundary(request.hasPreviousChapter) == ReaderChapterBoundary.TERMINAL
                ) {
                    add(chapterTransitionDisplayUnit(mode, ReaderTransitionDirection.PREVIOUS))
                }
                val pagesById = pages.associateBy(ReaderPageSession::id)
                addAll(
                    groups.map { group ->
                        request.toDisplayUnit(group, pagesById)
                    },
                )
                if (request.alwaysShowChapterTransition ||
                    readerChapterBoundary(request.hasNextChapter) == ReaderChapterBoundary.TERMINAL
                ) {
                    add(chapterTransitionDisplayUnit(mode, ReaderTransitionDirection.NEXT))
                }
            },
            targetFrameId = request.dualPageTargetFrameId,
            targetPageId = request.dualPageTargetPageId,
        )
    }

    private fun ReaderPresentationRequest.toDisplayUnit(
        frame: ReaderDualPagePresentationFrame,
        pagesById: Map<ReaderPageId, ReaderPageSession>,
    ): DisplayUnit {
        val unit = frame.unit
        val pages = unit.pageIds.map(pagesById::getValue)
        val slots = when {
            pages.size == 2 ->
                pages
                    .let { if ((direction == ReaderDirection.RTL) != invertSplitPages) it.asReversed() else it }
                    .map { page -> pageSlot(page) }

            pages.single().id in dualPagedOptions.spreadPageIds -> spreadSlots(pages.single())
            else -> singlePageSlots(pages.single(), unit.slots.single().position, frame.blankPaperSlot)
        }
        return DisplayUnit(
            id = DisplayUnitId(
                mode,
                slots.map(DisplaySlot::id),
                unit.id.takeIf {
                    dualPageFrames != null
                },
                frame.frameId,
            ),
            slots = slots,
        )
    }

    private fun ReaderPresentationRequest.spreadSlots(page: ReaderPageSession): List<DisplaySlot> {
        if (page.id.sourcePageIndex == 0 || page.id !in splitPageIds) return listOf(pageSlot(page))
        val halves = if ((direction == ReaderDirection.RTL) != invertSplitPages) {
            listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT)
        } else {
            listOf(PageSplitHalf.LEFT, PageSplitHalf.RIGHT)
        }
        return halves.map { half -> pageSlot(page, half) }
    }

    private fun ReaderPresentationRequest.singlePageSlots(
        page: ReaderPageSession,
        slot: ReaderPortraitSingleSlot,
        blankPaperSlot: ReaderPortraitSingleSlot?,
    ): List<DisplaySlot> {
        return when (slot) {
            ReaderPortraitSingleSlot.FULL -> listOf(pageSlot(page))
            ReaderPortraitSingleSlot.LEFT -> listOf(
                pageSlot(page),
                emptySlot(blankPaperSlot == ReaderPortraitSingleSlot.RIGHT),
            )
            ReaderPortraitSingleSlot.RIGHT -> listOf(
                emptySlot(blankPaperSlot == ReaderPortraitSingleSlot.LEFT),
                pageSlot(page),
            )
        }
    }

    private fun ReaderPresentationRequest.pageSlot(
        page: ReaderPageSession,
        half: PageSplitHalf? = null,
    ): DisplaySlot {
        val id = DisplaySlotId(page.id, half)
        return DisplaySlot(
            id = id,
            page = page,
            sourceBounds = pageBounds(page.id, half),
        )
    }

    private fun ReaderPresentationRequest.pageBounds(
        pageId: ReaderPageId,
        half: PageSplitHalf?,
    ): PixelBounds? {
        val size = pageSizes[pageId]
        return if (half != null && size != null) {
            splitPageBounds(
                imageWidth = size.width,
                imageHeight = size.height,
                half = half,
                rotation = pageRotations[pageId] ?: PageRotation.NONE,
            )
        } else {
            null
        }
    }

    private fun emptySlot(fill: Boolean): DisplaySlot =
        DisplaySlot(DisplaySlotId(pageId = null), page = null, isBlankPaper = fill)
}

internal fun ReaderPresentationSnapshot.resolveDualVisiblePages(displayUnitId: DisplayUnitId): VisiblePageSet {
    require(mode == ReaderPresentationMode.DUAL_PAGED) { "Only a dual-page snapshot can resolve dual visibility" }
    val unit = requireNotNull(displayUnits.firstOrNull { it.id == displayUnitId }) {
        "Unknown display unit: $displayUnitId"
    }
    val pageIds = unit.slots.mapNotNullTo(linkedSetOf()) { it.page?.id }
    return VisiblePageSet(
        displayUnitId = displayUnitId,
        pageIds = pageIds,
        activePageId = pageIds.minByOrNull(ReaderPageId::sourcePageIndex),
        transitionDirection = unit.transitionDirection,
    )
}

internal fun ReaderPresentationSnapshot.firstDualPageIndex(displayUnitIndex: Int): Int =
    resolveDualVisiblePages(displayUnits[displayUnitIndex].id).activePageId?.sourcePageIndex
        ?: error("Dual display unit $displayUnitIndex has no logical page")

internal fun ReaderPresentationSnapshot.dualDisplayUnitIndexForSourcePage(sourcePageIndex: Int): Int =
    targetPageId?.takeIf { it.sourcePageIndex == sourcePageIndex }?.let { restoreDisplayUnitIndex(it, null) }
        ?: displayUnits.indexOfFirst { unit -> unit.slots.any { it.page?.id?.sourcePageIndex == sourcePageIndex } }
