package mihon.desktop.domain

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.domain.error.AppError
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.finishDirectoryFiles
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
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.toSourceManga

/**
 * Checks a single library manga against its source for new chapters,
 * and inserts any new ones into the database.
 */
class LibraryUpdateChecker(
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: tachiyomi.domain.manga.repository.MangaRepository,
    private val creatorArchiveRepository: CreatorArchiveRepository? = null,
    private val sourceDateExtensionIdentityProvider: (Long) -> SourceDateExtensionIdentity = {
        SourceDateExtensionIdentity("unknown.extension", "unknown")
    },
    private val renameDirectoryChapter: suspend (
        tachiyomi.domain.chapter.service.ChapterDirectoryPhase,
        tachiyomi.domain.chapter.service.DirectoryFileChange,
    ) -> Unit = { _, _ -> },
    private val downloadPolicy: suspend (Manga) -> mihon.domain.chapter.interactor.DownloadNewChapterPolicy = {
        mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
    },
    private val downloadCommitted: suspend (Manga, List<Chapter>) -> Unit = { _, _ -> },
    private val markDuplicateAsRead: () -> Boolean = { false },
    private val disallowNonAsciiFilenames: () -> Boolean = { false },
    private val directoryCommit: suspend (Manga, tachiyomi.domain.chapter.service.ChapterDirectoryCommit) ->
    tachiyomi.domain.chapter.service.ChapterDirectoryResult = { _, request ->
        chapterRepository.syncDirectory(request)
    },
    private val fileReservation: suspend (Set<Long>, suspend () -> Unit) -> Unit = { _, operation -> operation() },
    private val confirmTaskReceipt: suspend (
        tachiyomi.domain.chapter.service.ChapterDirectoryPhase,
    ) -> Boolean = { false },
    private val libraryPreferences: () -> tachiyomi.domain.library.service.LibraryPreferences? = { null },
    private val fetchInterval: tachiyomi.domain.manga.interactor.FetchInterval? = null,
    private val hasCustomCover: (Long) -> Boolean = { false },
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) {

    /**
     * Fetches the chapter list from [source] and inserts chapters whose URL
     * is not yet stored in the DB for [manga].
     *
     * @return an [UpdateResult] with the count of newly added chapters.
     */
    suspend fun checkForUpdates(
        manga: Manga,
        source: Source,
        origin: String = "LIBRARY_UPDATE",
        taskReceipt: tachiyomi.domain.chapter.service.DirectoryTaskReceipt? = null,
    ): UpdateResult {
        chapterRepository.pendingDirectoryPhase(manga.id)?.let { pending ->
            if (taskReceipt != null && pending.effects.taskReceipt == null) {
                finishPhase(manga, pending)
                val current = requireNotNull(mangaRepository.getMangaById(manga.id))
                return checkForUpdates(current, source, origin, taskReceipt)
            }
            if (taskReceipt != null) {
                check(pending.effects.taskReceipt == taskReceipt) {
                    "Pending directory belongs to a different occurrence"
                }
            }
            finishPhase(manga, pending, holdReceipt = taskReceipt != null)
            return UpdateResult(
                pending.addedIds.size,
                pending.addedIds.mapNotNull {
                    chapterRepository.getChapterById(it)
                },
                receiptPhase = chapterRepository.pendingDirectoryPhase(manga.id),
            )
        }
        val knownChapters = chapterRepository.getChapterByMangaId(manga.id)
        val preferences = libraryPreferences()
        val fetchDetails = origin == "DETAIL_REFRESH" || preferences?.autoUpdateMetadata()?.get() == true
        val updateTitles = preferences?.updateMangaTitles()?.get() == true
        val remoteUpdate = when (
            val r = safeSourceCall {
                tachiyomi.domain.source.service.SourceMangaUpdateService().await(
                    source,
                    manga,
                    knownChapters,
                    fetchDetails,
                    true,
                )
            }
        ) {
            is SourceCallResult.Success -> r.value
            is SourceCallResult.Timeout -> return UpdateResult(newChapterCount = 0, sourceError = r.error)
            is SourceCallResult.Error -> return UpdateResult(newChapterCount = 0, sourceError = r.error)
        }
        val remoteChapters = remoteUpdate.chapters
        val acceptedTime = java.time.ZonedDateTime.now(clock)
        val observedAt = acceptedTime.toInstant().toEpochMilli()
        val current = mangaRepository.getMangaById(manga.id)
        val coverVersion = if (fetchDetails && !remoteUpdate.manga.thumbnail_url.isNullOrEmpty() &&
            !hasCustomCover(manga.id) &&
            (origin == "DETAIL_REFRESH" || current.thumbnailUrl != remoteUpdate.manga.thumbnail_url)
        ) {
            maxOf(observedAt, current.coverLastModified + 1)
        } else {
            null
        }
        val metadata = if (fetchDetails) {
            tachiyomi.domain.source.service.sourceMangaMetadata(current, remoteUpdate.manga, updateTitles, coverVersion)
        } else {
            null
        }
        val window = fetchInterval?.getWindow(acceptedTime)
        val extensionIdentity = sourceDateExtensionIdentityProvider(manga.source)
        val stableSourceUrl = CreatorSourceWorkKey.stableUrl(manga.url, manga.title, manga.author, manga.artist)
        val policy = if (manga.source ==
            0L
        ) {
            mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
        } else {
            downloadPolicy(manga)
        }
        val prepared = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(manga, remoteChapters) { chapter ->
            if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                source.prepareNewChapter(chapter, manga.toSourceManga())
            }
        }
        val committed = commitDirectory(
            manga,
            tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                manga.id,
                prepared,
                observedAt,
                mangaMemo = remoteUpdate.manga.memo,
                mangaMetadata = metadata,
                complete = remoteUpdate.chapterListComplete,
                allowEmpty = manga.source == 0L,
                markDuplicateAsRead = markDuplicateAsRead(),
                effects = tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                    manga.source, source.toString(), manga.url, manga.title, origin, observedAt,
                    extensionIdentity.packageName, extensionIdentity.version, stableSourceUrl,
                    remoteChapters.map {
                        tachiyomi.domain.chapter.service.DirectoryChapterDate(it.url, it.date_upload)
                    },
                    policy.enabled, policy.unreadOnly, creatorArchiveRepository != null,
                    disallowNonAsciiFilenames(), taskReceipt,
                    prediction = window?.let {
                        tachiyomi.domain.chapter.service.DirectoryPrediction(
                            acceptedTime.toString(),
                            it.first,
                            it.second,
                        )
                    },
                ),
            ),
        )
        val inserted = committed.added
        committed.phase?.let { finishPhase(manga, it, holdReceipt = taskReceipt != null) }

        return UpdateResult(
            newChapterCount = inserted.size,
            newChapters = inserted,
            receiptPhase = chapterRepository.pendingDirectoryPhase(manga.id),
        )
    }

    suspend fun commitDirectory(manga: Manga, request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit) =
        directoryCommit(manga, request)

    suspend fun finishPhase(
        manga: Manga,
        phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase,
        holdReceipt: Boolean = false,
    ) {
        val persisted = requireNotNull(mangaRepository.getMangaById(manga.id))
        check(
            persisted.source == phase.effects.sourceId && persisted.url == phase.effects.mangaUrl &&
                persisted.title == phase.currentTitle,
        ) { "Pending directory manga identity changed" }
        var pending = phase
        if (pending.files.isNotEmpty()) {
            fileReservation(pending.files.map { it.after.id }.toSet()) {
                pending = chapterRepository.finishDirectoryFiles(pending, renameDirectoryChapter)
            }
        }
        chapterRepository.finishDirectoryPhase(
            pending,
            renameDirectoryChapter,
            observe = { phase -> requireNotNull(creatorArchiveRepository).observeDirectoryPhase(phase) },
            download = { _, chapters -> downloadCommitted(persisted, chapters) },
        )
        pending = chapterRepository.pendingDirectoryPhase(manga.id) ?: pending
        if (pending.predictionPending) {
            val original = requireNotNull(pending.effects.prediction)
            val current = mangaRepository.getMangaById(manga.id)
            val prediction = requireNotNull(fetchInterval).toMangaUpdate(
                current,
                java.time.ZonedDateTime.parse(original.dateTime),
                original.windowLower to original.windowUpper,
            )
            check(mangaRepository.update(prediction)) { "The accepted chapter prediction could not be saved" }
            pending = pending.copy(predictionPending = false)
            chapterRepository.acknowledgeDirectoryPhase(pending)
        }
        if (phase.effects.taskReceipt != null && !holdReceipt) acknowledgeTaskReceipt(phase)
    }

    suspend fun recoverTaskReceipts(occurrence: String, unitIds: List<Long>) {
        for (id in unitIds) {
            val phase = chapterRepository.pendingDirectoryPhase(id) ?: continue
            val receipt = phase.effects.taskReceipt ?: continue
            check(receipt.occurrenceKey == occurrence && receipt.unitId == id) {
                "Cannot recover a directory receipt from a different occurrence"
            }
            finishPhase(Manga.create().copy(id = id), phase)
        }
    }

    suspend fun acknowledgeTaskReceipt(phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase) {
        val pending = requireNotNull(chapterRepository.pendingDirectoryPhase(phase.mangaId))
        check(pending.id == phase.id && pending.effects.taskReceipt == phase.effects.taskReceipt)
        check(pending.effectsComplete && pending.checkpointPending)
        check(confirmTaskReceipt(pending)) { "The original library checkpoint could not be confirmed" }
        chapterRepository.acknowledgeDirectoryPhase(pending.copy(checkpointPending = false))
    }

    data class UpdateResult(
        val newChapterCount: Int,
        val newChapters: List<tachiyomi.domain.chapter.model.Chapter> = emptyList(),
        val error: String? = null,
        val sourceError: AppError? = null,
        val receiptPhase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase? = null,
    )
}
