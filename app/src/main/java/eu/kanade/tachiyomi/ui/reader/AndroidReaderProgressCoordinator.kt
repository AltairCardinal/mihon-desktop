package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.reader.interactor.ReadingProgressSession
import tachiyomi.domain.reader.model.ReadingProgressEvent

/** A settled viewport's captured transaction inputs. No reader page or Activity may enter this queue. */
internal data class AcceptedReaderProgress(
    val event: ReadingProgressEvent,
    val session: ReadingProgressSession,
    val completion: ReaderProgressCompletionPlan? = null,
)

internal data class ReaderProgressWriteResult(
    val persisted: Boolean,
    val storageError: Throwable? = null,
    val completionError: Throwable? = null,
) {
    val isPersisted: Boolean get() = persisted
}

internal data class ReaderProgressSubmission(
    val receipt: Deferred<ReaderProgressWriteResult>?,
) {
    val accepted: Boolean get() = receipt != null
}

/** Application-owned FIFO for all reader instances of the same manga. */
class AndroidReaderProgressCoordinator internal constructor(
    private val onCommitted: suspend (AcceptedReaderProgress) -> Unit,
    private val onClosed: suspend (Long) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val tails = mutableMapOf<Long, Job>()

    /** Retain only the latest failed transaction per manga for the next-open barrier. */
    private val failures = mutableMapOf<Long, Throwable>()

    internal fun openReader(mangaId: Long): ReaderHandle = synchronized(lock) {
        ReaderHandle(mangaId, tails[mangaId], listOfNotNull(failures.remove(mangaId)))
    }

    suspend fun awaitAccepted(mangaId: Long) {
        synchronized(lock) { tails[mangaId] }?.join()
    }

    internal inner class ReaderHandle internal constructor(
        val mangaId: Long,
        private val previous: Job?,
        private val earlierFailures: List<Throwable>,
    ) {
        private var closed = false
        val isOpen: Boolean get() = synchronized(lock) { !closed }

        /** The barrier is captured at open time, so later submissions cannot prolong initialization. */
        suspend fun awaitPrevious(): List<Throwable> {
            previous?.join()
            return synchronized(lock) { earlierFailures + listOfNotNull(failures.remove(mangaId)) }
        }

        fun submit(command: AcceptedReaderProgress): ReaderProgressSubmission = synchronized(lock) {
            if (closed) return@synchronized ReaderProgressSubmission(null)
            val predecessor = tails[mangaId]
            val task = scope.async(start = CoroutineStart.LAZY) {
                predecessor?.join()
                val persisted = try {
                    command.session.await(command.event)
                    true
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Reader progress transaction failed" }
                    synchronized(lock) { failures[mangaId] = error }
                    return@async ReaderProgressWriteResult(false, storageError = error)
                }
                val completionError = try {
                    if (command.completion != null) onCommitted(command)
                    null
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Reader completion effect failed" }
                    error
                }
                ReaderProgressWriteResult(persisted, completionError = completionError)
            }
            installTail(mangaId, task)
            ReaderProgressSubmission(task)
        }

        /** Closing is synchronous and idempotent; its cleanup marker follows all accepted writes. */
        fun close(onAfterAccepted: suspend () -> Unit = {}) = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            val predecessor = tails[mangaId]
            val marker = scope.async(start = CoroutineStart.LAZY) {
                predecessor?.join()
                try {
                    onAfterAccepted()
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Reader close settlement failed" }
                }
                try {
                    onClosed(mangaId)
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Reader close cleanup failed" }
                }
            }
            installTail(mangaId, marker)
        }
    }

    private fun installTail(mangaId: Long, task: Job) {
        tails[mangaId] = task
        task.invokeOnCompletion {
            synchronized(lock) {
                if (tails[mangaId] === task) tails.remove(mangaId)
            }
        }
        task.start()
    }
}
