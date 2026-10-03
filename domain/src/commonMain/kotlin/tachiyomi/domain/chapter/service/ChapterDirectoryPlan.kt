package tachiyomi.domain.chapter.service

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** A prepared source response. Platform preparation happens before any database transaction. */
data class PreparedSourceChapter(val chapter: Chapter, val originalUploadDate: Long)

data class ChapterDirectoryChange(val before: Chapter, val after: Chapter)

data class ChapterDirectoryCommit(
    val mangaId: Long,
    val source: List<PreparedSourceChapter>,
    val now: Long,
    val markDuplicateAsRead: Boolean = false,
    val mangaMemo: kotlinx.serialization.json.JsonObject? = null,
    val allowEmpty: Boolean = false,
    val complete: Boolean = true,
    val mangaMetadata: tachiyomi.domain.manga.model.MangaUpdate? = null,
    val metadataOnlyForNonFavorites: Boolean = false,
    val effects: ChapterDirectoryEffects? = null,
    val guardedChapterIds: Set<Long>? = null,
)

data class ChapterDirectoryResult(
    val plan: ChapterDirectoryPlan,
    val added: List<Chapter>,
    val phase: ChapterDirectoryPhase? = null,
)

data class ChapterDirectoryPlan(
    val updates: List<ChapterDirectoryChange>,
    val additions: List<Chapter>,
    val removals: List<Chapter>,
    val newlyAvailableUrls: Set<String>,
) {
    val changed: Boolean get() = updates.isNotEmpty() || additions.isNotEmpty() || removals.isNotEmpty()

    fun affectedDownloadIds(stored: List<Chapter>, titleChanged: Boolean): Set<Long> =
        (
            removals.map { it.id } + updates.filter {
                it.before.url != it.after.url || it.before.name != it.after.name ||
                    it.before.scanlator != it.after.scanlator
            }.map { it.before.id } + if (titleChanged) stored.map { it.id } else emptyList()
            ).toSet()

    companion object {
        fun prepare(
            manga: Manga,
            raw: List<SChapter>,
            prepareChapter: (SChapter) -> Unit = {},
        ): List<PreparedSourceChapter> = raw.distinctBy { it.url }.mapIndexed { index, sourceChapter ->
            require(sourceChapter.url.isNotBlank()) { "Source returned an empty chapter URL" }
            val chapter = SChapter.create().apply {
                copyFrom(sourceChapter)
                name = with(ChapterSanitizer) { name.sanitize(manga.title) }
            }
            prepareChapter(chapter)
            require(chapter.url.isNotBlank()) { "Prepared chapter URL is empty" }
            PreparedSourceChapter(
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = chapter.url,
                    name = chapter.name,
                    scanlator = chapter.scanlator?.ifBlank { null }?.trim(),
                    dateUpload = chapter.date_upload,
                    chapterNumber = ChapterRecognition.parseChapterNumber(
                        manga.title,
                        chapter.name,
                        chapter.chapter_number.toDouble(),
                    ),
                    sourceOrder = index.toLong(),
                    memo = chapter.memo,
                ),
                sourceChapter.date_upload,
            )
        }.also { chapters ->
            require(
                chapters.map {
                    it.chapter.url
                }.distinct().size == chapters.size,
            ) { "Prepared chapter URLs are ambiguous" }
        }

        /** Uses the latest stored rows, including reading/bookmark/page state, inside the commit transaction. */
        fun create(
            stored: List<Chapter>,
            source: List<PreparedSourceChapter>,
            now: Long,
            markDuplicateAsRead: Boolean,
            excludedScanlators: Set<String>,
        ): ChapterDirectoryPlan {
            val sourceUrls = source.map { it.chapter.url }.toSet()
            val missing = stored.filterNot { it.url in sourceUrls }
            val incoming = source.filterNot { prepared -> stored.any { it.url == prepared.chapter.url } }
            fun Chapter.identity(): Pair<Double, String?>? =
                chapterNumber.takeIf { it.isFinite() && it >= 0 }?.let { it to scanlator }
            val oldIdentities = missing.mapNotNull { old ->
                old.identity()?.let { it to old }
            }.groupBy({ it.first }, { it.second })
            val newIdentities = incoming.mapNotNull { new ->
                new.chapter.identity()?.let { it to new.chapter }
            }.groupBy({ it.first }, { it.second })
            val storedCounts = stored.mapNotNull { it.identity() }.groupingBy { it }.eachCount()
            val sourceCounts = source.mapNotNull { it.chapter.identity() }.groupingBy { it }.eachCount()
            val relinks = newIdentities.mapNotNull { (identity, chapters) ->
                val previous = oldIdentities[identity]
                if (chapters.size == 1 && previous?.size == 1 && storedCounts[identity] == 1 &&
                    sourceCounts[identity] == 1
                ) {
                    chapters.single().url to previous.single()
                } else {
                    null
                }
            }.toMap()
            val relinkedIds = relinks.values.map { it.id }.toSet()
            val readNumbers = stored.filter { it.read && it.isRecognizedNumber }.map { it.chapterNumber }.toSet()
            val missingNumbers = missing.map { it.chapterNumber }.toSet()
            val missingRead = missing.filter { it.read }.map { it.chapterNumber }.toSet()
            val missingBookmarks = missing.filter { it.bookmark }.map { it.chapterNumber }.toSet()
            val previousFetch = missing.sortedByDescending {
                it.dateFetch
            }.associate { it.chapterNumber to it.dateFetch }
            val additions = mutableListOf<Chapter>()
            val updates = mutableListOf<ChapterDirectoryChange>()
            val available = mutableSetOf<String>()
            var maxSeenUploadDate = 0L
            var itemCount = incoming.size
            for (prepared in source) {
                val remote = prepared.chapter
                val existing = stored.find { it.url == remote.url } ?: relinks[remote.url]
                if (existing != null) {
                    val updated = existing.copy(
                        url = remote.url,
                        name = remote.name,
                        chapterNumber = remote.chapterNumber,
                        scanlator = remote.scanlator,
                        sourceOrder = remote.sourceOrder,
                        memo = remote.memo,
                        dateUpload = remote.dateUpload.takeIf { it != 0L } ?: existing.dateUpload,
                    )
                    if (updated != existing) updates += ChapterDirectoryChange(existing, updated)
                    if (remote.url in relinks) {
                        if (remote.dateUpload !=
                            0L
                        ) {
                            maxSeenUploadDate = maxOf(maxSeenUploadDate, prepared.originalUploadDate)
                        }
                        itemCount--
                    }
                    continue
                }
                var addition = remote.copy(
                    dateFetch = now + itemCount--,
                    dateUpload = remote.dateUpload.takeIf { it != 0L } ?: maxSeenUploadDate.takeIf { it != 0L } ?: now,
                )
                if (remote.dateUpload != 0L) maxSeenUploadDate = maxOf(maxSeenUploadDate, prepared.originalUploadDate)
                var inherited = false
                if (addition.chapterNumber in readNumbers && markDuplicateAsRead) {
                    addition = addition.copy(read = true)
                    inherited = true
                }
                if (addition.isRecognizedNumber && addition.chapterNumber in missingNumbers) {
                    addition = addition.copy(
                        read = addition.chapterNumber in missingRead,
                        bookmark = addition.chapterNumber in missingBookmarks,
                        dateFetch = previousFetch[addition.chapterNumber] ?: addition.dateFetch,
                    )
                    inherited = true
                }
                additions += addition
                if (!inherited && addition.scanlator !in excludedScanlators) available += addition.url
            }
            return ChapterDirectoryPlan(updates, additions, missing.filterNot { it.id in relinkedIds }, available)
        }
    }
}

class ChapterDirectoryDownloadConflictException(
    message: String =
        "Complete or cancel the affected chapter downloads before refreshing their directory",
) : IllegalStateException(message)
