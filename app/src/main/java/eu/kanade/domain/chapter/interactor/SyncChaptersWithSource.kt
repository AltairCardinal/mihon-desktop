package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.finishDirectoryFiles
import tachiyomi.domain.chapter.service.finishDirectoryPhase
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.time.ZonedDateTime

class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
) {

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
        mangaMetadata: tachiyomi.domain.manga.model.MangaUpdate? = null,
        chapterListComplete: Boolean = true,
        effects: tachiyomi.domain.chapter.service.ChapterDirectoryEffects? = null,
        observe: suspend (tachiyomi.domain.chapter.service.ChapterDirectoryPhase) -> Unit = {
            check(!it.observationPending) { "Original directory observation consumer is required" }
        },
    ): List<Chapter> {
        chapterRepository.pendingDirectoryPhase(manga.id)?.let { pending ->
            finishPhase(manga, source, pending, observe)
            return pending.addedIds.mapNotNull { chapterRepository.getChapterById(it) }
        }
        if (rawSourceChapters.isEmpty() && !source.isLocal()) throw NoChaptersException()
        val now = ZonedDateTime.now()
        val prepared = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(
            manga,
            rawSourceChapters,
        ) { chapter ->
            if (source is HttpSource) source.prepareNewChapter(chapter, manga.toSManga())
        }
        val request = tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
            mangaId = manga.id,
            source = prepared,
            now = now.toInstant().toEpochMilli(),
            markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
                .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW),
            allowEmpty = source.isLocal(),
            complete = chapterListComplete,
            mangaMetadata = mangaMetadata,
            effects = effects ?: tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                source.id,
                source.toString(),
                manga.url,
                manga.title,
                "ANDROID_DIRECT",
                now.toInstant().toEpochMilli(),
                disallowNonAsciiFilenames = libraryPreferences.disallowNonAsciiFilenames().get(),
            ),
        )
        val latestManga = updateManga.mangaForDirectory(manga.id)
        val stored = chapterRepository.getChapterByMangaId(manga.id)
        val plan = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.create(
            stored,
            prepared,
            request.now,
            request.markDuplicateAsRead,
            emptySet(),
        )
        val guarded = plan.affectedDownloadIds(stored, mangaMetadata?.title?.let { it != latestManga.title } == true)
        val result = downloadManager.withDirectoryChanges(guarded) {
            val committed = chapterRepository.syncDirectory(request.copy(guardedChapterIds = guarded))
            committed.phase?.let { phase ->
                chapterRepository.finishDirectoryFiles(phase) { p, change ->
                    downloadManager.renameDirectoryChapter(source, latestManga, p, change)
                }
            }
            committed.copy(phase = chapterRepository.pendingDirectoryPhase(manga.id))
        }
        result.phase?.let { finishPhase(manga, source, it, observe) }
        if (result.plan.changed || manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
            updateManga.awaitUpdateFetchInterval(manga, now, fetchWindow)
        }
        return result.added
    }

    suspend fun finishPhase(
        manga: Manga,
        source: Source,
        phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase,
        observe: suspend (tachiyomi.domain.chapter.service.ChapterDirectoryPhase) -> Unit,
    ) {
        val latest = updateManga.mangaForDirectory(manga.id)
        check(
            latest.id == phase.mangaId && source.id == phase.effects.sourceId &&
                latest.source == phase.effects.sourceId && latest.url == phase.effects.mangaUrl &&
                latest.title == phase.currentTitle,
        ) { "Pending directory manga identity changed" }
        var pending = phase
        if (pending.files.isNotEmpty()) {
            downloadManager.withDirectoryChanges(pending.files.map { it.after.id }.toSet()) {
                pending = chapterRepository.finishDirectoryFiles(pending) { p, change ->
                    downloadManager.renameDirectoryChapter(source, latest, p, change)
                }
            }
        }
        chapterRepository.finishDirectoryPhase(
            pending,
            rename = { p, change -> downloadManager.renameDirectoryChapter(source, manga, p, change) },
            observe = observe,
            download = { _, chapters ->
                check(
                    downloadManager.downloadDirectoryChapters(
                        latest,
                        chapters,
                        autoStart = phase.effects.origin != "LIBRARY_UPDATE",
                    ),
                ) { "Directory downloads were not durably accepted" }
            },
        )
    }
}
