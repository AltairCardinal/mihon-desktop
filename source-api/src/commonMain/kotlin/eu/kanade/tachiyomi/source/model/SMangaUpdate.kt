package eu.kanade.tachiyomi.source.model

/** Result of a combined manga-details and chapter-list source update. */
class SMangaUpdate @JvmOverloads constructor(
    val manga: SManga,
    val chapters: List<SChapter>,
    /** False when the source knows this response does not contain the complete chapter directory. */
    val chapterListComplete: Boolean = true,
)
