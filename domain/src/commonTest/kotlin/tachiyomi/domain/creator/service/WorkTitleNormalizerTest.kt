package tachiyomi.domain.creator.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WorkTitleNormalizerTest {

    @Test
    fun `normalizes traditional title to the same merge key as simplified title`() {
        WorkTitleNormalizer.normalizeForMerge("詭譎屋") shouldBe "诡谲屋"
        WorkTitleNormalizer.normalizeForMerge("诡谲屋") shouldBe "诡谲屋"
    }

    @Test
    fun `only reports a script variant when the source titles differ`() {
        WorkTitleNormalizer.isSimplifiedTraditionalVariant("詭譎屋", "诡谲屋") shouldBe true
        WorkTitleNormalizer.isSimplifiedTraditionalVariant("诡谲屋", "诡谲屋") shouldBe false
        WorkTitleNormalizer.isSimplifiedTraditionalVariant("Monster", "怪物") shouldBe false
    }

    @Test
    fun `does not treat punctuation or case changes as script variants`() {
        WorkTitleNormalizer.isSimplifiedTraditionalVariant("詭譎屋:外傳", "诡谲屋 外传") shouldBe false
        WorkTitleNormalizer.isSimplifiedTraditionalVariant("詭譎屋:外傳", "诡谲屋:外传") shouldBe true
        WorkTitleNormalizer.isSimplifiedTraditionalVariant("Series", "series") shouldBe false
    }

    @Test
    fun `uses phrase mappings where a character has context dependent forms`() {
        WorkTitleNormalizer.normalizeForMerge("乾坤") shouldBe "乾坤"
        WorkTitleNormalizer.normalizeForMerge("一目瞭然") shouldBe "一目了然"
        WorkTitleNormalizer.normalizeForMerge("情有獨鍾") shouldBe "情有独钟"
    }
}
