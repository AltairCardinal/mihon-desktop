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
}
