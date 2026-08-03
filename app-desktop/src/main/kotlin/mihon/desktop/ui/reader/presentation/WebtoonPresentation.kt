package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.splitPageBounds
import kotlin.math.roundToInt

internal object WebtoonPresentation : ReaderPresentationStrategy {
    override val mode = ReaderPresentationMode.WEBTOON

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
        return halves.map { half -> toDisplayUnit(page, half) }
    }

    private fun ReaderPresentationRequest.pageBounds(
        page: ReaderPageSession,
        half: PageSplitHalf?,
    ): PixelBounds? {
        val size = pageSizes[page.id]
        return if (half != null && size != null) {
            splitPageBounds(
                imageWidth = size.width,
                imageHeight = size.height,
                half = half,
                rotation = pageRotations[page.id] ?: PageRotation.NONE,
            )
        } else {
            null
        }
    }

    private fun ReaderPresentationRequest.toDisplayUnit(
        page: ReaderPageSession,
        half: PageSplitHalf?,
    ): DisplayUnit {
        val slotId = DisplaySlotId(page.id, half)
        return DisplayUnit(
            id = DisplayUnitId(mode, listOf(slotId)),
            slots = listOf(
                DisplaySlot(
                    id = slotId,
                    page = page,
                    sourceBounds = pageBounds(page, half),
                ),
            ),
        )
    }
}

internal data class WebtoonVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
) {
    init {
        require(index >= 0) { "Visible item index must be non-negative" }
        require(size >= 0) { "Visible item size must be non-negative" }
    }

    val endOffset: Int
        get() = offset + size
}

data class WebtoonScrollAnchor(
    val displayUnitId: DisplayUnitId,
    val scrollOffset: Int,
    val itemSize: Int? = null,
) {
    init {
        require(displayUnitId.mode == ReaderPresentationMode.WEBTOON) {
            "A webtoon anchor must reference a webtoon display unit"
        }
        require(scrollOffset >= 0) { "Scroll offset must be non-negative" }
        require(itemSize == null || itemSize > 0) { "Anchor item size must be positive when present" }
    }

    internal fun restoreOffsetFor(itemSize: Int): Int {
        require(itemSize > 0) { "Restored item size must be positive" }
        val restored = this.itemSize?.let { previousSize ->
            (scrollOffset.toDouble() / previousSize * itemSize).roundToInt()
        } ?: scrollOffset
        return restored.coerceIn(0, itemSize - 1)
    }
}

internal data class WebtoonViewportUpdate(
    val visiblePages: VisiblePageSet,
    val anchor: WebtoonScrollAnchor,
)

internal fun ReaderPresentationSnapshot.resolveWebtoonViewport(
    visibleItems: List<WebtoonVisibleItem>,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): WebtoonViewportUpdate? {
    require(mode == ReaderPresentationMode.WEBTOON) { "Only a webtoon snapshot can resolve a webtoon viewport" }
    require(viewportEndOffset >= viewportStartOffset) { "Viewport end must not precede its start" }

    val intersecting = visibleItems
        .filter { item ->
            item.index in displayUnits.indices &&
                item.endOffset > viewportStartOffset &&
                item.offset < viewportEndOffset
        }
        .sortedBy(WebtoonVisibleItem::index)
    if (intersecting.isEmpty()) return null

    // Fixed-main WebtoonLayoutManager.findLastEndVisibleItemPosition scans from the end and
    // selects the last item whose end is visible, falling back to an item crossing the start.
    val activeItem = intersecting.asReversed().firstOrNull { item ->
        item.endOffset <= viewportEndOffset || item.offset < viewportStartOffset
    } ?: return null
    val activeUnit = displayUnits[activeItem.index]
    val activePageId = activeUnit.slots.firstNotNullOfOrNull { it.page?.id } ?: return null
    val visiblePageIds = intersecting.flatMapTo(linkedSetOf()) { item ->
        displayUnits[item.index].slots.mapNotNull { it.page?.id }
    }
    val anchorItem = intersecting.first()

    return WebtoonViewportUpdate(
        visiblePages = VisiblePageSet(
            displayUnitId = activeUnit.id,
            pageIds = visiblePageIds,
            activePageId = activePageId,
        ),
        anchor = WebtoonScrollAnchor(
            displayUnitId = displayUnits[anchorItem.index].id,
            scrollOffset = (viewportStartOffset - anchorItem.offset).coerceAtLeast(0),
            itemSize = anchorItem.size,
        ),
    )
}

internal fun ReaderPresentationSnapshot.restoreWebtoonAnchorIndex(anchor: WebtoonScrollAnchor): Int {
    require(mode == ReaderPresentationMode.WEBTOON) { "Only a webtoon snapshot can restore a webtoon anchor" }
    val pageId = anchor.displayUnitId.slots.firstNotNullOfOrNull { it.pageId } ?: return -1
    return restoreDisplayUnitIndex(pageId, anchor.displayUnitId)
}

internal fun DisplayUnit.requireWebtoonPageId(): ReaderPageId {
    require(id.mode == ReaderPresentationMode.WEBTOON) { "Display unit is not a webtoon item" }
    return requireNotNull(slots.firstNotNullOfOrNull { it.page?.id }) { "Webtoon item has no logical page" }
}
