package tachiyomi.domain.track.interactor

import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.track.model.Track

/** The existing Android enhanced-binding progress decision; remote I/O remains a platform port. */
class SyncEnhancedChapterProgress(
    private val updateChapter: UpdateChapter,
    private val insertTrack: InsertTrack,
    private val getChapters: GetChaptersByMangaId,
) {
    suspend fun await(mangaId: Long, remoteTrack: Track, updateRemote: suspend (Track) -> Unit) {
        val sorted = getChapters.await(mangaId).sortedBy { it.chapterNumber }.filter { it.isRecognizedNumber }
        val selected = sorted.filter { it.chapterNumber <= remoteTrack.lastChapterRead && !it.read }
        val continuous = sorted.takeWhile { it.read }.lastOrNull()?.chapterNumber ?: 0.0
        val updated = remoteTrack.copy(lastChapterRead = maxOf(remoteTrack.lastChapterRead, continuous))
        updateRemote(updated)
        val current = getChapters.await(mangaId).associateBy { it.id }
        val updates = selected.mapNotNull { chapter ->
            current[chapter.id]?.takeIf { it.mangaId == chapter.mangaId && it.url == chapter.url && !it.read }
                ?.let { ChapterUpdate(id = it.id, read = true) }
        }
        updateChapter.awaitAll(updates)
        insertTrack.await(updated)
    }
}
