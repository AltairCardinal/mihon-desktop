package mihon.domain.reader.content

import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReaderPageContentAttemptIdentityTest {

    @Test
    fun `two Retry operations monotonically advance attempt for the same stable target and ref`() {
        val initial = request(attemptGeneration = 0L)
        val firstRetry = initial.nextAttempt()
        val secondRetry = firstRetry.nextAttempt()
        val attempts = listOf(initial, firstRetry, secondRetry)

        assertEquals(listOf(0L, 1L, 2L), attempts.map { it.attemptGeneration })
        assertEquals(3, attempts.toSet().size)
        assertEquals(attempts.first().pageId, attempts.last().pageId)
        assertEquals(attempts.first().generation, attempts.last().generation)
        assertEquals(attempts.first().encodedPageRef, attempts.last().encodedPageRef)
        assertNotEquals(initial, firstRetry)
        assertNotEquals(firstRetry, secondRetry)
    }

    @Test
    fun `content attempt generation cannot be negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(attemptGeneration = -1L)
        }
    }

    @Test
    fun `content attempt cannot wrap and reuse an old identity`() {
        assertThrows(IllegalStateException::class.java) {
            request(attemptGeneration = Long.MAX_VALUE).nextAttempt()
        }
    }

    @Test
    fun `full frame and region decode identities include content attempt`() {
        val firstAttempt = request(attemptGeneration = 4L)
        val retryAttempt = request(attemptGeneration = 5L)

        PageDecodePurpose.entries.forEach { purpose ->
            val firstKey = decodeKey(firstAttempt, purpose)
            val retryKey = decodeKey(retryAttempt, purpose)

            assertNotEquals(firstKey, retryKey, "$purpose must not share decode identity across Retry attempts")
            assertEquals(firstKey.purpose, retryKey.purpose)
            assertEquals(firstKey.generation, retryKey.generation)
        }
    }

    @Test
    fun `encoded ref remains an independent part of identity while Retry keeps it stable`() {
        val initial = request(attemptGeneration = 2L)
        val retry = initial.nextAttempt()
        val replacementRef = initial.copy(encodedPageRef = EncodedPageRef("opaque://replacement-page"))

        assertEquals(initial.encodedPageRef, retry.encodedPageRef)
        assertNotEquals(initial, retry)
        assertNotEquals(initial, replacementRef)
        assertEquals(initial.attemptGeneration, replacementRef.attemptGeneration)
    }

    private fun request(attemptGeneration: Long) = ReaderPageContentOpenRequest(
        pageId = ReaderPageId(ReaderChapterId(31L), sourcePageIndex = 7),
        generation = 9L,
        encodedPageRef = EncodedPageRef("opaque://stable-page"),
        attemptGeneration = attemptGeneration,
    )

    private fun decodeKey(
        contentKey: ReaderPageContentOpenRequest,
        purpose: PageDecodePurpose,
    ) = ReaderPageDecodeKey(
        contentKey = contentKey,
        purpose = purpose,
        maxWidth = 2_048,
        maxHeight = 2_048,
        region = PixelBounds(x = 0, y = 0, width = 512, height = 512)
            .takeIf { purpose == PageDecodePurpose.REGION_TILE },
        frameIndex = 0.takeIf { purpose == PageDecodePurpose.ANIMATION_FRAME },
    )
}
