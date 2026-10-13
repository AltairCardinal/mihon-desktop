package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderOriginalPageSamplingContractTest {
    @Test
    fun `original plan combines exactly the unchanged side and spine pixel coordinates`() {
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(400, 600))
        assertEquals(194, plan.left.size)
        assertEquals(194, plan.right.size)
        assertEquals(1 to 24, plan.left[0])
        assertEquals(1 to 564, plan.left[47])
        assertEquals(6 to 24, plan.left[48])
        assertEquals(14 to 24, plan.left[96])
        assertEquals(14 to 564, plan.left[143])
        assertEquals(2 to 0, plan.left[144])
        assertEquals(2 to 588, plan.left[193])
        assertEquals(398 to 24, plan.right[0])
        assertEquals(385 to 564, plan.right[143])
        assertEquals(397 to 588, plan.right[193])
        val edges = requireNotNull(SharedSpreadMatching.plan(400, 600))
        assertEquals(edges.left, plan.left.drop(144))
        assertEquals(edges.right, plan.right.drop(144))
        assertEquals(ReaderOriginalPageSampling.ALGORITHM_VERSION, plan.algorithmVersion)
    }

    @Test
    fun `quarter turns mirrors and user rotation project side regions into raw coordinates once`() {
        val expected = mapOf(
            ReaderImageOrientation.CLOCKWISE to (24 to 398),
            ReaderImageOrientation.TRANSPOSE to (24 to 1),
            ReaderImageOrientation.COUNTER_CLOCKWISE to (575 to 1),
            ReaderImageOrientation.TRANSVERSE to (575 to 398),
        )
        expected.forEach { (orientation, first) ->
            val plan = requireNotNull(ReaderOriginalPageSampling.plan(600, 400, orientation))
            assertEquals(first, plan.left.first(), orientation.name)
            assertTrue(plan.left.all { (x, y) -> x in 0 until 600 && y in 0 until 400 })
        }
        assertEquals(
            ReaderOriginalPageSampling.plan(600, 400, ReaderImageOrientation.CLOCKWISE)?.left,
            ReaderOriginalPageSampling.plan(600, 400, rotation = PageRotation.CLOCKWISE_90)?.left,
        )
        assertEquals(
            ReaderOriginalPageSampling.plan(600, 400, ReaderImageOrientation.TRANSVERSE)?.left,
            ReaderOriginalPageSampling.plan(
                600,
                400,
                ReaderImageOrientation.MIRROR_HORIZONTAL,
                PageRotation.CLOCKWISE_90,
            )?.left,
        )
    }

    @Test
    fun `side alpha and white thresholds stay distinct from edge white composition`() {
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(400, 600))
        for ((white, expected) in listOf(
            0xefffffff.toInt() to ReaderPageSideHint.UNKNOWN,
            0xf0f2f2f2.toInt() to ReaderPageSideHint.RIGHT,
            0xfff1f1f1.toInt() to ReaderPageSideHint.UNKNOWN,
        )) {
            val samples = plan.samples(List(194) { white }, List(194) { 0xff202020.toInt() })
            assertEquals(expected, samples.sideHint)
            assertEquals(
                expected,
                ReaderPageSideHints.classify(400, 600) { x, _ ->
                    if (x < 200) white else 0xff202020.toInt()
                },
            )
        }
        assertEquals(-1, SharedSpreadMatching.normalizeSample(0xefffffff.toInt()))
    }

    @Test
    fun `narrow tall and landscape originals retain explicit known unknown side results`() {
        val narrow = requireNotNull(ReaderOriginalPageSampling.plan(63, 20000))
        assertEquals(50, narrow.left.size)
        val result = narrow.samples(List(50) { -1 }, List(50) { 0xff202020.toInt() })
        assertEquals(ReaderPageSideHint.UNKNOWN, result.sideHint)
        assertEquals(19600, narrow.left.last().second)
        val wide = requireNotNull(ReaderOriginalPageSampling.plan(600, 400))
        assertTrue(wide.left.isEmpty() && wide.right.isEmpty())
        assertEquals(ReaderPageSideHint.UNKNOWN, wide.samples(emptyList(), emptyList()).sideHint)
        assertNull(wide.samples(emptyList(), emptyList()).edges)
        assertNull(ReaderOriginalPageSampling.plan(0, 600))
    }
}
