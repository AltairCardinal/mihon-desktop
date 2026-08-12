package tachiyomi.domain.creator.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaLanguageDetectorTest {

    @Test
    fun `uses explicit structured metadata as highest confidence language`() {
        val result = MangaLanguageDetector.detect(
            sourceLang = "en",
            explicitLanguage = "ja",
            title = "One Piece",
            description = null,
            genres = emptyList(),
        )

        result.tag shouldBe "ja"
        result.confidence shouldBe 1.0
        result.evidence shouldBe LanguageEvidence.EXPLICIT_METADATA
    }

    @Test
    fun `uses genre language tag before source language`() {
        val result = MangaLanguageDetector.detect(
            sourceLang = "en",
            explicitLanguage = null,
            title = "Solo Leveling",
            description = "Action",
            genres = listOf("Korean", "Action"),
        )

        result.tag shouldBe "ko"
        result.evidence shouldBe LanguageEvidence.GENRE_TAG
    }

    @Test
    fun `falls back to source language with medium confidence`() {
        val result = MangaLanguageDetector.detect(
            sourceLang = "fr",
            explicitLanguage = null,
            title = "Aventure",
            description = null,
            genres = emptyList(),
        )

        result.tag shouldBe "fr"
        result.confidence shouldBe 0.65
        result.evidence shouldBe LanguageEvidence.SOURCE_LANGUAGE
    }

    @Test
    fun `non-language genre tags and invalid BCP47 values stay unknown`() {
        val result = MangaLanguageDetector.detect(
            sourceLang = "unknown",
            explicitLanguage = "BL",
            title = "Example",
            description = null,
            genres = listOf("GL", "SF"),
        )

        result.tag shouldBe "und"
        result.evidence shouldBe LanguageEvidence.UNKNOWN
    }

    @Test
    fun `pure Han text stays unknown instead of pretending to distinguish Chinese from Japanese`() {
        MangaLanguageDetector.detect(null, null, "進撃的巨人", null, emptyList()).tag shouldBe "und"
        MangaLanguageDetector.detect(null, null, "进击的巨人", null, emptyList()).tag shouldBe "und"
    }

    @Test
    fun `Kana and Hangul provide bounded text evidence`() {
        MangaLanguageDetector.detect(null, null, "進撃の巨人", null, emptyList()).tag shouldBe "ja"
        MangaLanguageDetector.detect(null, null, "신의 탑", null, emptyList()).tag shouldBe "ko"
    }

    @Test
    fun `normalizes BCP47 aliases without treating content categories as languages`() {
        MangaLanguageDetector.detect(null, "PT_br", "Example", null, emptyList()).tag shouldBe "pt-br"
    }
}
