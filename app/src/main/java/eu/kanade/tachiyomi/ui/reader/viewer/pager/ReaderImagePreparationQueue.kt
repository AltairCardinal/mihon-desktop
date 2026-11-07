package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/** Main-owned normal-image admission, independent of pairing analysis and pixel/reading permission. */
internal class ReaderImagePreparationQueue(
    private val scope: CoroutineScope,
    private val capacity: Int = 2,
    private val neighbourCapacity: Int = 2,
) {
    private data class Request(val page: ReaderPage, val result: CompletableDeferred<Lease>, var lease: Lease? = null)
    private var priority = emptyList<ReaderPage>()
    private var current = emptySet<ReaderPage>()
    private val started = mutableSetOf<ReaderPage>()
    private val pending = mutableListOf<Request>()
    private val active = mutableMapOf<Lease, ReaderPage>()
    private var scheduled = false
    private var closed = false

    fun updateWindow(pages: List<ReaderPage>, currentPages: Set<ReaderPage>, prepared: Set<ReaderPage> = emptySet()) {
        if (closed) return
        priority = pages.distinct()
        current = currentPages
        started.retainAll(priority.toSet())
        started += prepared.intersect(current)
        schedule()
    }

    fun contains(page: ReaderPage): Boolean = page in priority

    suspend fun acquire(page: ReaderPage): Lease {
        if (closed) throw CancellationException("Reader image preparation is closed")
        val request = Request(page, CompletableDeferred())
        pending += request
        schedule()
        try {
            return request.result.await()
        } catch (failure: Throwable) {
            pending.remove(request)
            request.lease?.close()
            throw failure
        }
    }

    fun close() {
        closed = true
        pending.forEach { it.result.cancel() }
        pending.clear()
        active.keys.toList().forEach(Lease::close)
        priority = emptyList()
    }

    private fun schedule() {
        if (scheduled || closed) return
        scheduled = true
        scope.launch {
            // ViewPager may construct the neighbour before the selected holder. Admit from the
            // published logical window after all synchronous mount registrations, never draw order.
            yield()
            scheduled = false
            while (active.size < capacity) {
                val request = pending.filter { it.page in priority }
                    .minByOrNull { priority.indexOf(it.page) } ?: break
                if (request.page !in current &&
                    (!started.containsAll(current) || active.values.count { it !in current } >= neighbourCapacity)
                ) {
                    break
                }
                pending.remove(request)
                started += request.page
                lateinit var lease: Lease
                lease = Lease {
                    scope.launch {
                        active.remove(lease)
                        schedule()
                    }
                }
                active[lease] = request.page
                request.lease = lease
                request.result.complete(lease)
            }
        }
    }

    /** Cancellation requests release; already accepted physical work must really finish first. */
    class Lease(private val released: () -> Unit) {
        private var closed = false
        private var work = 0
        private var returned = false

        fun retainWork(): () -> Unit {
            synchronized(this) {
                // Later zoom tiles remain observed by the original foreground accounting, but
                // cannot reopen a completed initial-image admission.
                if (returned) return {}
                work++
            }
            val finished = AtomicBoolean()
            return {
                if (finished.compareAndSet(false, true)) {
                    val release = synchronized(this) {
                        work--
                        shouldReturn()
                    }
                    if (release) released()
                }
            }
        }

        fun close() {
            val release = synchronized(this) {
                closed = true
                shouldReturn()
            }
            if (release) released()
        }

        private fun shouldReturn(): Boolean = if (closed && work == 0 && !returned) {
            returned = true
            true
        } else {
            false
        }
    }
}
