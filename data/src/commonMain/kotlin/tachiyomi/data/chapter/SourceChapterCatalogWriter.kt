package tachiyomi.data.chapter

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.observeDirectoryPhase
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.ReadyCreatorArchiveBootstrap
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.manga.model.Manga

/** Source catalogue persistence for non-deleting Reader preparation and canonical directory commits. */
class SourceChapterCatalogWriter(
    private val chapters: ChapterRepository,
    private val archive: CreatorArchiveRepository? = null,
    private val handler: DatabaseHandler? = null,
    private val bootstrap: CreatorArchiveBootstrap = ReadyCreatorArchiveBootstrap,
    private val extensionIdentity: (Long) -> SourceDateExtensionIdentity = {
        SourceDateExtensionIdentity("unknown.extension", "unknown")
    },
) {
    suspend fun <T> transaction(block: suspend () -> T): T {
        bootstrap.awaitReady()
        return handler?.await(inTransaction = true) { block() } ?: block()
    }

    /** The canonical directory operation; platform file and download phases remain outside it. */
    suspend fun commitDirectory(
        manga: Manga,
        request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit,
    ): tachiyomi.domain.chapter.service.ChapterDirectoryResult = transaction {
        require(request.mangaId == manga.id)
        if (handler != null) {
            val current = handler.await { mangasQueries.getMangaById(manga.id).executeAsOneOrNull() }
            check(current != null && current.source == manga.source && current.url == manga.url) {
                "Source manga identity conflict"
            }
        }
        // Guard before metadata/index writes can replace an independently changed association.
        validateWorkIdentity(manga)
        val work = key(manga)
        val effects = request.effects?.also {
            require(
                it.sourceId == manga.source && it.mangaUrl == manga.url && it.workNaturalKey == work.stableSourceUrl,
            )
        }?.copy(
            dates = request.source.map {
                tachiyomi.domain.chapter.service.DirectoryChapterDate(it.chapter.url, it.originalUploadDate)
            },
        )
        val committed = chapters.syncDirectory(request.copy(effects = effects))
        val response = request.source.associateBy { it.chapter.url }
        val missingDates = chapters.getChapterByMangaId(manga.id).filter {
            it.url in response && it.dateFetch <= 0
        }
        if (missingDates.isNotEmpty()) {
            chapters.updateAll(missingDates.map { ChapterUpdate(it.id, dateFetch = request.now) })
        }
        val stored = chapters.getChapterByMangaId(manga.id).associateBy { it.url }
        request.source.forEach { prepared ->
            val chapter = checkNotNull(stored[prepared.chapter.url])
            check(chapter.sourceOrder == prepared.chapter.sourceOrder && chapter.dateFetch > 0) {
                "Source catalogue write verification failed"
            }
        }
        val phase = committed.phase
        if (effects?.observe == true) {
            val repository = checkNotNull(archive)
            val persisted = handler?.await {
                mangasQueries.getMangaById(manga.id, MangaMapper::mapManga).executeAsOne()
            } ?: manga
            repository.upsertSourceWork(
                manga.source,
                work.stableSourceUrl,
                manga.id,
                persisted.title,
                persisted.author,
                persisted.artist,
                persisted.thumbnailUrl,
                detailsFetchedAt = null,
            )
            repository.observeDirectoryPhase(checkNotNull(phase))
            if (handler != null) {
                val observation = checkNotNull(repository.getSourceWorkCatalog(work, manga.id))
                check(
                    observation.mangaId == manga.id && observation.chapterCount == request.source.size.toLong() &&
                        observation.completeness == ChapterCatalogCompleteness.COMPLETE,
                )
            }
            val pending = phase.copy(observationPending = false)
            chapters.acknowledgeDirectoryPhase(pending)
            committed.copy(phase = pending.takeUnless { it.complete })
        } else {
            committed
        }
    }

    fun validate(sourceChapters: List<SChapter>): List<SChapter> {
        if (sourceChapters.isEmpty()) throw NoChaptersException()
        require(sourceChapters.all { it.url.isNotBlank() && it.url == it.url.trim() && it.name.isNotBlank() }) {
            "Invalid source chapter identity"
        }
        return sourceChapters.distinctBy { it.url }
    }

    suspend fun needsRefresh(manga: Manga): Boolean {
        val stored = chapters.getChapterByMangaId(manga.id)
        check(stored.map { it.url }.distinct().size == stored.size) { "Duplicate local chapter identity" }
        // Library indexing can detach nonfavorite works; exact natural-key evidence remains usable.
        val observation = archive?.getSourceWorkCatalog(key(manga), manga.id)
        return stored.isEmpty() || observation?.completeness != ChapterCatalogCompleteness.COMPLETE ||
            (observation.mangaId != null && observation.mangaId != manga.id) ||
            observation.chapterCount != stored.size.toLong() || stored.any { it.dateFetch <= 0 } ||
            stored.map { it.sourceOrder }.sorted() != stored.indices.map { it.toLong() }
    }

    /** Recheck inside the transaction before any manga/index write can rebind the archive. */
    suspend fun validateWorkIdentity(manga: Manga) {
        archive?.getSourceWorkCatalog(key(manga), manga.id)
    }

    /** Must be called from [transaction]; only source fields are written, never user progress. */
    suspend fun merge(manga: Manga, sourceChapters: List<SChapter>): CatalogMerge {
        val remote = validate(sourceChapters)
        if (handler != null) {
            val current = handler.await { mangasQueries.getMangaById(manga.id).executeAsOneOrNull() }
            check(current != null && current.source == manga.source && current.url == manga.url) {
                "Source manga identity conflict"
            }
        }
        validateWorkIdentity(manga)
        val stored = chapters.getChapterByMangaId(manga.id)
        check(stored.map { it.url }.distinct().size == stored.size) { "Duplicate local chapter identity" }
        val byUrl = stored.associateBy { it.url }
        val now = System.currentTimeMillis()
        val updates = mutableListOf<ChapterUpdate>()
        val additions = remote.mapIndexedNotNull { index, source ->
            val number = tachiyomi.domain.chapter.service.ChapterRecognition.parseChapterNumber(
                manga.title,
                source.name,
                source.chapter_number.toDouble(),
            )
            val old = byUrl[source.url]
            if (old != null) {
                updates += ChapterUpdate(
                    id = old.id,
                    chapterNumber = number,
                    memo = source.memo,
                    sourceOrder = index.toLong(),
                    dateFetch = now.takeIf { old.dateFetch == 0L },
                )
                null
            } else {
                Chapter.create().copy(
                    mangaId = manga.id, url = source.url, name = source.name,
                    dateUpload = source.date_upload, chapterNumber = number,
                    scanlator = source.scanlator?.ifBlank { null }?.trim(),
                    sourceOrder = index.toLong(), dateFetch = now, memo = source.memo,
                )
            }
        }
        if (updates.isNotEmpty()) chapters.updateAll(updates)
        val inserted = if (additions.isEmpty()) emptyList() else chapters.addAll(additions)
        check(inserted.size == additions.size) { "Source chapter insertion failed" }
        val final = chapters.getChapterByMangaId(manga.id)
        check(final.map { it.url }.distinct().size == final.size) { "Duplicate local chapter identity" }
        val finalByUrl = final.associateBy { it.url }
        val verified = remote.mapIndexed { index, source ->
            checkNotNull(finalByUrl[source.url]).also {
                check(it.sourceOrder == index.toLong() && it.dateFetch > 0) {
                    "Source catalogue write verification failed"
                }
            }
        }
        val key = key(manga)
        archive?.upsertSourceWork(
            manga.source,
            key.stableSourceUrl,
            manga.id,
            manga.title,
            manga.author,
            manga.artist,
            manga.thumbnailUrl,
            detailsFetchedAt = null,
        )
        val extension = extensionIdentity(manga.source)
        archive?.recordSourceDateQualityObservations(
            remote.map { chapter ->
                val value = chapter.date_upload.takeIf { it > 0 }
                SourceDateObservation(
                    identity = SourceDateQualityIdentity(
                        extension.packageName,
                        extension.version,
                        manga.source,
                        SourceDateField.CHAPTER_UPDATED,
                    ),
                    workNaturalKey = key.stableSourceUrl,
                    chapterNaturalKey = chapter.url,
                    rawValue = value?.toString(),
                    valueAt = value,
                    precision = value?.let { SourceDatePrecision.DAY } ?: SourceDatePrecision.UNKNOWN,
                    observedAt = now,
                    reason = value?.let { null } ?: "missing-date",
                )
            },
            now,
        )
        archive?.updateSourceWorkCatalog(
            key,
            remote.size.toLong(),
            ChapterCatalogCompleteness.COMPLETE,
            remote.map { it.date_upload }.filter { it > 0 }.maxOrNull(),
            now,
            manga.id,
        )
        if (handler != null) {
            val observation =
                checkNotNull(archive?.getSourceWorkCatalog(key, manga.id)) {
                    "Source catalogue observation was not stored"
                }
            check(
                observation.chapterCount == verified.size.toLong() &&
                    observation.completeness == ChapterCatalogCompleteness.COMPLETE,
            )
        }
        return CatalogMerge(verified, inserted)
    }

    private fun key(manga: Manga) = SourceWorkNaturalKey(
        manga.source,
        CreatorSourceWorkKey.stableUrl(manga.url, manga.title, manga.author, manga.artist),
    )
}

data class CatalogMerge(val chapters: List<Chapter>, val added: List<Chapter>)
