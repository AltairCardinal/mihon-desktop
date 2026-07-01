package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mihon.desktop.extension.safeSourceCall
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Persists a browsed source manga so Browse and Library can share MangaDetailScreen.
 */
class SaveSourceMangaForDetails(
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    fun refreshFromSource(
        source: CatalogueSource,
        listedManga: SManga,
    ): Job {
        return refreshScope.launch {
            safeSourceCall { awaitFromSource(source, listedManga) }
        }
    }

    suspend fun awaitFromSource(
        source: CatalogueSource,
        listedManga: SManga,
    ): Manga {
        val details = mergeSourceMangaDetails(
            original = listedManga,
            details = source.getMangaDetails(listedManga),
        )
        val chapters = source.getChapterList(details)
        return await(details, source.id, chapters)
    }

    suspend fun awaitListed(
        sManga: SManga,
        sourceId: Long,
    ): Manga {
        mangaRepository.getMangaByUrlAndSourceId(sManga.url, sourceId)?.let { return it }

        val networkManga = Manga.create().copy(
            url = sManga.url,
            title = sManga.title,
            source = sourceId,
            thumbnailUrl = sManga.thumbnail_url,
            initialized = false,
        )

        return networkToLocalManga(networkManga)
    }

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
        val knownUrls = chapterRepository.getChapterByMangaId(dbManga.id)
            .mapTo(HashSet()) { it.url }
        val now = System.currentTimeMillis()

        val toAdd = sChapters.mapIndexedNotNull { index, sourceChapter ->
            if (sourceChapter.url in knownUrls) return@mapIndexedNotNull null
            Chapter.create().copy(
                mangaId = dbManga.id,
                url = sourceChapter.url,
                name = sourceChapter.name,
                dateUpload = sourceChapter.date_upload,
                chapterNumber = sourceChapter.chapter_number.toDouble(),
                scanlator = sourceChapter.scanlator?.ifBlank { null }?.trim(),
                sourceOrder = index.toLong(),
                dateFetch = now,
            )
        }

        if (toAdd.isNotEmpty()) {
            chapterRepository.addAll(toAdd)
        }

        return dbManga
    }
}

internal fun mergeSourceMangaDetails(original: SManga, details: SManga): SManga = details.also { d ->
    runCatching { d.url }.onFailure { d.url = original.url }
    runCatching { d.title }.onFailure { d.title = original.title }
    if (d.thumbnail_url.isNullOrBlank()) {
        d.thumbnail_url = original.thumbnail_url
    }
}
