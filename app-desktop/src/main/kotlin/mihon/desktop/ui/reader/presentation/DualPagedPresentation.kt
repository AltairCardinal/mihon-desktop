package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageLayout
import mihon.domain.reader.PagePair
import mihon.domain.reader.PagePairingOptions
import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderPagePairing
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.portraitSinglePageSlot
import mihon.domain.reader.readerChapterBoundary
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.splitPageBounds

internal data class DualPagedPresentationOptions(
    val spreadPageIds: Set<ReaderPageId> = emptySet(),
    val forcedSinglePageIds: Set<ReaderPageId> = emptySet(),
    val matchedPagePairs: Set<Pair<ReaderPageId, ReaderPageId>> = emptySet(),
) {
    internal val pageIds: Set<ReaderPageId>
        get() = spreadPageIds + forcedSinglePageIds + matchedPagePairs.flatMap { listOf(it.first, it.second) }
}

internal object DualPagedPresentation : ReaderPresentationStrategy {
    override val mode = ReaderPresentationMode.DUAL_PAGED

    override fun present(request: ReaderPresentationRequest): ReaderPresentationSnapshot {
        require(request.direction != ReaderDirection.VERTICAL) { "Dual-page presentation requires a horizontal direction" }
        val pages = request.chapter.pages
        val indexById = pages.mapIndexed { index, page -> page.id to index }.toMap()
        val options = request.dualPagedOptions
        val groups = ReaderPagePairing.build(
            pageCount = pages.size,
            layoutAt = { index ->
                if (pages[index].id in options.spreadPageIds) PageLayout.SPREAD else PageLayout.PORTRAIT
            },
            options = PagePairingOptions(
                pairAdjacentPortraitPages = true,
                forceFirstPageSingle = true,
                forcedSinglePages = options.forcedSinglePageIds.mapNotNullTo(linkedSetOf(), indexById::get),
                matchedPairs = options.matchedPagePairs.mapNotNullTo(linkedSetOf()) { (first, second) ->
                    val firstIndex = indexById[first]
                    val secondIndex = indexById[second]
                    if (firstIndex == null || secondIndex == null) null else PagePair(firstIndex, secondIndex)
                },
                preserveParityAfterSpread = true,
            ),
        )
        return ReaderPresentationSnapshot(
            mode = mode,
            displayUnits = buildList {
                if (readerChapterBoundary(request.hasPreviousChapter) == ReaderChapterBoundary.TERMINAL) {
                    add(chapterTransitionDisplayUnit(mode, ReaderTransitionDirection.PREVIOUS))
                }
                addAll(groups.mapIndexed { groupIndex, group ->
                    request.toDisplayUnit(groupIndex, groups.lastIndex, group.map(pages::get))
                })
                if (readerChapterBoundary(request.hasNextChapter) == ReaderChapterBoundary.TERMINAL) {
                    add(chapterTransitionDisplayUnit(mode, ReaderTransitionDirection.NEXT))
                }
            },
        )
    }

    private fun ReaderPresentationRequest.toDisplayUnit(
        groupIndex: Int,
        lastGroupIndex: Int,
        pages: List<ReaderPageSession>,
    ): DisplayUnit {
        val slots = when {
            pages.size == 1 && pages[0].id.sourcePageIndex == 0 && pages[0].id in dualPagedOptions.spreadPageIds ->
                listOf(pageSlot(pages.single()), emptySlot())

            pages.size == 2 -> pages
                .let { if (direction == ReaderDirection.RTL) it.asReversed() else it }
                .map { page -> pageSlot(page) }

            pages.single().id in dualPagedOptions.spreadPageIds -> spreadSlots(pages.single())
            else -> singlePageSlots(
                page = pages.single(),
                groupIndex = groupIndex,
                lastGroupIndex = lastGroupIndex,
            )
        }
        return DisplayUnit(
            id = DisplayUnitId(mode, slots.map(DisplaySlot::id)),
            slots = slots,
        )
    }

    private fun ReaderPresentationRequest.spreadSlots(page: ReaderPageSession): List<DisplaySlot> {
        if (page.id !in splitPageIds) return listOf(pageSlot(page))
        val halves = if (direction == ReaderDirection.RTL) {
            listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT)
        } else {
            listOf(PageSplitHalf.LEFT, PageSplitHalf.RIGHT)
        }
        return halves.map { half -> pageSlot(page, half) }
    }

    private fun ReaderPresentationRequest.singlePageSlots(
        page: ReaderPageSession,
        groupIndex: Int,
        lastGroupIndex: Int,
    ): List<DisplaySlot> {
        return when (portraitSinglePageSlot(direction, groupIndex, lastGroupIndex + 1)) {
            ReaderPortraitSingleSlot.FULL -> listOf(pageSlot(page))
            ReaderPortraitSingleSlot.LEFT -> listOf(pageSlot(page), emptySlot())
            ReaderPortraitSingleSlot.RIGHT -> listOf(emptySlot(), pageSlot(page))
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

    private fun emptySlot(): DisplaySlot = DisplaySlot(DisplaySlotId(pageId = null), page = null)
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
    displayUnits.indexOfFirst { unit -> unit.slots.any { it.page?.id?.sourcePageIndex == sourcePageIndex } }
