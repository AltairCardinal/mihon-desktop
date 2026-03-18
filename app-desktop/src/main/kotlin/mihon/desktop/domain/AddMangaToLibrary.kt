package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Adds a source manga to the user's library.
 *
 * - Converts the network [SManga] to a persisted [Manga] via [NetworkToLocalManga].
 * - Marks it as a favourite if not already.
 * - Syncs the chapter list, inserting only new URLs (idempotent).
 *
 * Returns the persisted [Manga] with `favourite = true`.
 */
class AddMangaToLibrary(
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(
        sManga: SManga,
        sourceId: Long,
        sChapters: List<SChapter>,
    ): Manga {
        val networkManga = Manga.create().copy(
            url = sManga.url,
            title = sManga.title,
            source = sourceId,
            thumbnailUrl = sManga.thumbnail_url,
            author = sManga.author,
            artist = sManga.artist,
            description = sManga.description,
            genre = sManga.genre?.split(", ")?.takeIf { it.isNotEmpty() },
            status = sManga.status.toLong(),
            initialized = true,
        )

        val dbManga = networkToLocalManga(networkManga)

        if (!dbManga.favorite) {
            mangaRepository.update(
                MangaUpdate(
                    id = dbManga.id,
                    favorite = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
        }

        val knownUrls = chapterRepository.getChapterByMangaId(dbManga.id)
            .mapTo(HashSet()) { it.url }

        val toAdd = sChapters.mapIndexedNotNull { index, sc ->
            if (sc.url in knownUrls) return@mapIndexedNotNull null
            Chapter.create().copy(
                mangaId = dbManga.id,
                url = sc.url,
                name = sc.name,
                dateUpload = sc.date_upload,
                chapterNumber = sc.chapter_number.toDouble(),
                scanlator = sc.scanlator?.ifBlank { null }?.trim(),
                sourceOrder = index.toLong(),
                dateFetch = System.currentTimeMillis(),
            )
        }

        if (toAdd.isNotEmpty()) chapterRepository.addAll(toAdd)

        return dbManga.copy(favorite = true)
    }
}
