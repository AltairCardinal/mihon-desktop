package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
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

/** Non-deleting Desktop source merge. All metadata and catalogue evidence commit together. */
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
        validateWorkIdentity(manga)
        val stored = chapters.getChapterByMangaId(manga.id)
        check(stored.map { it.url }.distinct().size == stored.size) { "Duplicate local chapter identity" }
        val byUrl = stored.associateBy { it.url }
        val now = System.currentTimeMillis()
        val updates = mutableListOf<ChapterUpdate>()
        val additions = remote.mapIndexedNotNull { index, source ->
            val number = source.recognizedChapterNumber(manga)
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
