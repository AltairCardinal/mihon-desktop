package tachiyomi.data.chapter

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.data.sync.journal.appendChapterReadOperation
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.affectsCreatorIndex
import tachiyomi.data.manga.applyMangaUpdateFields
import tachiyomi.data.manga.reconcileMangaCreatorIndex
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ChapterRepositoryImpl(
    private val handler: DatabaseHandler,
    private val creatorIndexWriter: tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter =
        tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
) : ChapterRepository {

    override suspend fun pendingDirectoryPhase(mangaId: Long): tachiyomi.domain.chapter.service.ChapterDirectoryPhase? =
        handler.await {
            chapter_directory_phasesQueries.getForManga(mangaId).executeAsOneOrNull()?.let {
                Json.decodeFromString<tachiyomi.domain.chapter.service.ChapterDirectoryPhase>(it)
            }
        }

    override suspend fun acknowledgeDirectoryPhase(phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase) =
        handler.await(inTransaction = true) {
            val existing = requireNotNull(
                chapter_directory_phasesQueries.getForManga(phase.mangaId).executeAsOneOrNull(),
            )
                .let { Json.decodeFromString<tachiyomi.domain.chapter.service.ChapterDirectoryPhase>(it) }
            check(existing.id == phase.id) { "Directory phase was replaced" }
            check(
                existing.effects == phase.effects && existing.currentTitle == phase.currentTitle &&
                    existing.addedIds == phase.addedIds && existing.downloadIds == phase.downloadIds,
            )
            check(
                phase.files.all { it in existing.files } &&
                    (!phase.observationPending || existing.observationPending) &&
                    (!phase.downloadsPending || existing.downloadsPending),
            ) {
                "Directory acknowledgement would repeat completed work"
            }
            if (phase.complete) {
                chapter_directory_phasesQueries.removePhase(phase.mangaId, phase.id)
            } else {
                chapter_directory_phasesQueries.updatePhase(Json.encodeToString(phase), phase.mangaId, phase.id)
            }
            Unit
        }

    override suspend fun getChapterUrlIdentity(chapterId: Long): tachiyomi.domain.chapter.model.ChapterUrlIdentity? =
        handler.await {
            chaptersQueries.getChapterById(chapterId).executeAsOneOrNull()?.let { readChapterUrlIdentity(it._id) }
        }

    override suspend fun restoreChapterUrlIdentity(
        mangaId: Long,
        chapterId: Long,
        identity: tachiyomi.domain.chapter.model.ChapterUrlIdentity,
    ) = handler.await(inTransaction = true) { restoreChapterUrlAliases(mangaId, chapterId, identity) }

    override suspend fun syncDirectory(
        request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit,
    ): tachiyomi.domain.chapter.service.ChapterDirectoryResult {
        require(request.complete) { "Source chapter directory is incomplete" }
        if (request.source.isEmpty() && !request.allowEmpty) throw tachiyomi.domain.chapter.model.NoChaptersException()
        require(request.source.all { it.chapter.mangaId == request.mangaId && it.chapter.url.isNotBlank() })
        require(request.source.map { it.chapter.url }.distinct().size == request.source.size)
        request.mangaMetadata?.let { value ->
            require(
                value.id == request.mangaId && value.favorite == null && value.notes == null &&
                    value.chapterFlags == null && value.viewerFlags == null && value.version == null &&
                    value.source == null && value.url == null && value.dateAdded == null,
            )
            if (value.affectsCreatorIndex()) creatorIndexWriter.indexLibraryMangaBatch(emptyList())
        }
        return handler.await(inTransaction = true) {
            check(chapter_directory_phasesQueries.getForManga(request.mangaId).executeAsOneOrNull() == null) {
                "Pending directory effects must complete before another directory commit"
            }
            val manga =
                checkNotNull(mangasQueries.getMangaById(request.mangaId).executeAsOneOrNull()) {
                    "Manga no longer exists"
                }
            val stored = chaptersQueries.getChaptersByMangaId(request.mangaId, 0, ::mapChapter).executeAsList()
            val excluded = excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(
                request.mangaId,
            ).executeAsList().toSet()
            val plan = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.create(
                stored,
                request.source,
                request.now,
                request.markDuplicateAsRead,
                excluded,
            )
            request.guardedChapterIds?.let { guarded ->
                val titleChanged = request.mangaMetadata?.title?.let {
                    it != manga.title && (!request.metadataOnlyForNonFavorites || !manga.favorite)
                } == true
                check(guarded.containsAll(plan.affectedDownloadIds(stored, titleChanged))) {
                    "Directory changed while reserving its download identities; retry the refresh"
                }
            }
            // Validate the entire response before any metadata, alias or membership write.
            request.source.forEach { prepared ->
                val owner = chapter_url_aliasesQueries.getOwner(
                    request.mangaId,
                    prepared.chapter.url,
                ).executeAsOneOrNull()
                val mappedId = stored.find { it.url == prepared.chapter.url }?.id
                    ?: plan.updates.find { it.after.url == prepared.chapter.url }?.before?.id
                require(owner == null || owner == mappedId) { "Source response conflicts with a chapter URL alias" }
            }
            plan.updates.filter { it.before.url != it.after.url }.forEach { (previous, current) ->
                val identity = readChapterUrlIdentity(previous.id)
                restoreChapterUrlAliases(
                    request.mangaId,
                    previous.id,
                    identity.copy(aliases = (identity.aliases + previous.url + current.url).distinct()),
                )
            }
            plan.updates.forEach { (_, chapter) ->
                chaptersQueries.updateSourceMetadata(
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateUpload,
                    tachiyomi.data.MemoColumnAdapter.encode(chapter.memo),
                    chapter.id,
                )
            }
            val inserted = plan.additions.map { chapter ->
                chaptersQueries.insert(
                    chapter.mangaId, chapter.url, chapter.name, chapter.scanlator, chapter.read, chapter.bookmark,
                    chapter.lastPageRead, chapter.chapterNumber, chapter.sourceOrder, chapter.dateFetch,
                    chapter.dateUpload, chapter.version, tachiyomi.data.MemoColumnAdapter.encode(chapter.memo),
                )
                chapter.copy(id = chaptersQueries.selectLastInsertedRowId().executeAsOne())
            }
            // Keep removed rows alive until new IDs are allocated; SQLite otherwise reuses the highest deleted ROWID.
            if (plan.removals.isNotEmpty()) chaptersQueries.removeChaptersWithIds(plan.removals.map { it.id })
            mangasQueries.updateChapterDirectoryState(
                request.mangaMemo?.let(tachiyomi.data.MemoColumnAdapter::encode),
                request.now.takeIf { plan.changed },
                request.mangaId,
            )
            request.mangaMetadata?.let { metadata ->
                val effective = if (request.metadataOnlyForNonFavorites && manga.favorite) {
                    tachiyomi.domain.manga.model.MangaUpdate(
                        metadata.id,
                        initialized = metadata.initialized,
                        memo = metadata.memo,
                    )
                } else {
                    metadata
                }
                applyMangaUpdateFields(effective)
                if (effective.affectsCreatorIndex()) reconcileMangaCreatorIndex(request.mangaId, creatorIndexWriter)
            }
            val added = inserted.filter { it.url in plan.newlyAvailableUrls }
            val phase = request.effects?.let { effects ->
                check(effects.sourceId == manga.source && effects.mangaUrl == manga.url)
                val currentTitle = mangasQueries.getMangaById(request.mangaId).executeAsOne().title
                val files = plan.updates.filter {
                    it.before.url != it.after.url || it.before.name != it.after.name ||
                        it.before.scanlator != it.after.scanlator || effects.mangaTitle != currentTitle
                }.map { change ->
                    tachiyomi.domain.chapter.service.DirectoryFileChange(
                        tachiyomi.domain.chapter.service.DirectoryFileChapter.from(change.before),
                        tachiyomi.domain.chapter.service.DirectoryFileChapter.from(change.after),
                    )
                }.toMutableList()
                if (effects.mangaTitle != currentTitle) {
                    stored.filter { old ->
                        old.id !in plan.removals.map { it.id } &&
                            files.none { it.after.id == old.id }
                    }
                        .forEach { old ->
                            val unchanged = tachiyomi.domain.chapter.service.DirectoryFileChapter.from(old)
                            files += tachiyomi.domain.chapter.service.DirectoryFileChange(unchanged, unchanged)
                        }
                }
                val downloadIds = mihon.domain.chapter.interactor.DownloadNewChapterPolicy(
                    effects.downloadEnabled && manga.favorite,
                    effects.downloadUnreadOnly,
                ).select(added, stored).map { it.id }
                tachiyomi.domain.chapter.service.ChapterDirectoryPhase(
                    id = "${request.mangaId}:${request.now}:${kotlin.random.Random.nextLong()}",
                    mangaId = request.mangaId, effects = effects, currentTitle = currentTitle, files = files,
                    addedIds = added.map { it.id }, downloadIds = downloadIds,
                    observationPending = effects.observe, downloadsPending = effects.downloadEnabled,
                ).also { phase ->
                    if (!phase.complete) {
                        chapter_directory_phasesQueries.insertPhase(
                            phase.mangaId,
                            phase.id,
                            Json.encodeToString(phase),
                        )
                    }
                }.takeUnless { it.complete }
            }
            tachiyomi.domain.chapter.service.ChapterDirectoryResult(plan, added, phase)
        }
    }

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            handler.await(inTransaction = true) {
                chapters.map { chapter ->
                    chaptersQueries.insert(
                        chapter.mangaId,
                        chapter.url,
                        chapter.name,
                        chapter.scanlator,
                        chapter.read,
                        chapter.bookmark,
                        chapter.lastPageRead,
                        chapter.chapterNumber,
                        chapter.sourceOrder,
                        chapter.dateFetch,
                        chapter.dateUpload,
                        chapter.version,
                        tachiyomi.data.MemoColumnAdapter.encode(chapter.memo),
                    )
                    val lastInsertId = chaptersQueries.selectLastInsertedRowId().executeAsOne()
                    chapter.copy(id = lastInsertId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    private suspend fun partialUpdate(vararg chapterUpdates: ChapterUpdate) {
        handler.await(inTransaction = true) {
            chapterUpdates.forEach { chapterUpdate ->
                chaptersQueries.update(
                    mangaId = chapterUpdate.mangaId,
                    url = chapterUpdate.url,
                    name = chapterUpdate.name,
                    scanlator = chapterUpdate.scanlator,
                    read = chapterUpdate.read,
                    bookmark = chapterUpdate.bookmark,
                    lastPageRead = chapterUpdate.lastPageRead,
                    chapterNumber = chapterUpdate.chapterNumber,
                    sourceOrder = chapterUpdate.sourceOrder,
                    dateFetch = chapterUpdate.dateFetch,
                    dateUpload = chapterUpdate.dateUpload,
                    chapterId = chapterUpdate.id,
                    version = chapterUpdate.version,
                    isSyncing = 0,
                    memo = chapterUpdate.memo?.let(tachiyomi.data.MemoColumnAdapter::encode),
                )
                chapterUpdate.read?.let { appendChapterReadOperation(chapterUpdate.id, it, chapterUpdate.syncContext) }
            }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            handler.await { chaptersQueries.removeChaptersWithIds(chapterIds) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
        }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return handler.awaitList {
            chaptersQueries.getScanlatorsByMangaId(mangaId) { it.orEmpty() }
        }
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return handler.subscribeToList {
            chaptersQueries.getScanlatorsByMangaId(mangaId) { it.orEmpty() }
        }
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getBookmarkedChaptersByMangaId(
                mangaId,
                ::mapChapter,
            )
        }
    }

    override suspend fun getChapterById(id: Long): Chapter? {
        return handler.awaitOneOrNull { chaptersQueries.getChapterById(id, ::mapChapter) }
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> {
        return handler.subscribeToList {
            chaptersQueries.getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
        }
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return handler.awaitOneOrNull {
            chaptersQueries.getChapterByUrlAndMangaId(
                chapterUrl = url,
                mangaId = mangaId,
                mapper = ::mapChapter,
            )
        }
    }

    private fun mapChapter(
        id: Long,
        mangaId: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        @Suppress("UNUSED_PARAMETER")
        isSyncing: Long,
        memo: ByteArray,
    ): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        lastModifiedAt = lastModifiedAt,
        version = version,
        memo = tachiyomi.data.MemoColumnAdapter.decode(memo),
    )
}
