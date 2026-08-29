package mihon.domain.reader

import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReaderPageDecodeKeyTest {

    @Test
    fun `full page key rejects region and frame`() {
        val contentKey = contentKey()

        ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.FULL_PAGE,
                maxWidth = 2_048,
                maxHeight = 2_048,
                region = PixelBounds(x = 0, y = 0, width = 100, height = 100),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.FULL_PAGE,
                maxWidth = 2_048,
                maxHeight = 2_048,
                frameIndex = 0,
            )
        }
    }

    @Test
    fun `region tile key requires region and rejects frame`() {
        val contentKey = contentKey()

        ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 1_024,
            maxHeight = 1_024,
            region = PixelBounds(x = 10, y = 20, width = 300, height = 400),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.REGION_TILE,
                maxWidth = 1_024,
                maxHeight = 1_024,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.REGION_TILE,
                maxWidth = 1_024,
                maxHeight = 1_024,
                region = PixelBounds(x = 10, y = 20, width = 300, height = 400),
                frameIndex = 0,
            )
        }
    }

    @Test
    fun `animation frame key requires non-negative frame and rejects region`() {
        val contentKey = contentKey()

        ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.ANIMATION_FRAME,
            maxWidth = 2_048,
            maxHeight = 2_048,
            frameIndex = 0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.ANIMATION_FRAME,
                maxWidth = 2_048,
                maxHeight = 2_048,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.ANIMATION_FRAME,
                maxWidth = 2_048,
                maxHeight = 2_048,
                frameIndex = -1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.ANIMATION_FRAME,
                maxWidth = 2_048,
                maxHeight = 2_048,
                region = PixelBounds(x = 0, y = 0, width = 100, height = 100),
                frameIndex = 0,
            )
        }
    }

    @Test
    fun `decode key derives page index and generation from stable content identity`() {
        val contentKey = contentKey(pageIndex = 7, generation = 11)

        val key = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        assertEquals(7, key.pageIndex)
        assertEquals(11L, key.generation)
        assertEquals(contentKey, key.contentKey)
    }

    @Test
    fun `decode key identity includes content purpose and decode bounds`() {
        val contentKey = contentKey()
        val baseline = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        assertEquals(baseline, baseline.copy())
        assertEquals(
            4,
            setOf(
                baseline,
                baseline.copy(contentKey = contentKey(encodedPageRef = "opaque://replacement")),
                baseline.copy(maxWidth = 1_024),
                ReaderPageDecodeKey(
                    contentKey = contentKey,
                    purpose = PageDecodePurpose.ANIMATION_FRAME,
                    maxWidth = 2_048,
                    maxHeight = 2_048,
                    frameIndex = 0,
                ),
            ).size,
        )
    }

    @Test
    fun `decode key requires positive decode bounds`() {
        val contentKey = contentKey()

        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.FULL_PAGE,
                maxWidth = 0,
                maxHeight = 2_048,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.FULL_PAGE,
                maxWidth = 2_048,
                maxHeight = -1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.REGION_TILE,
                maxWidth = 2_048,
                maxHeight = 2_048,
                region = PixelBounds(x = 0, y = 0, width = 0, height = 100),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPageDecodeKey(
                contentKey = contentKey,
                purpose = PageDecodePurpose.REGION_TILE,
                maxWidth = 2_048,
                maxHeight = 2_048,
                region = PixelBounds(x = -1, y = 0, width = 100, height = 100),
            )
        }
    }

    private fun contentKey(
        pageIndex: Int = 3,
        generation: Long = 5,
        encodedPageRef: String = "opaque://page-3",
    ) = ReaderPageContentOpenRequest(
        pageId = ReaderPageId(ReaderChapterId(42), pageIndex),
        generation = generation,
        encodedPageRef = EncodedPageRef(encodedPageRef),
    )
}
