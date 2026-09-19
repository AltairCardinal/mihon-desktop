package mihon.desktop.domain

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.domain.error.AppError
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaUpdateService

private const val MAX_REFRESH_STATES = 128

/**
 * Persists a browsed source manga so Browse and Library can share MangaDetailScreen.
 */
class SaveSourceMangaForDetails(
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val creatorArchiveRepository: CreatorArchiveRepository? = null,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sourceDateExtensionIdentityProvider: (Long) -> SourceDateExtensionIdentity = {
        SourceDateExtensionIdentity("unknown.extension", "unknown")
    },
) {

    private val mutableRefreshStates = MutableStateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>>(emptyMap())
    val refreshStates: StateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>> = mutableRefreshStates.asStateFlow()

    suspend fun awaitSearchResults(results: List<SManga>, sourceId: Long): List<Manga> =
        results.map { it.toDomainManga(sourceId) }
            .distinctBy(Manga::url)
            .let { networkToLocalManga(it) }

    fun refreshFromSource(
        source: Source,
        listedManga: SManga,
    ): Job {
        val key = SourceMangaRefreshKey(source.id, listedManga.url)
        updateRefreshState(key, SourceMangaRefreshState.Loading)
        return refreshScope.launch {
            when (val result = safeSourceCall { awaitFromSource(source, listedManga) }) {
                is SourceCallResult.Success -> updateRefreshState(key, null)
                is SourceCallResult.Error -> updateRefreshState(key, SourceMangaRefreshState.Failure(result.error))
                is SourceCallResult.Timeout -> updateRefreshState(key, SourceMangaRefreshState.Failure(result.error))
            }
        }
    }

    private fun updateRefreshState(key: SourceMangaRefreshKey, state: SourceMangaRefreshState?) {
        mutableRefreshStates.update { current ->
            LinkedHashMap(current).apply {
                remove(key)
                if (state != null) put(key, state)
                while (size > MAX_REFRESH_STATES) remove(keys.first())
            }
        }
    }

    suspend fun awaitFromSource(
        source: Source,
        listedManga: SManga,
    ): Manga {
        val manga = mangaRepository.getMangaByUrlAndSourceId(listedManga.url, source.id)
            ?: listedManga.toDomainManga(source.id)
        val update = SourceMangaUpdateService().await(
            source, manga, chapterRepository.getChapterByMangaId(manga.id), fetchDetails = true, fetchChapters = true,
        )
        val details = mergeSourceMangaDetails(
            original = listedManga,
            details = update.manga,
        )
        return await(details, source.id, update.chapters)
    }

    suspend fun awaitLinkedChapter(
        source: Source,
        listedManga: SManga,
        linkedChapter: SChapter?,
    ): ResolvedSourceChapter {
        val manga = awaitSearchResults(listOf(listedManga), source.id).single()
        val existing = linkedChapter?.let { chapterRepository.getChapterByUrlAndMangaId(it.url, manga.id) }
        if (linkedChapter == null || existing != null) return ResolvedSourceChapter(manga, existing)
        val update = SourceMangaUpdateService().await(
            source, manga, chapterRepository.getChapterByMangaId(manga.id), fetchDetails = false, fetchChapters = true,
        )
        val updatedManga = await(update.manga, source.id, update.chapters, fetchDetails = false)
        val chapter = chapterRepository.getChapterByUrlAndMangaId(linkedChapter.url, manga.id)
        return ResolvedSourceChapter(updatedManga, chapter)
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
            memo = sManga.memo,
        )

        return networkToLocalManga(networkManga)
    }

    suspend fun awaitListedForDetails(
        sManga: SManga,
        sourceId: Long,
    ): ListedMangaForDetails {
        val manga = awaitListed(sManga, sourceId)
        val hasNoChapters = chapterRepository.getChapterByMangaId(manga.id).isEmpty()
        return ListedMangaForDetails(
            manga = manga,
            needsRefresh = !manga.initialized || hasNoChapters,
        )
    }

    suspend fun await(
        sManga: SManga,
        sourceId: Long,
        sChapters: List<SChapter>,
        fetchDetails: Boolean = true,
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
            memo = sManga.memo,
        )

        val storedManga = if (fetchDetails) networkToLocalManga(networkManga) else {
            requireNotNull(mangaRepository.getMangaByUrlAndSourceId(sManga.url, sourceId))
        }
        check(mangaRepository.update(MangaUpdate(storedManga.id, memo = sManga.memo, initialized = true.takeIf { fetchDetails })))
        val dbManga = mangaRepository.getMangaById(storedManga.id)
        val knownChaptersByUrl = chapterRepository.getChapterByMangaId(dbManga.id)
            .associateBy { it.url }
        val now = System.currentTimeMillis()
        val toUpdate = mutableListOf<ChapterUpdate>()

        val toAdd = sChapters.mapIndexedNotNull { index, sourceChapter ->
            val chapterNumber = sourceChapter.recognizedChapterNumber(dbManga)
            val knownChapter = knownChaptersByUrl[sourceChapter.url]
            if (knownChapter != null) {
                if (knownChapter.chapterNumber != chapterNumber || knownChapter.memo != sourceChapter.memo) {
                    toUpdate += ChapterUpdate(id = knownChapter.id, chapterNumber = chapterNumber, memo = sourceChapter.memo)
                }
                return@mapIndexedNotNull null
            }
            Chapter.create().copy(
                mangaId = dbManga.id,
                url = sourceChapter.url,
                name = sourceChapter.name,
                dateUpload = sourceChapter.date_upload,
                chapterNumber = chapterNumber,
                scanlator = sourceChapter.scanlator?.ifBlank { null }?.trim(),
                sourceOrder = index.toLong(),
                dateFetch = now,
                memo = sourceChapter.memo,
            )
        }

        if (toUpdate.isNotEmpty()) {
            chapterRepository.updateAll(toUpdate)
        }
        if (toAdd.isNotEmpty()) {
            chapterRepository.addAll(toAdd)
        }

        val extensionIdentity = sourceDateExtensionIdentityProvider(dbManga.source)
        val stableSourceUrl = CreatorSourceWorkKey.stableUrl(
            url = dbManga.url,
            title = dbManga.title,
            author = dbManga.author,
            artist = dbManga.artist,
        )
        creatorArchiveRepository?.recordSourceDateQualityObservations(
            sChapters.mapNotNull { chapter ->
                chapter.url.takeIf(String::isNotBlank)?.let { chapterUrl ->
                    val value = chapter.date_upload.takeIf { it > 0L }
                    SourceDateObservation(
                        identity = SourceDateQualityIdentity(
                            extensionPackage = extensionIdentity.packageName,
                            extensionVersion = extensionIdentity.version,
                            sourceId = dbManga.source,
                            field = SourceDateField.CHAPTER_UPDATED,
                        ),
                        workNaturalKey = stableSourceUrl,
                        chapterNaturalKey = chapterUrl,
                        rawValue = value?.toString(),
                        valueAt = value,
                        precision = value?.let { SourceDatePrecision.DAY } ?: SourceDatePrecision.UNKNOWN,
                        observedAt = now,
                        reason = value?.let { null } ?: "missing-date",
                    )
                }
            },
            now = now,
        )
        creatorArchiveRepository?.updateSourceWorkCatalog(
            sourceWork = tachiyomi.domain.creator.model.SourceWorkNaturalKey(
                sourceId = dbManga.source,
                stableSourceUrl = stableSourceUrl,
            ),
            chapterCount = sChapters.size.toLong(),
            completeness = ChapterCatalogCompleteness.COMPLETE,
            latestChapterAt = sChapters.map { it.date_upload }.filter { it > 0L }.maxOrNull(),
            observedAt = now,
            mangaId = dbManga.id,
        )

        return dbManga
    }
}

data class SourceMangaRefreshKey(
    val sourceId: Long,
    val mangaUrl: String,
)

sealed interface SourceMangaRefreshState {
    data object Loading : SourceMangaRefreshState
    data class Failure(val error: AppError) : SourceMangaRefreshState
}

data class ListedMangaForDetails(
    val manga: Manga,
    val needsRefresh: Boolean,
)

data class ResolvedSourceChapter(
    val manga: Manga,
    val chapter: Chapter?,
)

internal fun mergeSourceMangaDetails(original: SManga, details: SManga): SManga = details.also { d ->
    runCatching { d.url }.onFailure { d.url = original.url }
    runCatching { d.title }.onFailure { d.title = original.title }
    if (d.thumbnail_url.isNullOrBlank()) {
        d.thumbnail_url = original.thumbnail_url
    }
}
