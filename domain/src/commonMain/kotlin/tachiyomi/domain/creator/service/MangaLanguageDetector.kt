package tachiyomi.domain.creator.service

data class LanguageDetection(
    val tag: String,
    val confidence: Double,
    val evidence: LanguageEvidence,
)

enum class LanguageEvidence {
    EXPLICIT_METADATA,
    GENRE_TAG,
    SOURCE_LANGUAGE,
    TEXT_DETECTED,
    UNKNOWN,
}

object MangaLanguageDetector {

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
            return LanguageDetection(it, 1.0, LanguageEvidence.EXPLICIT_METADATA)
        }

        genres.firstNotNullOfOrNull { normalizeLanguage(it) }?.let {
            return LanguageDetection(it, 0.9, LanguageEvidence.GENRE_TAG)
        }

        detectFromText("$title ${description.orEmpty()}")?.let {
            return LanguageDetection(it, 0.7, LanguageEvidence.TEXT_DETECTED)
        }

        normalizeLanguage(sourceLang)?.let {
            return LanguageDetection(it, 0.65, LanguageEvidence.SOURCE_LANGUAGE)
        }

        return LanguageDetection("unknown", 0.0, LanguageEvidence.UNKNOWN)
    }

    private fun normalizeLanguage(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return languageAliases[normalized] ?: normalized.takeIf { it.length == 2 }
    }

    private fun detectFromText(text: String): String? {
        return when {
            text.any { it in '\u3040'..'\u30ff' } -> "ja"
            text.any { it in '\uac00'..'\ud7af' } -> "ko"
            text.any { it in '\u4e00'..'\u9fff' } -> "zh"
            else -> null
        }
    }
}
