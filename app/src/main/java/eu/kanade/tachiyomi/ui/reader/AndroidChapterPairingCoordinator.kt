package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import mihon.domain.reader.ChapterPairingRepository

/** Application-owned work: leaving a reader Activity never cancels an accepted chapter write. */
class AndroidChapterPairingCoordinator(
    private val repository: ChapterPairingRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val tails = mutableMapOf<Long, Deferred<*>>()

    fun <T> submit(chapterId: Long, operation: suspend ChapterPairingRepository.() -> T): Deferred<T> =
        synchronized(lock) {
            val predecessor = tails[chapterId]
            val task = scope.async(start = CoroutineStart.LAZY) {
                predecessor?.join()
                repository.operation()
            }
            tails[chapterId] = task
            task.invokeOnCompletion {
                synchronized(lock) {
                    if (tails[chapterId] === task) tails.remove(chapterId)
                }
            }
            task.start()
            task
        }

    suspend fun awaitAcceptedOperations() {
        synchronized(lock) { tails.values.toList() }.joinAll()
    }
}
