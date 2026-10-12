package tachiyomi.domain.creator.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.model.CreatorRole

class TitleCreditParserTest {
    @Test
    fun `dated event prefix preserves following creator and circle credits`() {
        val title = "(2021-03 Akihabara Chou Doujinsai) [Popochichi (Yahiro Pochi)] Example [Chinese]"
        TitleCreditParser.parse(title, allowStandaloneCreator = true).map { it.displayName } shouldBe
            listOf("Yahiro Pochi", "Popochichi")
        TitleCreditParser.parse(title).map { it.evidence.single().field } shouldBe
            listOf(CreatorMetadataField.TITLE_CREATOR, CreatorMetadataField.TITLE_CIRCLE)
    }

    @Test
    fun `dated event parsing stays bounded and preserves creator exclusions`() {
        TitleCreditParser.parse("（2021-03 秋葉原同人祭）［星舟社（青岚）］ 示例").map { it.displayName } shouldBe
            listOf("青岚", "星舟社")
        listOf(
            "(2021-00 Event) [Circle (Artist)] Work",
            "(2021-13 Event) [Circle (Artist)] Work",
            "(2021-03) [Circle (Artist)] Work",
            "(2021-03 Event [Nested]) [Circle (Artist)] Work",
            "(2021-03 ${"E".repeat(97)}) [Circle (Artist)] Work",
            "(2021-03 Event） [Circle (Artist)] Work",
            "(2021-03 Event) [Chinese] Work",
            "(2021-03 Event) [Translation] Work",
            "(2021-03 Event) [Artist / Other] Work",
        ).forEach { title ->
            TitleCreditParser.parse(title, allowStandaloneCreator = true) shouldBe emptyList()
        }
    }

    @Test
    fun `explicit personal credits do not manufacture a circle`() {
        listOf("[(青岚)] 作品", "（C105）［（青岚）］ 作品", "[作者: 青岚] 作品", "[artist：青岚] 作品").forEach { title ->
            val mention = TitleCreditParser.parse(title).single()
            mention.displayName shouldBe "青岚"
            mention.role shouldBe CreatorRole.UNKNOWN
            mention.evidence.single().field shouldBe CreatorMetadataField.TITLE_CREATOR
            mention.evidence.single().rawField shouldBe title
        }
    }

    @Test
    fun `bare name is a candidate only under explicitly requested standalone parsing`() {
        val title = "(C105) [Kuromotokun] Work"
        TitleCreditParser.parse(title) shouldBe emptyList()
        val mention = TitleCreditParser.parse(title, allowStandaloneCreator = true).single()
        mention.displayName shouldBe "Kuromotokun"
        mention.evidence.single().field shouldBe CreatorMetadataField.TITLE_CREATOR
        TitleCreditParser.parse("［Ｋｕｒｏ］ 作品", allowStandaloneCreator = true).single().displayName shouldBe "Ｋｕｒｏ"
    }

    @Test
    fun `language event translation and uploader labels are never standalone creator candidates`() {
        listOf(
            "[Chinese] Work", "[English] Work", "[日本語] Work", "[简体中文] Work", "[全彩] Work",
            "[汉化组] Work", "[Translation] Work", "[翻訳] Work", "[C105] Work", "[COMIC1] Work",
            "[Uploader] Work", "[上传者: Alice] Work", "[转载] Work", "[Artist / Other] Work",
            "[Artist]", "Work [Artist]", "[Artist] [English]", "[Artist）] Work",
        ).forEach { title ->
            TitleCreditParser.parse(title, allowStandaloneCreator = true) shouldBe emptyList()
        }
    }
}
