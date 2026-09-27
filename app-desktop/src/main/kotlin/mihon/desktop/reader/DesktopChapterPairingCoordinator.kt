package mihon.desktop.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import mihon.desktop.DesktopRuntimeService
import mihon.domain.reader.ChapterPairingRepository

/** Orders accepted operations per chapter, independently of reader screen disposal. */
class DesktopChapterPairingCoordinator(
    private val repository: ChapterPairingRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DesktopRuntimeService {
    private val lock = Any()
    private val tails = mutableMapOf<Long, Deferred<*>>()
    private var stopping = false

    fun <T> submit(chapterId: Long, operation: suspend ChapterPairingRepository.() -> T): Deferred<T> =
        synchronized(lock) {
            check(!stopping) { "Chapter pairing persistence is closing" }
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

    override fun start() = Unit

    override fun stop() {
        synchronized(lock) { stopping = true }
    }

    override suspend fun awaitStopped() {
        synchronized(lock) { tails.values.toList() }.joinAll()
        scope.cancel()
    }
}
