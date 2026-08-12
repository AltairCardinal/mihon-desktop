package tachiyomi.domain.creator.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Shared JVM contract for chapter-version comparison. */
class ChapterVariantNormalizerTest {

    @Test
    fun `detects volume and chapter numbers`() {
        val variant = ChapterVariantNormalizer.normalize("Vol. 3 Ch. 12: The Tower", 12.0)

        variant.volumeNumber shouldBe 3.0
        variant.chapterNumber shouldBe 12.0
        variant.type shouldBe ChapterVariantType.REGULAR
    }

    @Test
    fun `detects split chapters`() {
        val variant = ChapterVariantNormalizer.normalize("Chapter 10 Part 2", 10.0)

        variant.chapterNumber shouldBe 10.0
        variant.partNumber shouldBe 2.0
        variant.type shouldBe ChapterVariantType.SPLIT
    }

    @Test
    fun `detects extra chapters`() {
        val variant = ChapterVariantNormalizer.normalize("Omake: Beach Special", -1.0)

        variant.type shouldBe ChapterVariantType.EXTRA
        variant.confidence shouldBe 0.8
    }

    @Test
    fun `summarizes split decimal extra duplicate missing and unknown chapters without discarding names`() {
        val summary = ChapterVariantNormalizer.summarize(
            listOf(
                ChapterVariantInput("/1", "Vol. 1 Ch. 1", 1.0, "A"),
                ChapterVariantInput("/2a", "Ch. 2 Part 1", 2.0, "A"),
                ChapterVariantInput("/2b", "Ch. 2 Part 2", 2.0, "A"),
                ChapterVariantInput("/4", "4", 4.0, "A"),
                ChapterVariantInput("/4-alt", "Chapter 4", 4.0, "B"),
                ChapterVariantInput("/45", "10.5", 10.5, null),
                ChapterVariantInput("/extra", "Omake", -1.0, null),
                ChapterVariantInput("/unknown", "鏂囧瓧绔犺妭", -1.0, null),
            ),
        )

        summary.splitChapterCount shouldBe 2
        summary.decimalChapterCount shouldBe 1
        summary.extraChapterCount shouldBe 1
        summary.duplicateReleaseCount shouldBe 1
        summary.missingChapterNumbers shouldBe listOf(3L)
        summary.unknownRawNames shouldBe listOf("鏂囧瓧绔犺妭")
        summary.variants.map(ChapterVariantRecord::rawName) shouldBe listOf(
            "Vol. 1 Ch. 1",
            "Ch. 2 Part 1",
            "Ch. 2 Part 2",
            "4",
            "Chapter 4",
            "10.5",
            "Omake",
            "鏂囧瓧绔犺妭",
        )
    }
}
