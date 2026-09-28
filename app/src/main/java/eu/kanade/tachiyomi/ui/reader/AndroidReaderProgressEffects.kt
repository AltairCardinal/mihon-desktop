package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Immutable metadata needed after the progress transaction commits. */
internal data class ReaderProgressCompletionPlan(
    val manga: Manga,
    val completedChapter: Chapter,
    val markDuplicates: Boolean,
    val deleteCandidate: Chapter?,
    val updateTracking: Boolean,
)

internal class AndroidReaderProgressEffects(
    private val application: Application,
    private val trackChapter: TrackChapter,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadManager: DownloadManager,
    private val trackingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    suspend fun onCommitted(command: AcceptedReaderProgress) {
        val plan = command.completion ?: return
        if (plan.updateTracking) {
            trackingScope.launch {
                try {
                    trackChapter.await(
                        application,
                        plan.manga.id,
                        plan.completedChapter.chapterNumber,
                    )
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Reader tracking update failed" }
                }
            }
        }
        var failure: Throwable? = null
        if (plan.markDuplicates) {
            try {
                val updates = duplicateChapterReadUpdates(
                    chapters = getChaptersByMangaId.await(plan.manga.id, applyScanlatorFilter = false),
                    completedChapter = plan.completedChapter,
                    enabled = true,
                )
                if (updates.isNotEmpty()) updateChapter.awaitAllOrThrow(updates)
            } catch (error: Throwable) {
                failure = error
                logcat(LogPriority.ERROR, error) { "Reader duplicate chapter update failed" }
            }
        }
        plan.deleteCandidate?.let { chapter ->
            try {
                val persistedRead = chapter.id == plan.completedChapter.id ||
                    getChaptersByMangaId.awaitOrThrow(plan.manga.id, applyScanlatorFilter = false)
                        .any { it.id == chapter.id && it.read }
                if (persistedRead) downloadManager.enqueueChaptersToDelete(listOf(chapter), plan.manga)
            } catch (error: Throwable) {
                failure = failure ?: error
                logcat(LogPriority.ERROR, error) { "Reader download deletion enqueue failed" }
            }
        }
        failure?.let { throw it }
    }
}
