package tachiyomi.domain.creator.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.model.CreatorRole

class TitleCreditParserTest {
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
