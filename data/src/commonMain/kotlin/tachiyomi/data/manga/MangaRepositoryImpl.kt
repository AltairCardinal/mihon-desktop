package tachiyomi.data.manga

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import mihon.data.sync.journal.appendFavoriteOperation
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.LocalDate
import java.time.ZoneId

class MangaRepositoryImpl(
    private val handler: DatabaseHandler,
    private val creatorIndexWriter: CreatorLibraryIndexWriter,
    private val extractCreators: ExtractCreatorsFromManga = ExtractCreatorsFromManga(),
) : MangaRepository, CreatorLibraryMangaSource {
    override suspend fun updateAtomically(update: LibraryMembershipUpdate) {
        updateMembershipsAtomically(listOf(update))
    }

    override suspend fun updateMembershipsAtomically(updates: List<LibraryMembershipUpdate>) {
        handler.await(inTransaction = true) {
            updates.forEach { update ->
                mangasQueries.update(
                    source = null,
                    url = null,
                    artist = null,
                    updateArtist = false,
                    author = null,
                    updateAuthor = false,
                    description = null,
                    genre = null, title = null, status = null, thumbnailUrl = null,
                    favorite = update.favorite, lastUpdate = null, nextUpdate = null,
                    calculateInterval = null, initialized = null, viewer = update.viewerFlags,
                    chapterFlags = update.chapterFlags,
                    coverLastModified = null, dateAdded = update.dateAdded, mangaId = update.mangaId,
                    updateStrategy = null, version = null, isSyncing = 0, notes = update.notes,
                )
                reconcileCreatorIndex(update.mangaId)
                appendFavoriteOperation(update.mangaId, update.favorite, update.syncContext)
                if (update.updateCategories) {
                    mangas_categoriesQueries.deleteMangaCategoryByMangaId(update.mangaId)
                    update.categoryIds.forEach { mangas_categoriesQueries.insert(update.mangaId, it) }
                }
            }
        }
    }

    override suspend fun getMangaById(id: Long): Manga {
        return handler.awaitOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return handler.subscribeToOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
                url,
                sourceId,
                MangaMapper::mapManga,
            )
        }
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return handler.subscribeToOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
                url,
                sourceId,
                MangaMapper::mapManga,
            )
        }
    }

    override suspend fun getFavorites(): List<Manga> {
        return handler.awaitList { mangasQueries.getFavorites(MangaMapper::mapManga) }
    }

    override suspend fun countLibraryMangaForCreatorIndex(): Long {
        return handler.awaitOne { mangasQueries.countLibraryMangaForCreatorIndex() }
    }

    override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> {
        require(limit > 0L) { "Creator index page limit must be positive" }
        return handler.awaitList {
            mangasQueries.getLibraryMangaForCreatorIndex(afterId, limit, MangaMapper::mapManga)
        }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return handler.awaitList { mangasQueries.getReadMangaNotInLibrary(MangaMapper::mapManga) }
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return handler.awaitList { libraryViewQueries.library(MangaMapper::mapLibraryManga) }
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return handler.subscribeToList { libraryViewQueries.library(MangaMapper::mapLibraryManga) }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return handler.subscribeToList { mangasQueries.getFavoriteBySourceId(sourceId, MangaMapper::mapManga) }
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return handler.awaitList {
            mangasQueries.getDuplicateLibraryManga(id, title, MangaMapper::mapMangaWithChapterCount)
        }
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return handler.subscribeToList {
            mangasQueries.getUpcomingManga(epochMillis, statuses, MangaMapper::mapManga)
        }
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            handler.await { mangasQueries.resetViewerFlags() }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun resetViewerFlagsForNonFavorites(): Boolean {
        return try {
            handler.await { mangasQueries.resetViewerFlagsForNonFavorites() }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.map { categoryId ->
                mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        return handler.await(inTransaction = true) {
            manga.map {
                mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
                    title = it.title,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                    mapper = MangaMapper::mapManga,
                )
                    .executeAsOne()
                    .also { stored ->
                        if (stored.favorite) {
                            creatorIndexWriter.indexLibraryMangaBatch(
                                listOf(CreatorLibraryIndexEntry(stored, extractCreators.await(stored))),
                            )
                        }
                    }
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        handler.await(inTransaction = true) {
            mangaUpdates.forEach { value ->
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
                )
                if (value.affectsCreatorIndex()) {
                    reconcileCreatorIndex(value.id)
                }
                value.favorite?.let { appendFavoriteOperation(value.id, it, value.syncContext) }
            }
        }
    }

    private suspend fun tachiyomi.data.Database.reconcileCreatorIndex(mangaId: Long) {
        val manga = mangasQueries.getMangaById(mangaId, MangaMapper::mapManga).executeAsOne()
        if (manga.favorite) {
            creatorIndexWriter.indexLibraryMangaBatch(
                listOf(CreatorLibraryIndexEntry(manga, extractCreators.await(manga))),
            )
        } else {
            creatorIndexWriter.removeLibraryMangaIndex(mangaId)
        }
    }

    private fun MangaUpdate.affectsCreatorIndex(): Boolean {
        return favorite != null ||
            source != null ||
            url != null ||
            title != null ||
            updateAuthor ||
            updateArtist ||
            thumbnailUrl != null
    }
}
