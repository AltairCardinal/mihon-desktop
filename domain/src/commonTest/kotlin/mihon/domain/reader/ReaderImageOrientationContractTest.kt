package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderImageOrientationContractTest {
    @Test
    fun `interleaved direction confirmations preserve other tickets and reject replaced bytes`() {
        val chapter = mihon.domain.reader.session.ReaderChapterId(8)
        val pages = List(2) { mihon.domain.reader.session.ReaderPageId(chapter, it) }
        val owner = SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                pages.map {
                    ReaderDualPageContentIdentity(it, "encoded:${it.sourcePageIndex}")
                },
            ),
            ReaderDirection.RTL,
            pages.last(),
        )
        val first = owner.requestFact(pages[0])
        val second = owner.requestFact(pages[1])
        val budget = owner.factBudget
        val fact = ReaderPageFact.dimensions(40, 60, sourceOrientation = ReaderImageOrientation.CLOCKWISE)
        assertTrue(!owner.acceptFact(first, fact), "Ordinary consumers cannot bypass direction confirmation")
        assertTrue(owner.acceptOrientedFact(first, fact) === owner)
        assertTrue(
            !owner.acceptOrientedFact(first, fact).let { it != null },
            "The confirmed page's original cap is revoked",
        )
        assertTrue(
            owner.acceptOrientedFact(
                second,
                fact.copy(
                    sourceOrientation =
                    ReaderImageOrientation.COUNTER_CLOCKWISE,
                ),
            ) === owner,
        )
        assertTrue(owner.factBudget === budget)
        assertEquals(
            listOf(ReaderImageOrientation.CLOCKWISE, ReaderImageOrientation.COUNTER_CLOCKWISE),
            owner.identity.content.map { it.sourceOrientation },
        )
        val fresh = owner.requestFact(pages[1])
        val replacement = owner.rebindContent(
            owner.identity.copy(
                pageListRevision = 2,
                content = owner.identity.content.map {
                    if (it.pageId == pages[1]) {
                        it.copy(
                            contentVersion =
                            "replaced:2",
                        )
                    } else {
                        it
                    }
                },
            ),
        )
        assertTrue(!replacement.acceptOrientedFact(fresh, fact).let { it != null })
        assertTrue(
            !SharedDualPageSession(
                replacement.identity,
                ReaderDirection.RTL,
                pages.last(),
            ).acceptOrientedFact(fresh, fact).let { it != null },
        )
    }

    @Test
    fun `PNG WebP big endian and malformed metadata remain bounded without pixel decoding`() {
        val big = "4d4d002a00000008000101120003000000010008000000000000".chunked(2).map {
            it.toInt(16).toByte()
        }.toByteArray()
        val png = byteArrayOf(-119) + "PNG\r\n\u001a\n".encodeToByteArray() + byteArrayOf(0, 0, 0, big.size.toByte()) +
            "eXIf".encodeToByteArray() + big + ByteArray(4)
        assertEquals(ReaderImageOrientation.COUNTER_CLOCKWISE, readImageOrientation(png))
        val webp = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBPEXIF".encodeToByteArray() +
            byteArrayOf(big.size.toByte(), 0, 0, 0) + big
        assertEquals(ReaderImageOrientation.COUNTER_CLOCKWISE, readImageOrientation(webp))
        assertEquals(ReaderImageOrientation.NORMAL, readImageOrientation(png.copyOf(16)))
        val badOffset = big.copyOf().also {
            it[4] = -1
            it[5] = -1
            it[6] = -1
            it[7] = -1
        }
        assertEquals(ReaderImageOrientation.NORMAL, readImageOrientation(webp.copyOfRange(0, 20) + badOffset))
    }

    @Test
    fun `first orientation confirmation preserves logical read cause and budget`() {
        val page = mihon.domain.reader.session.ReaderPageId(mihon.domain.reader.session.ReaderChapterId(7), 0)
        val identity = ReaderDualPageIdentity(1, 1, listOf(ReaderDualPageContentIdentity(page, "encoded:1")))
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, page)
        val viewport = requireNotNull(owner.captureViewport())
        val original = owner.requestFact(page)
        val budget = owner.factBudget
        val location = owner.location
        val next = requireNotNull(
            owner.acceptOrientedFact(
                original,
                ReaderPageFact.dimensions(
                    40,
                    60,
                    sourceOrientation = ReaderImageOrientation.CLOCKWISE,
                ),
            ),
        )
        assertEquals(ReaderImageOrientation.CLOCKWISE, next.identity.content.single().sourceOrientation)
        assertTrue(next === owner, "Only same-byte metadata changes retain this owner and its physical work")
        assertTrue(next.identity != identity)
        assertEquals(location, next.location)
        assertTrue(next.factBudget === budget)
        assertTrue(next.units.single().certain)
        assertEquals(ReaderDualPageSingleReason.ONLY_PAGE, next.units.single().singleReason)
        assertTrue(requireNotNull(next.captureViewport()).readingEligible)
        assertTrue(!next.acceptFact(original, ReaderPageFact.dimensions(60, 40)))
        val old = ReaderDualPageSettlement(
            viewport.identity, viewport.layoutRevision, viewport.locationSequence,
            viewport.unitId, setOf(page), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, viewport,
        )
        assertEquals(null, next.settle(old, false, "orientation"))
    }

    @Test
    fun `every normalized odd half pixel belongs to exactly its inverse mapped source region`() {
        ReaderImageOrientation.entries.forEach { orientation ->
            val size = orientation.orientedSize(61, 41)
            PageSplitHalf.entries.forEach { half ->
                val region = requireNotNull(orientation.splitBounds(61, 41, half))
                val start = if (half == PageSplitHalf.LEFT) 0 else size.width / 2
                val end = if (half == PageSplitHalf.LEFT) size.width / 2 else size.width
                assertEquals(
                    (end - start) * size.height,
                    region.width * region.height,
                    "$orientation $half must keep its own normalized width",
                )
                for (x in 0 until size.width) {
                    for (y in 0 until size.height) {
                        val (sx, sy) = orientation.sourcePixel(x, y, 61, 41)
                        assertEquals(
                            x in start until end,
                            sx in region.x until region.x + region.width &&
                                sy in region.y until region.y + region.height,
                            "$orientation $half normalized($x,$y) source($sx,$sy)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `bounded encoded EXIF headers preserve all eight source orientations`() {
        for (orientation in ReaderImageOrientation.entries) {
            assertEquals(orientation, readImageOrientation(jpegHeader(orientation.exifValue)))
        }
        assertEquals(ReaderImageOrientation.NORMAL, readImageOrientation(jpegHeader(9)))
        val encoded = jpegHeader(6)
        for (length in 0 until encoded.size - 2) {
            assertEquals(ReaderImageOrientation.NORMAL, readImageOrientation(encoded.copyOf(length)))
        }
    }

    @Test
    fun `normalized four corners and halves map to original pixels without losing odd centers`() {
        // Source four corners are TL=0, TR=1, BL=2, BR=3.
        val expected = listOf(
            listOf(0, 1, 2, 3),
            listOf(1, 0, 3, 2),
            listOf(3, 2, 1, 0),
            listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3),
            listOf(2, 0, 3, 1),
            listOf(3, 1, 2, 0),
            listOf(1, 3, 0, 2),
        )
        ReaderImageOrientation.entries.forEachIndexed { index, orientation ->
            val size = orientation.orientedSize(61, 41)
            val corners = listOf(0 to 0, size.width - 1 to 0, 0 to size.height - 1, size.width - 1 to size.height - 1)
            val actual = corners.map { (x, y) ->
                orientation.sourcePixel(x, y, 61, 41).let { (sx, sy) ->
                    (if (sy == 0) 0 else 2) + (if (sx == 0) 0 else 1)
                }
            }
            assertEquals(expected[index], actual)
            val left = requireNotNull(orientation.splitBounds(61, 41, PageSplitHalf.LEFT))
            val right = requireNotNull(orientation.splitBounds(61, 41, PageSplitHalf.RIGHT))
            assertEquals(61 * 41, left.width * left.height + right.width * right.height)
            assertTrue(
                left.x + left.width <= right.x || right.x + right.width <= left.x ||
                    left.y + left.height <= right.y || right.y + right.height <= left.y,
            )
        }
    }

    private fun jpegHeader(orientation: Int): ByteArray {
        val tiff = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.toByte() }
        return byteArrayOf(-1, -40, -1, -31, 0, (tiff.size + 2).toByte()) + tiff + byteArrayOf(-1, -39)
    }
}
