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
) {

    /**
     * Fetches the chapter list from [source] and inserts chapters whose URL
     * is not yet stored in the DB for [manga].
     *
     * @return an [UpdateResult] with the count of newly added chapters.
     */
    suspend fun checkForUpdates(manga: Manga, source: Source, origin: String = "LIBRARY_UPDATE"): UpdateResult {
        chapterRepository.pendingDirectoryPhase(manga.id)?.let { pending ->
            finishPhase(manga, pending)
            return UpdateResult(
                pending.addedIds.size,
                pending.addedIds.mapNotNull {
                    chapterRepository.getChapterById(it)
                },
            )
        }
        val knownChapters = chapterRepository.getChapterByMangaId(manga.id)
        val remoteUpdate = when (
            val r = safeSourceCall {
                tachiyomi.domain.source.service.SourceMangaUpdateService().await(
                    source,
                    manga,
                    knownChapters,
                    false,
                    true,
                )
            }
        ) {
            is SourceCallResult.Success -> r.value
            is SourceCallResult.Timeout -> return UpdateResult(newChapterCount = 0, sourceError = r.error)
            is SourceCallResult.Error -> return UpdateResult(newChapterCount = 0, sourceError = r.error)
        }
        val remoteChapters = remoteUpdate.chapters
        val observedAt = System.currentTimeMillis()
        val extensionIdentity = sourceDateExtensionIdentityProvider(manga.source)
        val stableSourceUrl = CreatorSourceWorkKey.stableUrl(manga.url, manga.title, manga.author, manga.artist)
        val policy = downloadPolicy(manga)
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
                    disallowNonAsciiFilenames(),
                ),
            ),
        )
        val inserted = committed.added
        committed.phase?.let { finishPhase(manga, it) }

        return UpdateResult(newChapterCount = inserted.size, newChapters = inserted)
    }

    suspend fun commitDirectory(manga: Manga, request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit) =
        directoryCommit(manga, request)

    suspend fun finishPhase(manga: Manga, phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase) {
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
    }

    data class UpdateResult(
        val newChapterCount: Int,
        val newChapters: List<tachiyomi.domain.chapter.model.Chapter> = emptyList(),
        val error: String? = null,
        val sourceError: AppError? = null,
    )
}
