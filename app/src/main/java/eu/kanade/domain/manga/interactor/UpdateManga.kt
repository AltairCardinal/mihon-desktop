package eu.kanade.domain.manga.interactor

import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.service.observeDirectoryPhase
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZonedDateTime

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
    private val creatorArchiveRepository: CreatorArchiveRepository? = null,
    private val sourceDateExtensionIdentityProvider: (Long) -> SourceDateExtensionIdentity = {
        SourceDateExtensionIdentity("unknown.extension", "unknown")
    },
) {

    internal suspend fun mangaForDirectory(id: Long) = mangaRepository.getMangaById(id)

    suspend fun awaitFromRemote(
        manga: Manga,
        source: eu.kanade.tachiyomi.source.Source,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = 0L to 0L,
        chapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository = Injekt.get(),
        syncChaptersWithSource: eu.kanade.domain.chapter.interactor.SyncChaptersWithSource = Injekt.get(),
        coverCache: CoverCache = Injekt.get(),
        libraryPreferences: LibraryPreferences = Injekt.get(),
        downloadManager: DownloadManager = Injekt.get(),
        requireFavorite: Boolean = false,
    ): Pair<Manga, List<tachiyomi.domain.chapter.model.Chapter>> {
        if (!fetchDetails && !fetchChapters) return manga to emptyList()
        if (fetchChapters) {
            chapterRepository.pendingDirectoryPhase(manga.id)?.let { pending ->
                syncChaptersWithSource.finishPhase(mangaRepository.getMangaById(manga.id), source, pending) {
                    requireNotNull(creatorArchiveRepository).observeDirectoryPhase(it)
                }
                return mangaRepository.getMangaById(manga.id) to
                    pending.addedIds.mapNotNull { chapterRepository.getChapterById(it) }
            }
        }
        val chapters = chapterRepository.getChapterByMangaId(manga.id)
        val update = tachiyomi.domain.source.service.SourceMangaUpdateService().await(
            source,
            manga,
            chapters,
            fetchDetails,
            fetchChapters,
        )
        val latestManga = mangaRepository.getMangaById(manga.id)
        if (requireFavorite && !latestManga.favorite) return latestManga to emptyList()
        val currentManga: Manga
        val newChapters: List<tachiyomi.domain.chapter.model.Chapter>
        if (fetchChapters) {
            val observedAt = Instant.now().toEpochMilli()
            val extension = sourceDateExtensionIdentityProvider(latestManga.source)
            val policy = if ((manualFetch || requireFavorite) && latestManga.favorite &&
                source is eu.kanade.tachiyomi.source.online.HttpSource
            ) {
                Injekt.get<mihon.domain.chapter.interactor.FilterChaptersForDownload>().snapshot(latestManga)
            } else {
                mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
            }
            val metadata = if (fetchDetails) {
                prepareSourceMetadata(latestManga, update.manga, manualFetch, coverCache, libraryPreferences)
            } else {
                MangaUpdate(latestManga.id, memo = update.manga.memo)
            }
            newChapters = syncChaptersWithSource.await(
                update.chapters, latestManga, source, manualFetch, fetchWindow,
                mangaMetadata = metadata, chapterListComplete = update.chapterListComplete,
                effects = tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                    source.id, source.toString(), latestManga.url, latestManga.title,
                    if (requireFavorite) {
                        "LIBRARY_UPDATE"
                    } else if (manualFetch) {
                        "DETAIL_REFRESH"
                    } else {
                        "BROWSE"
                    },
                    observedAt, extension.packageName, extension.version,
                    CreatorSourceWorkKey.stableUrl(
                        latestManga.url,
                        latestManga.title,
                        latestManga.author,
                        latestManga.artist,
                    ),
                    update.chapters.map {
                        tachiyomi.domain.chapter.service.DirectoryChapterDate(it.url, it.date_upload)
                    },
                    policy.enabled, policy.unreadOnly, creatorArchiveRepository != null,
                    libraryPreferences.disallowNonAsciiFilenames().get(),
                ),
                observe = { requireNotNull(creatorArchiveRepository).observeDirectoryPhase(it) },
            )
            currentManga = mangaRepository.getMangaById(manga.id)
        } else {
            if (fetchDetails) {
                check(
                    awaitUpdateFromSource(
                        latestManga,
                        update.manga,
                        manualFetch,
                        coverCache,
                        libraryPreferences,
                        downloadManager,
                    ),
                )
            } else {
                check(mangaRepository.update(MangaUpdate(manga.id, memo = update.manga.memo)))
            }
            currentManga = mangaRepository.getMangaById(manga.id)
            val knownByUrl = chapters.associateBy { it.url }
            chapterRepository.updateAll(
                update.chapters.mapNotNull { chapter ->
                    knownByUrl[chapter.url]?.takeIf { it.memo != chapter.memo }?.let {
                        tachiyomi.domain.chapter.model.ChapterUpdate(it.id, memo = chapter.memo)
                    }
                },
            )
            newChapters = emptyList()
        }
        return mangaRepository.getMangaById(manga.id) to newChapters
    }

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
        coverCache: CoverCache = Injekt.get(),
        libraryPreferences: LibraryPreferences = Injekt.get(),
        downloadManager: DownloadManager = Injekt.get(),
    ): Boolean {
        val metadata = prepareSourceMetadata(localManga, remoteManga, manualFetch, coverCache, libraryPreferences)
        val success = mangaRepository.update(metadata)
        metadata.title?.takeIf { success }?.let { downloadManager.renameManga(localManga, it) }
        return success
    }

    private fun prepareSourceMetadata(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
        coverCache: CoverCache,
        libraryPreferences: LibraryPreferences,
    ): MangaUpdate {
        val coverLastModified =
            when {
                // Never refresh covers if the url is empty to avoid "losing" existing covers
                remoteManga.thumbnail_url.isNullOrEmpty() -> null
                !manualFetch && localManga.thumbnailUrl == remoteManga.thumbnail_url -> null
                localManga.isLocal() -> Instant.now().toEpochMilli()
                localManga.hasCustomCover(coverCache) -> {
                    coverCache.deleteFromCache(localManga, false)
                    null
                }
                else -> {
                    coverCache.deleteFromCache(localManga, false)
                    Instant.now().toEpochMilli()
                }
            }

        return tachiyomi.domain.source.service.sourceMangaMetadata(
            localManga,
            remoteManga,
            libraryPreferences.updateMangaTitles().get(),
            coverLastModified,
        )
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        return mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
    }
}
