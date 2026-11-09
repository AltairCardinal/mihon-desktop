package mihon.desktop.reader

import kotlinx.serialization.Serializable
import mihon.domain.reader.session.ReaderPageId
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference

@Serializable
internal data class ReaderStartupEvent(
    val stage: String,
    val monotonicNanos: Long,
    val threadId: Long,
    val threadName: String,
    val threadCpuNanos: Long?,
    val chapterId: Long? = null,
    val pageIndex: Int? = null,
    val generation: Long? = null,
    val detail: Int? = null,
    val attemptGeneration: Long? = null,
)

@Serializable
internal data class ReaderStartupSnapshot(
    val capacity: Int,
    val dropped: Long,
    val events: List<ReaderStartupEvent>,
)

/** Explicit diagnostic only: allocated and MXBean-initialized during Test Mode preparation. */
internal class ReaderStartupObservation(private val capacity: Int = 1024) {
    private val lock = Any()
    private val events = ArrayDeque<ReaderStartupEvent>()
    private val threadBean = ManagementFactory.getThreadMXBean().also {
        if (it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled) it.currentThreadCpuTime
    }
    private var dropped = 0L

    init { require(capacity in 1..4096) }

    fun record(
        stage: String,
        page: ReaderPageId? = null,
        generation: Long? = null,
        detail: Int? = null,
        chapterId: Long? = null,
        attemptGeneration: Long? = null,
    ) {
        val thread = Thread.currentThread()
        val event = ReaderStartupEvent(
            stage, System.nanoTime(), thread.id, thread.name,
            if (threadBean.isCurrentThreadCpuTimeSupported && threadBean.isThreadCpuTimeEnabled) {
                threadBean.currentThreadCpuTime.takeIf { it >= 0 }
            } else {
                null
            },
            page?.chapterId?.value ?: chapterId, page?.sourcePageIndex, generation, detail, attemptGeneration,
        )
        synchronized(lock) {
            if (events.size == capacity) {
                dropped++ // Preserve DOWN/open and the first causal chain; report any truncated tail.
            } else {
                events.addLast(event)
            }
        }
    }

    fun snapshot(): ReaderStartupSnapshot = synchronized(lock) {
        ReaderStartupSnapshot(capacity, dropped, events.toList())
    }
}

/** Default-off observation never reads clocks, allocates events, or writes files. */
internal object ReaderStartupTrace {
    private val active = AtomicReference<ReaderStartupObservation?>()
    fun install(observation: ReaderStartupObservation?) { active.set(observation) }
    fun clear(observation: ReaderStartupObservation?) { active.compareAndSet(observation, null) }
    fun markChapter(stage: String, chapterId: Long, detail: Int? = null, generation: Long? = null) {
        active.get()?.record(stage, generation = generation, detail = detail, chapterId = chapterId)
    }
    fun mark(
        stage: String,
        page: ReaderPageId? = null,
        generation: Long? = null,
        detail: Int? = null,
        attemptGeneration: Long? = null,
    ) {
        active.get()?.record(stage, page, generation, detail, attemptGeneration = attemptGeneration)
    }
}
