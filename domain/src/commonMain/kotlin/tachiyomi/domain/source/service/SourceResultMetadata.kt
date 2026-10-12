package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.creator.service.LanguageDetection
import tachiyomi.domain.creator.service.LanguageEvidence
import tachiyomi.domain.creator.service.MangaLanguageDetector
import tachiyomi.domain.manga.model.Manga

data class TranslationGroupCredit(
    val key: String,
    val displayName: String,
    val rawEvidence: String,
)

/** Ephemeral metadata for one source result; never changes source genre or chapter scanlators. */
data class SourceResultMetadata(
    val language: LanguageDetection,
    val translationGroups: List<TranslationGroupCredit>,
    val readingLanguageTags: Set<String> = setOf(language.tag).filterTo(linkedSetOf()) { it != "und" },
) {
    companion object {
        fun from(manga: SManga): SourceResultMetadata =
            from(manga.title, manga.description, manga.getGenres().orEmpty())

        fun from(manga: Manga): SourceResultMetadata = from(manga.title, manga.description, manga.genre.orEmpty())

        private fun from(title: String, description: String?, genres: List<String>): SourceResultMetadata {
            val language = MangaLanguageDetector.detect(null, null, title, description, genres)
            val titleEvidence = CatalogTitleEvidenceParser.parse(title)
            val readingLanguages = when {
                language.conflict -> emptySet()
                language.tag != "und" -> setOf(language.tag)
                language.evidence == LanguageEvidence.TEXT_DETECTED -> titleEvidence.languageTags
                else -> emptySet()
            }
            return SourceResultMetadata(language, titleEvidence.translationGroups, readingLanguages)
        }
    }
}
