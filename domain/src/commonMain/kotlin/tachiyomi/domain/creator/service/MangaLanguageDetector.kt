package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag
import tachiyomi.domain.source.service.CatalogLanguageLabels
import tachiyomi.domain.source.service.CatalogTitleEvidenceParser

data class LanguageDetection(
    val tag: String,
    val confidence: Double,
    val evidence: LanguageEvidence,
    val conflict: Boolean = false,
    val rawEvidence: List<String> = emptyList(),
)

enum class LanguageEvidence {
    EXPLICIT_METADATA,
    GENRE_TAG,
    SOURCE_LANGUAGE,
    TEXT_DETECTED,
    UNKNOWN,
}

object MangaLanguageDetector {

    // Lexical phrases, not Han-character counts: Japanese shares much of the Han script.
    // Alternative scripts of the same phrase count as one cue, and repeated cues count once.
    private val chineseTitleCues = listOf(
        "小姐", "我们", "我們", "他们", "他們", "她们", "她們", "这个", "這個", "我的", "你的", "没想到", "沒想到",
    )
    private val chineseDescriptionCues = listOf(
        listOf("这是", "這是"),
        listOf("我们", "我們"),
        listOf("他们", "他們"),
        listOf("她们", "她們"),
        listOf("没有", "沒有"),
        listOf("所以"),
        listOf("就是"),
        listOf("一个", "一個"),
        listOf("觉得", "覺得"),
        listOf("好好看"),
    )

    private val languageAliases = mapOf(
        "english" to "en",
        "en" to "en",
        "japanese" to "ja",
        "japan" to "ja",
        "ja" to "ja",
        "korean" to "ko",
        "korea" to "ko",
        "ko" to "ko",
        "chinese" to "zh",
        "china" to "zh",
        "zh" to "zh",
        "french" to "fr",
        "fr" to "fr",
        "spanish" to "es",
        "es" to "es",
        "portuguese" to "pt",
        "pt" to "pt",
    )

    fun detect(
        sourceLang: String?,
        explicitLanguage: String?,
        title: String,
        description: String?,
        genres: List<String>,
    ): LanguageDetection {
        normalizeLanguage(explicitLanguage)?.let {
            return LanguageDetection(
                it,
                1.0,
                LanguageEvidence.EXPLICIT_METADATA,
                rawEvidence = listOfNotNull(explicitLanguage),
            )
        }

        val genreLanguages = genres.takeIf { it.size <= 128 }.orEmpty().mapNotNull { genre ->
            CatalogLanguageLabels.languageTag(genre)?.let { genre to it }
        }
        if (genreLanguages.isNotEmpty()) {
            val tag = CatalogLanguageLabels.compatibleTag(genreLanguages.map { it.second })
            return LanguageDetection(
                tag = tag ?: "und",
                confidence = if (tag == null) 0.0 else 0.9,
                evidence = LanguageEvidence.GENRE_TAG,
                conflict = tag == null,
                rawEvidence = genreLanguages.map { it.first },
            )
        }

        val titleEvidence = CatalogTitleEvidenceParser.parse(title)
        if (titleEvidence.rawLanguageEvidence.isNotEmpty()) {
            return LanguageDetection(
                tag = titleEvidence.languageTags.singleOrNull() ?: "und",
                confidence = if (titleEvidence.conflict) 0.0 else 0.8,
                evidence = LanguageEvidence.TEXT_DETECTED,
                conflict = titleEvidence.conflict,
                rawEvidence = titleEvidence.rawLanguageEvidence,
            )
        }
        if (!titleEvidence.complete) return LanguageDetection("und", 0.0, LanguageEvidence.UNKNOWN)

        val readingTitle = titleEvidence.textOutsideMarkers
        val boundedDescription = description.orEmpty().take(4096)
        if (chineseTitleCues.any(readingTitle::contains) &&
            chineseDescriptionCues.count { alternatives -> alternatives.any(boundedDescription::contains) } >= 2
        ) {
            return LanguageDetection(
                "zh",
                0.7,
                LanguageEvidence.TEXT_DETECTED,
                rawEvidence = listOf("title: $readingTitle", "description: $boundedDescription"),
            )
        }

        detectFromText("$readingTitle $boundedDescription")?.let {
            return LanguageDetection(it, 0.7, LanguageEvidence.TEXT_DETECTED)
        }

        normalizeLanguage(sourceLang)?.let {
            return LanguageDetection(it, 0.65, LanguageEvidence.SOURCE_LANGUAGE)
        }

        return LanguageDetection("und", 0.0, LanguageEvidence.UNKNOWN)
    }

    private fun normalizeLanguage(value: String?): String? {
        value?.let { CatalogLanguageLabels.languageTag(it) }?.let { return it }
        val normalized = value?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return null
        val alias = languageAliases[normalized] ?: normalized
        return CreatorArchiveLanguageTag.normalize(alias).takeUnless { it == "und" }
    }

    private fun detectFromText(text: String): String? {
        return when {
            text.any { it in '\u3040'..'\u30ff' } -> "ja"
            text.any { it in '\uac00'..'\ud7af' } -> "ko"
            else -> null
        }
    }
}
