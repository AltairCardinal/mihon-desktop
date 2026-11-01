package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Physical page content for the pairing contract tests.
 *
 * The pages are built from real pixels and scored by the real sampler, so the
 * edge scores and the side hints are production values, not test doubles.
 */
internal enum class ReaderPairingTestPixels {
    GRAY,
    TEXTURE,
    SHIFTED,
    INVERTED,
}

/** A portrait page of vertical seven pixel stripes, optionally shifted or inverted. */
internal fun readerPairingTestOriginal(
    kind: ReaderPairingTestPixels,
    width: Int = 256,
    height: Int = 384,
): Pair<ReaderPageFact, ReaderOriginalPageSamples> {
    fun pixel(y: Int): Int {
        if (kind == ReaderPairingTestPixels.GRAY) return 0xff8c8c8c.toInt()
        val light = (y / 7) % 2 == if (kind == ReaderPairingTestPixels.INVERTED) 1 else 0
        val value = (if (light) 180 else 70) +
            if (kind == ReaderPairingTestPixels.SHIFTED) 5 else 0
        return 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    }
    val plan = requireNotNull(ReaderOriginalPageSampling.plan(width, height))
    val samples = plan.samples(
        plan.left.map { (_, y) -> pixel(y) },
        plan.right.map { (_, y) -> pixel(y) },
    )
    assertEquals(ReaderPageSideHint.UNKNOWN, samples.sideHint, "$kind must not carry a side hint")
    return ReaderPageFact.dimensions(width, height) to samples
}
