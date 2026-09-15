package mihon.data.sync.projection

import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import tachiyomi.domain.manga.model.Manga
import java.util.Date

enum class SyncProjectionUnavailableReason { SOURCE, DESCRIPTION, IDENTITY }

class SyncProjectionUnavailable(val reason: SyncProjectionUnavailableReason) :
    IllegalStateException("sync projection unavailable: " + reason.name.lowercase())

/**
 * Applies an accepted remote projection using the business database and its existing author index.
 * Call [prepare] outside the receiving transaction, sharing the repository's bootstrap instance.
 * Every mutation participates in the caller's transaction and propagates failures for rollback.
 */
class SyncRemoteProjectionWriter(
    private val handler: DatabaseHandler,
    private val creatorIndexWriter: CreatorLibraryIndexWriter,
    private val creatorRepository: CreatorRepository,
    private val bootstrap: CreatorArchiveBootstrap,
    private val sourceAvailable: (Long) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val extractCreators = ExtractCreatorsFromManga()

    suspend fun prepare() = bootstrap.awaitReady()

    suspend fun localMembership(key: SyncObjectKey): Boolean? = handler.await(inTransaction = true) {
        validateIdentity(key)
        when (key.type) {
            SyncObjectType.MANGA -> findManga(key)?.favorite
            SyncObjectType.AUTHOR -> {
                val id = resolveAuthor(key) ?: return@await null
                sync_projectionQueries.getAuthorMembership(id).executeAsOneOrNull() ?: false
            }
            SyncObjectType.CHAPTER -> unavailable(SyncProjectionUnavailableReason.IDENTITY)
        }
    }

    suspend fun applyMembership(
        key: SyncObjectKey,
        value: Boolean,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Unit = handler.await(inTransaction = true) {
        validateIdentity(key)
        when (key.type) {
            SyncObjectType.MANGA -> {
                val manga = ensureManga(key, describe)
                sync_projectionQueries.setFavorite(value, clock(), manga._id)
                val projected = mangasQueries.getMangaById(manga._id, MangaMapper::mapManga).executeAsOne()
                if (value) {
                    creatorIndexWriter.indexLibraryMangaBatch(
                        listOf(CreatorLibraryIndexEntry(projected, extractCreators.await(projected))),
                    )
                } else {
                    creatorIndexWriter.removeLibraryMangaIndex(manga._id)
                }
                sync_projectionQueries.restoreMangaSyncing(manga.is_syncing, manga._id)
            }
            SyncObjectType.AUTHOR -> {
                val id = resolveAuthor(key) ?: createAuthor(key, describe)
                if (value) {
                    creatorRepository.followCreator(
                        id,
                        sourceIds = null,
                        languageTags = null,
                        syncContext = remoteContext,
                    )
                } else {
                    creatorRepository.unfollowCreator(id, syncContext = remoteContext)
                }
            }
            SyncObjectType.CHAPTER -> unavailable(SyncProjectionUnavailableReason.IDENTITY)
        }
    }

    suspend fun applyReadStatus(
        chapterKey: SyncObjectKey,
        read: Boolean,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Unit = handler.await(inTransaction = true) {
        validateChapter(chapterKey)
        val chapter = ensureChapter(chapterKey, describe)
        sync_projectionQueries.setReadStatus(read, chapter._id)
        sync_importQueries.setPublicReadStatus(if (read) 1L else 0L, chapter._id)
        sync_projectionQueries.restoreChapterSyncing(chapter.is_syncing, chapter._id)
    }

    suspend fun applyResume(
        mangaKey: SyncObjectKey,
        chapterKey: SyncObjectKey,
        pageIndex: Int,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Unit = handler.await(inTransaction = true) {
        validateRelation(mangaKey, chapterKey)
        if (pageIndex < 0) unavailable(SyncProjectionUnavailableReason.IDENTITY)
        val chapter = ensureChapter(chapterKey, describe)
        sync_projectionQueries.setResumePage(pageIndex.toLong(), chapter._id)
        sync_importQueries.setPublicResume(pageIndex.toLong(), chapter._id)
        sync_projectionQueries.restoreChapterSyncing(chapter.is_syncing, chapter._id)
    }

    suspend fun applyHistory(
        mangaKey: SyncObjectKey,
        chapterKey: SyncObjectKey,
        readAt: Long,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Unit = handler.await(inTransaction = true) {
        validateRelation(mangaKey, chapterKey)
        if (readAt < 0) unavailable(SyncProjectionUnavailableReason.IDENTITY)
        val chapter = ensureChapter(chapterKey, describe)
        val previous = sync_projectionQueries.getChapterHistory(chapter._id).executeAsOneOrNull()?.last_read?.time
        historyQueries.upsert(chapter._id, Date(maxOf(previous ?: Long.MIN_VALUE, readAt)), 0)
        sync_importQueries.setPublicHistory(readAt, chapter._id)
    }

    private fun Database.findManga(key: SyncObjectKey): Mangas? = sync_projectionQueries.getMangaByIdentity(
        requireNotNull(key.originalUrl),
        requireNotNull(key.sourceId).toLong(),
    ).executeAsOneOrNull()

    private fun Database.ensureManga(
        key: SyncObjectKey,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Mangas {
        findManga(key)?.let { return it }
        requireSource(key)
        val description = description(key, describe)
        val manga = Manga.create().copy(
            source = requireNotNull(key.sourceId).toLong(),
            url = requireNotNull(key.originalUrl),
            title = description.title,
            author = description.author,
            artist = description.artist,
            thumbnailUrl = description.thumbnailUrl,
        )
        mangasQueries.insert(
            source = manga.source, url = manga.url, artist = manga.artist, author = manga.author,
            description = manga.description, genre = manga.genre, title = manga.title, status = manga.status,
            thumbnailUrl = manga.thumbnailUrl, favorite = manga.favorite, lastUpdate = manga.lastUpdate,
            nextUpdate = manga.nextUpdate, initialized = manga.initialized, viewerFlags = manga.viewerFlags,
            chapterFlags = manga.chapterFlags, coverLastModified = manga.coverLastModified, dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy, calculateInterval = manga.fetchInterval.toLong(),
            version = manga.version, notes = manga.notes,
        )
        return requireNotNull(findManga(key))
    }

    private fun Database.ensureChapter(
        key: SyncObjectKey,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Chapters {
        sync_projectionQueries.getChapterByIdentity(
            requireNotNull(key.originalUrl),
            requireNotNull(key.parentUrl),
            requireNotNull(key.sourceId).toLong(),
        ).executeAsOneOrNull()?.let { return it }
        requireSource(key)
        val description = description(key, describe)
        val manga = ensureManga(parentKey(key), describe)
        val chapter = Chapter.create().copy(
            mangaId = manga._id,
            url = requireNotNull(key.originalUrl),
            name = description.title,
            chapterNumber = description.chapterNumber ?: -1.0,
            sourceOrder = description.sourceOrder ?: 0,
            scanlator = description.scanlator,
        )
        chaptersQueries.insert(
            chapter.mangaId, chapter.url, chapter.name, chapter.scanlator, chapter.read, chapter.bookmark,
            chapter.lastPageRead, chapter.chapterNumber, chapter.sourceOrder, chapter.dateFetch, chapter.dateUpload,
            chapter.version,
        )
        val id = chaptersQueries.selectLastInsertedRowId().executeAsOne()
        return chaptersQueries.getChapterById(id).executeAsOne()
    }

    /** A portable key identifies an archive identity; a local merge may explicitly redirect it. */
    private fun Database.resolveAuthor(key: SyncObjectKey): Long? =
        resolveSyncAuthorIdentity(requireNotNull(key.portableKey))

    private fun Database.createAuthor(
        key: SyncObjectKey,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): Long {
        val description = description(key, describe)
        val normalized = CreatorNameNormalizer.normalize(description.title).ifBlank { description.title }
        val now = clock()
        author_archiveQueries.insertArchiveCreator(
            requireNotNull(key.portableKey),
            description.title,
            normalized,
            description.title,
            now,
            now,
        )
        val id = author_archiveQueries.getArchiveCreatorIdByPortableKey(requireNotNull(key.portableKey)).executeAsOne()
        author_archiveQueries.upsertArchiveAlias(
            creatorId = id, rawAlias = description.title, normalizedAlias = normalized, source = "PRIMARY",
            evidence = "remote identity display name", confidence = 1.0, isManual = false,
            createdAt = now, lastModifiedAt = now,
        )
        return id
    }

    private fun description(
        key: SyncObjectKey,
        describe: (SyncObjectKey) -> SyncObjectDescriptor?,
    ): SyncObjectDescriptor {
        val value = describe(key) ?: unavailable(SyncProjectionUnavailableReason.DESCRIPTION)
        if (value.objectKey != key) unavailable(SyncProjectionUnavailableReason.IDENTITY)
        if (value.title.isBlank() || value.title.length > 4096 || value.chapterNumber?.isFinite() == false) {
            unavailable(SyncProjectionUnavailableReason.DESCRIPTION)
        }
        return value
    }

    private fun requireSource(key: SyncObjectKey) {
        if (!sourceAvailable(requireNotNull(key.sourceId).toLong())) unavailable(SyncProjectionUnavailableReason.SOURCE)
    }

    private fun validateRelation(mangaKey: SyncObjectKey, chapterKey: SyncObjectKey) {
        validateIdentity(mangaKey)
        validateChapter(chapterKey)
        if (mangaKey.type != SyncObjectType.MANGA || mangaKey != parentKey(chapterKey)) {
            unavailable(SyncProjectionUnavailableReason.IDENTITY)
        }
    }

    private fun validateChapter(key: SyncObjectKey) {
        validateIdentity(key)
        if (key.type != SyncObjectType.CHAPTER) unavailable(SyncProjectionUnavailableReason.IDENTITY)
    }

    private fun validateIdentity(key: SyncObjectKey) {
        fun String?.url() = !isNullOrBlank() && length <= 4096
        val portableKey = key.portableKey
        val source = key.sourceId?.toLongOrNull()?.toString() == key.sourceId && key.sourceId != null
        val valid = when (key.type) {
            SyncObjectType.MANGA -> source && key.originalUrl.url() && key.parentUrl == null && key.portableKey == null
            SyncObjectType.CHAPTER -> source && key.originalUrl.url() && key.parentUrl.url() && key.portableKey == null
            SyncObjectType.AUTHOR -> !portableKey.isNullOrBlank() && portableKey.length <= 1024 &&
                key.sourceId == null && key.originalUrl == null && key.parentUrl == null
        }
        if (!valid) unavailable(SyncProjectionUnavailableReason.IDENTITY)
    }

    private fun parentKey(key: SyncObjectKey) =
        SyncObjectKey(SyncObjectType.MANGA, sourceId = key.sourceId, originalUrl = key.parentUrl)

    private fun unavailable(reason: SyncProjectionUnavailableReason): Nothing = throw SyncProjectionUnavailable(reason)

    private companion object {
        val remoteContext = SyncMutationContext(SyncOrigin.REMOTE_SYNC, uploadAllowed = false)
    }
}
