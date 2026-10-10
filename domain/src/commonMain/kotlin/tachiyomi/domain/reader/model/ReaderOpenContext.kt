package tachiyomi.domain.reader.model

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Identity of the target already chosen by the originating user action. */
data class ReaderChapterIdentity(
    val mangaId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterId: Long,
    val chapterUrl: String,
)

/** One database version supplies the selected chapter, page and causal baseline. */
data class ReaderOpenContext(
    val manga: Manga,
    val chapter: Chapter,
    val pageIndex: Int,
    val snapshot: ReadingSyncSnapshot,
    val resumedWithinChapter: Boolean,
)
