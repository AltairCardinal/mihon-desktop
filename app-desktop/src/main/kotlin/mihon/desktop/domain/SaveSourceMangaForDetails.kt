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
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.finishDirectoryPhase
import tachiyomi.domain.chapter.service.observeDirectoryPhase
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
    private val initialChapterFlags: () -> Long = { 0L },
    private val finishPendingDirectory: (
        suspend (Manga, tachiyomi.domain.chapter.service.ChapterDirectoryPhase) -> Unit
    )? = null,
    private val downloadPolicy: suspend (Manga) -> mihon.domain.chapter.interactor.DownloadNewChapterPolicy = {
        mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
    },
    private val disallowNonAsciiFilenames: () -> Boolean = { false },
    private val directoryCommit: suspend (Manga, tachiyomi.domain.chapter.service.ChapterDirectoryCommit) ->
    tachiyomi.domain.chapter.service.ChapterDirectoryResult = { _, request ->
        chapterRepository.syncDirectory(request)
    },
) {

    private val mutableRefreshStates = MutableStateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>>(emptyMap())
    val refreshStates: StateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>> =
        mutableRefreshStates.asStateFlow()

    suspend fun awaitSearchResults(results: List<SManga>, sourceId: Long): List<Manga> =
        results.map { it.toDomainManga(sourceId).copy(chapterFlags = initialChapterFlags()) }
            .distinctBy(Manga::url)
            .let { networkToLocalManga(it) }

    fun refreshFromSource(
        source: Source,
        listedManga: SManga,
        origin: String = "BROWSE",
    ): Job {
        val key = SourceMangaRefreshKey(source.id, listedManga.url)
        updateRefreshState(key, SourceMangaRefreshState.Loading)
        return refreshScope.launch {
            when (val result = safeSourceCall { awaitFromSource(source, listedManga, origin) }) {
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
        origin: String = "BROWSE",
    ): Manga {
        val manga = mangaRepository.getMangaByUrlAndSourceId(listedManga.url, source.id)
            ?: listedManga.toDomainManga(source.id)
        chapterRepository.pendingDirectoryPhase(manga.id)?.let { phase ->
            completePhase(manga, phase)
            return mangaRepository.getMangaById(manga.id)
        }
        val update = SourceMangaUpdateService().await(
            source,
            manga,
            chapterRepository.getChapterByMangaId(manga.id),
            fetchDetails = true,
            fetchChapters = true,
        )
        val details = mergeSourceMangaDetails(
            original = listedManga,
            details = update.manga,
        )
        return await(
            details,
            source.id,
            update.chapters,
            complete = update.chapterListComplete,
            sourceName = source.toString(),
            origin = origin,
            prepareChapter = { chapter ->
                if (source is eu.kanade.tachiyomi.source.online.HttpSource) source.prepareNewChapter(chapter, details)
            },
        )
    }

    suspend fun awaitLinkedChapter(
        source: Source,
        listedManga: SManga,
        linkedChapter: SChapter?,
    ): ResolvedSourceChapter {
        mangaRepository.getMangaByUrlAndSourceId(listedManga.url, source.id)?.let { existingManga ->
            chapterRepository.pendingDirectoryPhase(existingManga.id)?.let { phase ->
                completePhase(existingManga, phase)
                return ResolvedSourceChapter(
                    mangaRepository.getMangaById(existingManga.id),
                    linkedChapter?.let { chapterRepository.getChapterByUrlAndMangaId(it.url, existingManga.id) },
                )
            }
        }
        val manga = awaitSearchResults(listOf(listedManga), source.id).single()
        val existing = linkedChapter?.let { chapterRepository.getChapterByUrlAndMangaId(it.url, manga.id) }
        if (linkedChapter == null || existing != null) return ResolvedSourceChapter(manga, existing)
        val update = SourceMangaUpdateService().await(
            source,
            manga,
            chapterRepository.getChapterByMangaId(manga.id),
            fetchDetails = false,
            fetchChapters = true,
        )
        val updatedManga =
            await(
                update.manga,
                source.id,
                update.chapters,
                fetchDetails = false,
                complete = update.chapterListComplete,
                sourceName = source.toString(),
                prepareChapter = { chapter ->
                    if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                        source.prepareNewChapter(
                            chapter,
                            update.manga,
                        )
                    }
                },
            )
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
            chapterFlags = initialChapterFlags(),
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
        complete: Boolean = true,
        prepareChapter: (SChapter) -> Unit = {},
        sourceName: String = sourceId.toString(),
        origin: String = "BROWSE",
    ): Manga {
        require(complete) { "Source chapter directory is incomplete" }
        if (sChapters.isEmpty() && sourceId != 0L) throw tachiyomi.domain.chapter.model.NoChaptersException()
        require(sChapters.all { it.url.isNotBlank() })
        val storedManga = mangaRepository.getMangaByUrlAndSourceId(sManga.url, sourceId)
            ?: awaitListed(sManga, sourceId)
        chapterRepository.pendingDirectoryPhase(storedManga.id)?.let { phase ->
            completePhase(storedManga, phase)
            return mangaRepository.getMangaById(storedManga.id)
        }
        val now = System.currentTimeMillis()
        val extension = sourceDateExtensionIdentityProvider(sourceId)
        val policy = if (origin ==
            "DETAIL_REFRESH"
        ) {
            downloadPolicy(storedManga)
        } else {
            mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
        }
        val workKey = CreatorSourceWorkKey.stableUrl(
            storedManga.url,
            storedManga.title,
            storedManga.author,
            storedManga.artist,
        )
        val metadata = if (fetchDetails) {
            MangaUpdate(
                id = storedManga.id,
                title = sManga.title,
                author = sManga.author,
                updateAuthor = true,
                artist = sManga.artist,
                updateArtist = true,
                description = sManga.description,
                genre = sManga.genre?.split(", ")?.takeIf { it.isNotEmpty() },
                status = sManga.status.toLong(),
                thumbnailUrl = sManga.thumbnail_url,
                updateStrategy = sManga.update_strategy,
                initialized = true,
                memo = sManga.memo,
            )
        } else {
            MangaUpdate(storedManga.id, memo = sManga.memo)
        }
        val committed = directoryCommit(
            storedManga,
            tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                mangaId = storedManga.id,
                source = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(
                    storedManga,
                    sChapters,
                    prepareChapter,
                ),
                now = now,
                allowEmpty = sourceId == 0L,
                complete = complete,
                mangaMetadata = metadata,
                metadataOnlyForNonFavorites = true,
                effects = tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                    sourceId, sourceName, storedManga.url, storedManga.title, origin, now,
                    extension.packageName, extension.version, workKey,
                    sChapters.map { tachiyomi.domain.chapter.service.DirectoryChapterDate(it.url, it.date_upload) },
                    downloadEnabled = policy.enabled, downloadUnreadOnly = policy.unreadOnly,
                    observe = creatorArchiveRepository != null, disallowNonAsciiFilenames = disallowNonAsciiFilenames(),
                ),
            ),
        )
        val dbManga = mangaRepository.getMangaById(storedManga.id)
        committed.phase?.let { completePhase(dbManga, it) }

        return dbManga
    }
    private suspend fun completePhase(manga: Manga, phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase) {
        val consumer = finishPendingDirectory
        if (consumer != null) {
            consumer(manga, phase)
        } else {
            chapterRepository.finishDirectoryPhase(
                phase,
                rename = { _, _ -> },
                observe = { requireNotNull(creatorArchiveRepository).observeDirectoryPhase(it) },
                download = { _, chapters ->
                    check(chapters.isEmpty()) { "Browse cannot replace an accepted download policy" }
                },
            )
        }
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
