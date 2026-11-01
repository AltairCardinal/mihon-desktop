package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class ReaderPairingAdoptionCostTest {
    private class Reads<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T {
            reads++
            return values[index]
        }
    }

    @Test
    fun `score stops reading acquired samples once nonnegative distance rejects`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val black = Reads(List(50) { 0xff000000.toInt() })
            val white = Reads(List(50) { -1 })
            assertNull(
                SharedSpreadMatching.score(
                    ReaderSpreadEdgeSamples(black, black),
                    ReaderSpreadEdgeSamples(white, white),
                    direction,
                ),
            )
            assertEquals(2, black.reads)
            assertEquals(2, white.reads)
        }
    }

    @Test
    fun `real score stays exactly equal to original formula across alpha direction and guards`() {
        val random = Random(739)
        val edges = buildList {
            for (color in listOf(0, -1, 0xff101010.toInt(), 0xff808080.toInt())) add(List(50) { color })
            val art = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
            add(art)
            for (shift in listOf(5, 9, 10, 11)) add(art.map { it + shift * 0x010101 })
            repeat(100) { add(List(50) { random.nextInt() }) }
            repeat(20) { add(List(50) { random.nextInt(256).let { a -> (a shl 24) or 0x4080a0 } }) }
        }
        for (index in edges.indices) {
            for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
                val first = ReaderSpreadEdgeSamples(edges[index], edges[(index + 1) % edges.size])
                val second = ReaderSpreadEdgeSamples(edges[(index + 2) % edges.size], edges[index])
                assertEquals(
                    originalScore(first, second, direction),
                    SharedSpreadMatching.score(first, second, direction),
                )
            }
        }
    }

    // Independent pre-adoption oracle; production always goes through SharedSpreadMatching.score.
    private fun originalScore(
        first: ReaderSpreadEdgeSamples,
        second: ReaderSpreadEdgeSamples,
        direction: ReaderDirection,
    ): Double? {
        val a = (if (direction == ReaderDirection.RTL) first.left else first.right)
            .map(SharedSpreadMatching::normalizeSample)
        val b = (if (direction == ReaderDirection.RTL) second.right else second.left)
            .map(SharedSpreadMatching::normalizeSample)
        fun red(c: Int) = (c ushr 16) and 255
        fun green(c: Int) = (c ushr 8) and 255
        fun blue(c: Int) = c and 255
        val distance = a.zip(b).sumOf { (x, y) ->
            abs(red(x) - red(y)) + abs(green(x) - green(y)) + abs(blue(x) - blue(y))
        }.toDouble() / 50
        if (distance >= 30) return null
        fun bright(e: List<Int>) = e.count { minOf(red(it), green(it), blue(it)) > 220 } >= 40
        fun dark(e: List<Int>) = e.count { maxOf(red(it), green(it), blue(it)) < 50 } >= 40
        fun variance(e: List<Int>): Double {
            val l = e.map { 0.299 * red(it) + 0.587 * green(it) + 0.114 * blue(it) }
            val mean = l.average()
            return l.sumOf { it * it } / 50 - mean * mean
        }
        if ((bright(a) && bright(b)) || (dark(a) && dark(b))) return null
        if (variance(a) < 100 && variance(b) < 100) return null
        return distance
    }
}
