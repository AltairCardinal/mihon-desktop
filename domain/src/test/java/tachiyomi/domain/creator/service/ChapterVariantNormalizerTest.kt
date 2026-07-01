package tachiyomi.domain.creator.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
}
