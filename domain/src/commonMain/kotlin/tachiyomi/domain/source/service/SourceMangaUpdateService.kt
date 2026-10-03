package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class SourceMangaUpdateService {
    suspend fun awaitSharedCatalog(
        source: Source,
        manga: Manga,
        chapters: List<Chapter>,
        fetchDetails: Boolean,
    ): SMangaUpdate =
        SharedReaderCatalogRequests.await(source, manga, chapters, fetchDetails)

    suspend fun await(
        source: Source,
        manga: Manga,
        chapters: List<Chapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val sourceManga = manga.toSourceManga()
        val sourceChapters = chapters.sortedBy { it.sourceOrder }.map { it.toSourceChapter() }
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(sourceManga, sourceChapters)
        return source.getMangaUpdate(sourceManga, sourceChapters, fetchDetails, fetchChapters)
    }
}

fun Manga.toSourceManga(): SManga = SManga.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre?.joinToString(", ")
    it.status = status.toInt()
    it.thumbnail_url = thumbnailUrl
    it.initialized = initialized
    it.update_strategy = updateStrategy
    it.memo = memo
}

fun Chapter.toSourceChapter(): SChapter = SChapter.create().also {
    it.url = url
    it.name = name
    it.date_upload = dateUpload
    it.chapter_number = chapterNumber.toFloat()
    it.scanlator = scanlator
    it.memo = memo
}
