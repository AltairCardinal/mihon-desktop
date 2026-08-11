package tachiyomi.domain.creator.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CreatorNameNormalizerTest {

    @Test
    fun `normalizes creator names for stable lookup`() {
        CreatorNameNormalizer.normalize("  ONE / Murata Yuusuke  ") shouldBe "one murata yuusuke"
        CreatorNameNormalizer.normalize("荒木 飛呂彦") shouldBe "荒木 飛呂彦"
    }

    @Test
    fun `splits multiple creators without dropping source text`() {
        CreatorNameNormalizer.splitNames("ONE, Murata Yuusuke / Boichi; Inio Asano")
            .shouldContainExactly("ONE", "Murata Yuusuke", "Boichi", "Inio Asano")
    }

    @Test
    fun `ignores empty creator chunks`() {
        CreatorNameNormalizer.splitNames("ONE / / , Murata")
            .shouldContainExactly("ONE", "Murata")
    }

    @Test
    fun `normalizes full width roman text before identity lookup`() {
        CreatorNameNormalizer.normalize(" ＯＮＥ　Ｓｅｎｓｅｉ ") shouldBe "one sensei"
    }

    @Test
    fun `splits full width and ideographic separators`() {
        CreatorNameNormalizer.splitNames("ONE，村田雄介＆Boichi、 浦沢直樹")
            .shouldContainExactly("ONE", "村田雄介", "Boichi", "浦沢直樹")
    }
}
