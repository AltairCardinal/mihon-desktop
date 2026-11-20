package mihon.desktop.download

import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.filterReadDownloadDeletion
import tachiyomi.domain.download.service.removeBookmarkedForReader
import tachiyomi.domain.manga.repository.MangaRepository

/** Ordinary read cleanup uses the same finite artifact/producer boundary as confirmed deletion. */
class DesktopReadDownloadDeletion(
    private val preferences: DownloadPreferences,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val getCategories: GetCategories,
    private val manager: DesktopDownloadManager,
    private val provider: DesktopDownloadProvider,
    private val identities: DesktopDownloadIdentityResolver,
) {
    internal data class ReaderCapture(
        val chapter: Chapter,
        val files: CapturedDownloadFiles,
        val generations: Map<Long, CapturedDownloadGeneration>,
        val readerCleanup: Boolean,
    )

    internal suspend fun captureReader(chapter: Chapter, readerCleanup: Boolean = false): ReaderCapture? {
        val generations = manager.captureMigrationGenerations(setOf(chapter.id))
        val files = capture(listOf(chapter), readerCleanup)
        if (files.pendingArtifacts.isEmpty()) return null
        return ReaderCapture(chapter, files, generations, readerCleanup)
    }

    internal suspend fun deleteReader(capture: ReaderCapture) {
        val current = chapterRepository.getChapterById(capture.chapter.id) ?: return
        val manga = mangaRepository.getMangaById(current.mangaId)
        if (current.mangaId != capture.chapter.mangaId || current.url != capture.chapter.url || !current.read) return
        if (filterReadDownloadDeletion(
                listOf(current),
                getCategories.await(manga.id).map { it.id },
                preferences.removeExcludeCategories().get(),
                if (capture.readerCleanup) {
                    preferences.removeBookmarkedForReader(current.mangaId)
                } else {
                    preferences.removeBookmarkedChapters().get()
                },
            ).isEmpty()
        ) {
            return
        }
        val reservation = provider.artifactUsage.reserveDeletion(capture.files.pendingArtifacts) ?: return
        reservation.use {
            if (manager.cancelCapturedDownloadAttempts(capture.files.pendingAttempts).isNotEmpty()) return
            if (capture.generations.values.any { it.replaced }) return
            manager.withMigrationArtifacts(
                setOf(current.id),
                capture.files.pendingArtifacts.toList(),
                capture.generations,
            ) {
                check(provider.deleteCapturedDownloadArtifacts(capture.files.pendingArtifacts).isEmpty()) {
                    "Unable to delete read downloads"
                }
            }
        }
    }
    suspend fun prepareManualRead(chapters: List<Chapter>): suspend () -> Unit {
        val captured = if (preferences.removeAfterMarkedAsRead().get()) {
            chapters.mapNotNull { chapter -> captureReader(chapter.copy(read = true)) }
        } else {
            emptyList()
        }
        return { captured.forEach { deleteReader(it) } }
    }

    suspend fun chapter(id: Long): Chapter? = chapterRepository.getChapterById(id)

    private suspend fun capture(chapters: List<Chapter>, readerCleanup: Boolean): CapturedDownloadFiles {
        val artifacts = mutableSetOf<java.io.File>()
        val attempts = mutableListOf<CapturedDownloadAttempt>()
        val queuedAliases = mutableMapOf<Long, Set<java.io.File>>()
        chapters.groupBy { it.mangaId }.forEach { (mangaId, group) ->
            val manga = mangaRepository.getMangaById(mangaId)
            val eligible = filterReadDownloadDeletion(
                group,
                getCategories.await(mangaId).map { it.id },
                preferences.removeExcludeCategories().get(),
                if (readerCleanup) {
                    preferences.removeBookmarkedForReader(mangaId)
                } else {
                    preferences.removeBookmarkedChapters().get()
                },
            )
            eligible.forEach { chapter ->
                artifacts += provider.chapterDownloadArtifacts(manga.source, identities.resolve(manga, chapter))
                manager.captureDownloadAttempts(listOf(chapter.id)).forEach { target ->
                    val identity = target.item.downloadIdentity ?: identities.resolve(manga, chapter)
                    val captured = target.copy(item = target.item.copy(downloadIdentity = identity))
                    attempts += captured
                    val paths = provider.chapterDownloadArtifacts(target.item.sourceId, identity).toSet()
                    queuedAliases[chapter.id] = paths
                    artifacts += paths
                }
            }
        }
        return CapturedDownloadFiles(artifacts.filterTo(mutableSetOf()) { it.exists() }, attempts, queuedAliases)
    }
}
