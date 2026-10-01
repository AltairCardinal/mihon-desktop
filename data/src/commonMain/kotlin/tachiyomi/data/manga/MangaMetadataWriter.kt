package tachiyomi.data.manga

import tachiyomi.data.Database
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.MangaUpdate

internal fun Database.applyMangaUpdateFields(value: MangaUpdate) {
    mangasQueries.update(
        source = value.source,
        url = value.url,
        artist = value.artist,
        updateArtist = value.updateArtist,
        author = value.author,
        updateAuthor = value.updateAuthor,
        description = value.description,
        genre = value.genre?.let(StringListColumnAdapter::encode),
        title = value.title,
        status = value.status,
        thumbnailUrl = value.thumbnailUrl,
        favorite = value.favorite,
        lastUpdate = value.lastUpdate,
        nextUpdate = value.nextUpdate,
        calculateInterval = value.fetchInterval?.toLong(),
        initialized = value.initialized,
        viewer = value.viewerFlags,
        chapterFlags = value.chapterFlags,
        coverLastModified = value.coverLastModified,
        dateAdded = value.dateAdded,
        mangaId = value.id,
        updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
        version = value.version,
        isSyncing = 0,
        notes = value.notes,
        memo = value.memo?.let(tachiyomi.data.MemoColumnAdapter::encode),
    )
}

internal suspend fun Database.reconcileMangaCreatorIndex(
    mangaId: Long,
    writer: CreatorLibraryIndexWriter,
    extractCreators: ExtractCreatorsFromManga = ExtractCreatorsFromManga(),
) {
    val manga = mangasQueries.getMangaById(mangaId, MangaMapper::mapManga).executeAsOne()
    if (manga.favorite) {
        writer.indexLibraryMangaBatch(listOf(CreatorLibraryIndexEntry(manga, extractCreators.await(manga))))
    } else {
        writer.removeLibraryMangaIndex(mangaId)
    }
}

internal fun MangaUpdate.affectsCreatorIndex(): Boolean =
    favorite != null || source != null || url != null || title != null || updateAuthor || updateArtist ||
        thumbnailUrl != null
