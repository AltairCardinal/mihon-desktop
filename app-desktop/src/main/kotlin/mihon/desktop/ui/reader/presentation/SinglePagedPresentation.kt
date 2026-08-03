package mihon.desktop.ui.reader.presentation

import mihon.domain.error.AppError
import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
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

/**
 * Migration-only projection from the pre-core Desktop URL slots to the canonical reader session.
 * It performs no I/O and must be deleted when RD-01 connects Desktop directly to ReaderSessionCore.
 */
internal object LegacyDesktopReaderPresentationAdapter {
    fun singlePagedRequest(
        chapterId: Long,
        generation: Long,
        pageUrls: List<String>,
        direction: ReaderDirection,
        splitPageIndices: Set<Int>,
        pageError: AppError? = null,
    ): ReaderPresentationRequest {
        val canonicalChapterId = ReaderChapterId(chapterId)
        val pages = pageUrls.mapIndexed { sourcePageIndex, contentUrl ->
            ReaderPageSession(
                id = ReaderPageId(canonicalChapterId, sourcePageIndex),
                url = contentUrl,
                imageUrl = contentUrl.takeIf(String::isNotBlank),
                encodedPageRef = null,
                loadState = when {
                    contentUrl.isNotBlank() -> ReaderPageLoadState.Ready
                    pageError != null -> ReaderPageLoadState.Error(pageError)
                    else -> ReaderPageLoadState.Queued
                },
            )
        }
        return ReaderPresentationRequest(
            chapter = ReaderChapterSession(
                id = canonicalChapterId,
                generation = generation,
                loadState = ReaderChapterLoadState.Loaded,
                pages = pages,
            ),
            direction = direction,
            splitPageIds = splitPageIndices
                .filter { it in pages.indices }
                .mapTo(linkedSetOf()) { pages[it].id },
        )
    }

    fun webtoonRequest(
        chapterId: Long,
        generation: Long,
        pageUrls: List<String>,
        splitPageIndices: Set<Int>,
        pageError: AppError? = null,
    ): ReaderPresentationRequest = singlePagedRequest(
        chapterId = chapterId,
        generation = generation,
        pageUrls = pageUrls,
        direction = ReaderDirection.RTL,
        splitPageIndices = splitPageIndices,
        pageError = pageError,
    )
}
