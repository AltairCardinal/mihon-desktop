package tachiyomi.data.manga

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.data.sync.journal.appendFavoriteOperation
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.applyChapterUpdate
import tachiyomi.domain.chapter.service.ChapterDirectoryPhase
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
    override suspend fun prepareMigration(commit: mihon.domain.migration.MigrationCommit) =
        handler.await(inTransaction = true) { prepareMigrationReceipt(commit) }

    override suspend fun pendingMigrations() = handler.await {
        chapter_directory_phasesQueries.getPending().executeAsList()
            .mapNotNull {
                Json.decodeFromString<ChapterDirectoryPhase>(
                    it,
                ).migrationReceipt
            }
    }

    override suspend fun migrationReceipt(sourceMangaId: Long) =
        handler.await { pendingMigration(sourceMangaId)?.migrationReceipt }

    override suspend fun markMigrationFilesReady(commit: mihon.domain.migration.MigrationCommit) {
        handler.await(inTransaction = true) {
            validateMigrationIdentity(commit)
            val phase = requireMigration(commit)
            val receipt = requireNotNull(phase.migrationReceipt)
            check(!receipt.committed) { "Migration is already committed" }
            writeMigrationReceipt(phase, receipt.copy(filesReady = true))
        }
    }

    override suspend fun completeMigrationFiles(commit: mihon.domain.migration.MigrationCommit) {
        handler.await(inTransaction = true) {
            validateMigrationIdentity(commit)
            val phase = requireMigration(commit)
            val receipt = requireNotNull(phase.migrationReceipt)
            check(receipt.committed && receipt.filesReady) { "Migration has not committed" }
            writeMigrationReceipt(phase, receipt.copy(filesComplete = true))
        }
    }

    override suspend fun acknowledgeMigration(commit: mihon.domain.migration.MigrationCommit) {
        handler.await(inTransaction = true) {
            validateMigrationIdentity(commit)
            val phase = requireMigration(commit)
            val receipt = requireNotNull(phase.migrationReceipt)
            check(receipt.committed && receipt.filesComplete) { "Migration effects are not complete" }
            chapter_directory_phasesQueries.removePhase(phase.mangaId, phase.id)
        }
    }

    override suspend fun abortPreparedMigration(commit: mihon.domain.migration.MigrationCommit) {
        handler.await(inTransaction = true) {
            val phase = requireMigration(commit)
            check(phase.migrationReceipt?.committed == false) { "Committed migration cannot be rolled back" }
            chapter_directory_phasesQueries.removePhase(phase.mangaId, phase.id)
        }
    }

    override suspend fun updateAtomically(update: LibraryMembershipUpdate) {
        updateMembershipsAtomically(listOf(update))
    }

    override suspend fun updateMembershipsAtomically(updates: List<LibraryMembershipUpdate>) {
        handler.await(inTransaction = true) {
            updates.forEach { update ->
                applyMembership(update)
            }
        }
    }

    private suspend fun tachiyomi.data.Database.applyMembership(update: LibraryMembershipUpdate) {
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
            memo = null,
        )
        reconcileCreatorIndex(update.mangaId)
        appendFavoriteOperation(update.mangaId, update.favorite, update.syncContext)
        if (update.updateCategories) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(update.mangaId)
            update.categoryIds.forEach { mangas_categoriesQueries.insert(update.mangaId, it) }
        }
    }

    override suspend fun commitMigration(commit: mihon.domain.migration.MigrationCommit): Manga =
        handler.await(inTransaction = true) {
            val migrationPhase = if (commit.operationId != null) requireMigration(commit) else null
            if (migrationPhase != null) {
                validateMigrationIdentity(commit)
                val receipt = requireNotNull(migrationPhase.migrationReceipt)
                if (receipt.committed) {
                    return@await mangasQueries.getMangaById(commit.targetMangaId, MangaMapper::mapManga).executeAsOne()
                }
                check(receipt.filesReady) { "Migration files have not been prepared" }
            }
            require(commit.sourceMangaId != commit.targetMangaId) { "Cannot migrate onto the same manga" }
            val source = mangasQueries.getMangaById(commit.sourceMangaId, MangaMapper::mapManga).executeAsOne()
            val target = mangasQueries.getMangaById(commit.targetMangaId, MangaMapper::mapManga).executeAsOne()
            require(source.source == commit.sourceId && source.url == commit.sourceUrl) { "Source identity changed" }
            require(target.source == commit.targetSourceId && target.url == commit.targetUrl) {
                "Target identity changed"
            }
            require(source.source != target.source || source.url != target.url) { "Cannot migrate onto the same manga" }
            if (commit.coverVersion != null) {
                require(mihon.domain.migration.models.MigrationFlag.CUSTOM_COVER in commit.flags)
                check(target.coverLastModified == commit.previousCoverVersion) {
                    "Target cover changed after preparation"
                }
            }
            val orchestrator = mihon.domain.migration.MigrationOrchestrator()
            if (mihon.domain.migration.models.MigrationFlag.CHAPTER in commit.flags) {
                fun chapters(id: Long) = chaptersQueries.getChaptersByMangaId(id, 0) {
                        chapterId,
                        _,
                        _,
                        _,
                        _,
                        read,
                        bookmark,
                        _,
                        number,
                        _,
                        fetched,
                        _,
                        _,
                        _,
                        _,
                        _,
                    ->
                    mihon.domain.migration.MigrationChapter(chapterId, number, read, bookmark, fetched)
                }.executeAsList()
                orchestrator.chapterUpdates(chapters(source.id), chapters(target.id)).forEach {
                    applyChapterUpdate(
                        tachiyomi.domain.chapter.model.ChapterUpdate(
                            id = it.id,
                            read = it.read,
                            bookmark = it.bookmark,
                            dateFetch = it.dateFetch,
                        ),
                    )
                }
            }
            val copyCategories = mihon.domain.migration.models.MigrationFlag.CATEGORY in commit.flags
            val categories = if (copyCategories) {
                categoriesQueries.getCategoriesByMangaId(source.id) { id, _, _, _ -> id }.executeAsList()
            } else {
                emptyList()
            }
            val plan = orchestrator.libraryPlan(
                mihon.domain.migration.MigrationMangaMetadata(
                    source.id,
                    categories,
                    source.chapterFlags,
                    source.viewerFlags,
                    source.dateAdded,
                    source.notes,
                ),
                target.id,
                commit.flags,
                commit.replace,
                commit.now,
            )
            applyMembership(
                LibraryMembershipUpdate(
                    target.id,
                    true,
                    plan.targetDateAdded,
                    plan.targetCategoryIds,
                    copyCategories,
                    plan.targetChapterFlags,
                    plan.targetViewerFlags,
                    plan.targetNotes,
                ),
            )
            if (plan.removeCurrentFromLibrary) {
                applyMembership(LibraryMembershipUpdate(source.id, false, 0, emptyList()))
            }
            commit.coverVersion?.let {
                applyMangaUpdateFields(MangaUpdate(target.id, coverLastModified = it))
            }
            if (migrationPhase != null) {
                writeMigrationReceipt(
                    migrationPhase,
                    requireNotNull(migrationPhase.migrationReceipt).copy(
                        committed = true,
                        targetDateAdded = plan.targetDateAdded,
                    ),
                )
            }
            mangasQueries.getMangaById(target.id, MangaMapper::mapManga).executeAsOne()
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
                    memo = tachiyomi.data.MemoColumnAdapter.encode(it.memo),
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
                applyMangaUpdateFields(value)
                if (value.affectsCreatorIndex()) {
                    reconcileCreatorIndex(value.id)
                }
                value.favorite?.let { appendFavoriteOperation(value.id, it, value.syncContext) }
            }
        }
    }

    private suspend fun tachiyomi.data.Database.reconcileCreatorIndex(mangaId: Long) {
        reconcileMangaCreatorIndex(mangaId, creatorIndexWriter, extractCreators)
    }
}
