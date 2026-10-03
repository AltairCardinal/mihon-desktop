package tachiyomi.domain.chapter.model

/** Device-local source URL history for a uniquely recognized chapter, never a sync wire object. */
data class ChapterUrlIdentity(val canonicalUrl: String, val aliases: List<String> = emptyList())
