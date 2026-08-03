package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageLayout
import mihon.domain.reader.PagePair
import mihon.domain.reader.PagePairingOptions
import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderPagePairing
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
            displayUnits = groups.mapIndexed { groupIndex, group ->
                request.toDisplayUnit(groupIndex, groups.lastIndex, group.map(pages::get))
            },
        )
    }

    private fun ReaderPresentationRequest.toDisplayUnit(
        groupIndex: Int,
        lastGroupIndex: Int,
        pages: List<ReaderPageSession>,
    ): DisplayUnit {
        val slots = when {
            pages.singleOrNull()?.id?.sourcePageIndex == 0 -> singlePageSlots(
                page = pages.single(),
                groupIndex = groupIndex,
                lastGroupIndex = lastGroupIndex,
            )

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
        if (page.id.sourcePageIndex == 0) return listOf(pageSlot(page), emptySlot())
        val trailing = groupIndex < lastGroupIndex
        val slotOnRight = if (trailing) {
            direction == ReaderDirection.LTR
        } else {
            direction == ReaderDirection.RTL
        }
        return if (slotOnRight) {
            listOf(emptySlot(), pageSlot(page))
        } else {
            listOf(pageSlot(page), emptySlot())
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
        activePageId = pageIds.maxByOrNull(ReaderPageId::sourcePageIndex),
    )
}

internal fun ReaderPresentationSnapshot.firstDualPageIndex(displayUnitIndex: Int): Int =
    displayUnits[displayUnitIndex].slots.mapNotNull { it.page?.id?.sourcePageIndex }.minOrNull()
        ?: error("Dual display unit $displayUnitIndex has no logical page")

internal fun ReaderPresentationSnapshot.dualDisplayUnitIndexForSourcePage(sourcePageIndex: Int): Int =
    displayUnits.indexOfFirst { unit -> unit.slots.any { it.page?.id?.sourcePageIndex == sourcePageIndex } }
