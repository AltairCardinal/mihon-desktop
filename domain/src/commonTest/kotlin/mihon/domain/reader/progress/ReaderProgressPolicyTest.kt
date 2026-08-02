package mihon.domain.reader.progress

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderProgressPolicyTest {

    @Test
    fun `only a settled viewport in the active chapter produces a progress effect`() {
        val chapterId = chapterId(2)
        val pageId = pageId(chapterId, 4)

        assertNull(ReaderProgressPolicy.reduce(ReaderProgressSignal.ChapterOpened(chapterId)))
        assertNull(ReaderProgressPolicy.reduce(ReaderProgressSignal.PagePrepared(pageId)))
        assertNull(
            ReaderProgressPolicy.reduce(
                settled(
                    activeChapterId = chapterId(1),
                    chapterId = chapterId,
                    visiblePageIndices = setOf(4),
                    totalPages = 10,
                ),
            ),
        )

        val effect = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(
                    activeChapterId = chapterId,
                    chapterId = chapterId,
                    visiblePageIndices = setOf(4),
                    totalPages = 10,
                ),
            ),
        )
        assertEquals(chapterId, effect.chapterId)
        assertEquals(4, effect.lastPageRead)
    }

    @Test
    fun `settled spread records its highest visible logical page and only the last page completes`() {
        val chapterId = chapterId(7)

        val penultimate = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(chapterId, chapterId, setOf(7, 8), totalPages = 10),
            ),
        )
        val finalSpread = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(chapterId, chapterId, setOf(8, 9), totalPages = 10, settlementSequence = 2),
            ),
        )

        assertEquals(8, penultimate.lastPageRead)
        assertFalse(penultimate.reachedLastPage)
        assertFalse(penultimate.isRead)
        assertEquals(9, finalSpread.lastPageRead)
        assertTrue(finalSpread.reachedLastPage)
        assertTrue(finalSpread.isRead)
    }

    @Test
    fun `a partial settled page never clears an existing read state`() {
        val chapterId = chapterId(4)

        val effect = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(
                    activeChapterId = chapterId,
                    chapterId = chapterId,
                    visiblePageIndices = setOf(2),
                    totalPages = 10,
                    wasRead = true,
                ),
            ),
        )

        assertFalse(effect.reachedLastPage)
        assertTrue(effect.isRead)
    }

    @Test
    fun `settlement identity creates a stable idempotency key and a later settlement creates a new key`() {
        val chapterId = chapterId(3)
        val first = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(chapterId, chapterId, setOf(5), totalPages = 10, settlementSequence = 8),
            ),
        )
        val replay = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(chapterId, chapterId, setOf(5), totalPages = 10, settlementSequence = 8),
            ),
        )
        val later = requireNotNull(
            ReaderProgressPolicy.reduce(
                settled(chapterId, chapterId, setOf(5), totalPages = 10, settlementSequence = 9),
            ),
        )

        assertEquals(first.idempotencyKey, replay.idempotencyKey)
        assertTrue(first.idempotencyKey.contains(":3:5:8"))
        assertTrue(first.idempotencyKey != later.idempotencyKey)
    }

    @Test
    fun `reader entry chooses the story earliest unfinished chapter from ascending or descending input`() {
        val ascending = listOf(
            ReaderEntryCandidate(chapterId(1), isRead = true),
            ReaderEntryCandidate(chapterId(2), isRead = false),
            ReaderEntryCandidate(chapterId(3), isRead = false),
        )
        val descending = ascending.reversed()

        assertEquals(
            chapterId(2),
            resolveReaderEntry(ascending, ReaderChapterDisplayOrder.STORY_ASCENDING),
        )
        assertEquals(
            chapterId(2),
            resolveReaderEntry(descending, ReaderChapterDisplayOrder.STORY_DESCENDING),
        )
        assertNull(
            resolveReaderEntry(
                ascending.map { it.copy(isRead = true) },
                ReaderChapterDisplayOrder.STORY_ASCENDING,
            ),
        )
    }

    private fun settled(
        activeChapterId: ReaderChapterId,
        chapterId: ReaderChapterId,
        visiblePageIndices: Set<Int>,
        totalPages: Int,
        wasRead: Boolean = false,
        settlementSequence: Long = 1,
    ) = ReaderProgressSignal.ViewportSettled(
        activeChapterId = activeChapterId,
        chapterId = chapterId,
        visiblePageIds = visiblePageIndices.mapTo(linkedSetOf()) { pageId(chapterId, it) },
        totalPages = totalPages,
        wasRead = wasRead,
        sessionId = "reader-session",
        settlementSequence = settlementSequence,
    )

    private fun chapterId(value: Long) = ReaderChapterId(value)

    private fun pageId(chapterId: ReaderChapterId, index: Int) = ReaderPageId(chapterId, index)
}
