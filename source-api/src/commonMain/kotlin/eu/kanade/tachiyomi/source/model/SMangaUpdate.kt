package eu.kanade.tachiyomi.source.model

/** Result of a combined manga-details and chapter-list source update. */
class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
)
