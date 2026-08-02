package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderPageSize
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageSession

enum class ReaderPresentationMode {
    SINGLE_PAGED,
    WEBTOON,
    DUAL_PAGED,
}

data class DisplaySlotId(
    val pageId: ReaderPageId?,
    val splitHalf: PageSplitHalf? = null,
)

data class DisplayUnitId(
    val mode: ReaderPresentationMode,
    val slots: List<DisplaySlotId>,
)

internal data class DisplaySlot(
    val id: DisplaySlotId,
    val page: ReaderPageSession?,
    val splitHalf: PageSplitHalf? = id.splitHalf,
    val sourceBounds: PixelBounds? = null,
) {
    init {
        require(id.pageId == page?.id) { "Display slot identity must match its page" }
        require(id.splitHalf == splitHalf) { "Display slot identity must match its split half" }
    }
}

internal data class DisplayUnit(
    val id: DisplayUnitId,
    val slots: List<DisplaySlot>,
) {
    init {
        require(slots.isNotEmpty()) { "A display unit must contain at least one slot" }
        require(id.slots == slots.map(DisplaySlot::id)) { "Display unit identity must match its slots" }
    }
}

internal data class VisiblePageSet(
    val displayUnitId: DisplayUnitId,
    val pageIds: Set<ReaderPageId>,
)

internal data class ReaderPresentationRequest(
    val chapter: ReaderChapterSession,
    val direction: ReaderDirection,
    val splitPageIds: Set<ReaderPageId> = emptySet(),
    val pageSizes: Map<ReaderPageId, ReaderPageSize> = emptyMap(),
    val pageRotations: Map<ReaderPageId, PageRotation> = emptyMap(),
) {
    init {
        require(splitPageIds.all { it.chapterId == chapter.id }) {
            "Every split page must belong to the presented chapter"
        }
        require(pageSizes.keys.all { it.chapterId == chapter.id }) {
            "Every page size must belong to the presented chapter"
        }
        require(pageRotations.keys.all { it.chapterId == chapter.id }) {
            "Every page rotation must belong to the presented chapter"
        }
    }
}

internal data class ReaderPresentationSnapshot(
    val mode: ReaderPresentationMode,
    val displayUnits: List<DisplayUnit>,
) {
    init {
        require(displayUnits.all { it.id.mode == mode }) { "Every display unit must belong to the snapshot mode" }
        require(displayUnits.map(DisplayUnit::id).distinct().size == displayUnits.size) {
            "Display unit identities must be unique"
        }
    }

    fun firstDisplayUnitIndex(pageId: ReaderPageId): Int =
        displayUnits.indexOfFirst { unit -> unit.slots.any { it.page?.id == pageId } }

    fun restoreDisplayUnitIndex(
        pageId: ReaderPageId,
        displayUnitId: DisplayUnitId?,
    ): Int {
        val exactIndex = displayUnitId?.let { id -> displayUnits.indexOfFirst { it.id == id } } ?: -1
        return exactIndex.takeIf { it >= 0 } ?: firstDisplayUnitIndex(pageId)
    }

    fun visiblePages(displayUnitId: DisplayUnitId): VisiblePageSet {
        val unit = requireNotNull(displayUnits.firstOrNull { it.id == displayUnitId }) {
            "Unknown display unit: $displayUnitId"
        }
        return VisiblePageSet(
            displayUnitId = displayUnitId,
            pageIds = unit.slots.mapNotNullTo(linkedSetOf()) { it.page?.id },
        )
    }
}

internal interface ReaderPresentationStrategy {
    val mode: ReaderPresentationMode

    fun present(request: ReaderPresentationRequest): ReaderPresentationSnapshot
}

internal class ReaderPresentationRegistry(
    strategies: List<ReaderPresentationStrategy>,
) {
    private val strategiesByMode = strategies.associateBy(ReaderPresentationStrategy::mode)

    init {
        require(strategiesByMode.size == strategies.size) { "Each reader presentation mode must have one strategy" }
    }

    fun require(mode: ReaderPresentationMode): ReaderPresentationStrategy =
        requireNotNull(strategiesByMode[mode]) { "No reader presentation strategy registered for $mode" }
}

internal val DesktopReaderPresentationRegistry = ReaderPresentationRegistry(
    listOf(SinglePagedPresentation),
)
