package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Diagnostic state only. Configuration is read before normal UI navigation, never at reader hotspots. */
object ReaderOpenLatencyProbe {
    data class Event(val event: String, val monotonicNanos: Long, val chapterId: Long, val page: Int, val request: Long)

    @Volatile var enabled = false
    private var targetManga = -1L
    private var targetChapter = -1L
    private var configuredPage = 0
    private val names = arrayOf(
        "MAIN_TOUCH_UP", "ACTIVITY_CREATE", "OPEN_READER_INTENT", "INIT_IO_ENTER", "MANGA_READY",
        "SOURCE_READY", "CHAPTER_LIST_BEGIN", "CHAPTER_LIST_READY", "CHAPTER_LOAD_BEGIN", "CHAPTER_IO_ENTER",
        "DOWNLOAD_CHECK_BEGIN", "DOWNLOAD_CHECK_END", "GET_PAGES_BEGIN", "CHAPTER_PATH_BEGIN", "CHAPTER_PATH_END",
        "ARCHIVE_OPEN_BEGIN", "ARCHIVE_OPEN_END", "ARCHIVE_LIST_BEGIN", "ARCHIVE_LIST_END", "PAGE_LIST_READY",
        "OPEN_PAGE", "ENCODED_READ_COMPLETE", "DECODE_SUBMIT", "FIRST_DRAW", "FRAME_COMMIT",
        "MAIN_TOUCH_DOWN", "GET_PAGES_END", "HEADER_FACTS_BEGIN", "HEADER_FACTS_END",
        "TARGET_HEADER_BEGIN", "TARGET_HEADER_END",
        "ACTIVATION_UI_ENTER", "PAIRING_OWNER_READY", "READING_GATE_READY", "CHAPTER_STATE_PUBLISH_BEGIN",
        "ACTIVITY_CHAPTERS_RECEIVED", "TARGET_IMAGE_ENTER", "TARGET_HEADER_DISPATCH",
        "VIEWER_CHAPTERS_APPLY_ENTER", "PAGER_ADAPTER_READY", "TARGET_HOLDER_CREATED",
        "TARGET_LOAD_ENTER", "TARGET_STATUS_COLLECT", "TARGET_READY_ENTER",
        "UNIT_DRAWN",
    )
    private class TargetBinding(chapter: Any, pages: Any, targetPageObject: Any) {
        val chapter = WeakReference(chapter)
        val pages = WeakReference(pages)
        val targetPageObject = WeakReference(targetPageObject)

        @Volatile var viewerChapters: WeakReference<Any>? = null

        fun matches(chapter: Any, pages: Any?): Boolean = pages != null &&
            this.chapter.get() === chapter && this.pages.get() === pages && targetPageObject.get() != null
    }
    private class Session(val request: Long, val chapter: Long, val page: Int) {
        val times = AtomicLongArray(names.size)
        val pagerDispatch = AtomicInteger()

        @Volatile var exported = false

        @Volatile var target: TargetBinding? = null
    }
    private val requestSequence = AtomicLong()
    private var nextSession = 0
    private var sessions = emptyArray<Session>()

    @Volatile private var active: Session? = null

    @Volatile private var lastTouch = 0L

    @Volatile private var lastDown = 0L
    private var label = "unset"
    private var output: File? = null
    private val writer by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "reader-latency-export").apply { isDaemon = true } }
    }

    fun loadConfiguration(context: Context) {
        val root = context.getExternalFilesDir("reader-open-real") ?: return
        runCatching {
            val config = JSONObject(File(root, "config.json").readText(Charsets.UTF_8))
            configure(
                config.getBoolean("enabled"),
                config.getLong("mangaId"),
                config.getLong("chapterId"),
                config.getString("label"),
                config.optInt("targetPage", 0),
            )
            output = File(root, "sessions").apply { mkdirs() }
        }.onFailure { configure(false, -1, -1, "missing") }
    }

    /** Only called on main before navigation; allocates the entire bounded measurement budget. */
    fun configure(on: Boolean, manga: Long, chapter: Long, runLabel: String, targetPage: Int = 0) {
        require(targetPage >= 0)
        enabled = false
        active = null
        targetManga = manga
        targetChapter = chapter
        configuredPage = targetPage
        nextSession = 0
        lastTouch = 0
        lastDown = 0
        label = runLabel
        output = null
        sessions =
            if (on) Array(32) { Session(requestSequence.incrementAndGet(), chapter, configuredPage) } else emptyArray()
        enabled = on
    }

    fun noteMainTouchDown() {
        if (enabled) lastDown = System.nanoTime()
    }

    fun noteMainTouchUp() {
        if (enabled) lastTouch = System.nanoTime()
    }

    fun begin() {
        if (enabled) startChapter(targetManga, targetChapter)
    }

    /** Activity creation is serialized on main; this entry does not allocate. */
    fun startChapter(manga: Long, chapter: Long): Long {
        if (!enabled || manga != targetManga || chapter != targetChapter) return 0
        if (nextSession == sessions.size) {
            active = null
            return 0
        }
        val session = sessions[nextSession++]
        active = session
        if (lastTouch > 0) session.times.set(0, lastTouch)
        session.times.set(1, System.nanoTime())
        if (lastDown > 0) session.times.set(names.indexOf("MAIN_TOUCH_DOWN"), lastDown)
        return session.request
    }

    fun requestFor(chapter: Long, page: Int = -1): Long {
        if (!enabled) return 0
        val session = active ?: return 0
        if (page != -1 && page != session.page) return 0
        return if (chapter == session.chapter) session.request else 0
    }

    internal fun targetPageFor(request: Long): Int? =
        if (enabled) active?.takeIf { it.request == request && !it.exported }?.page else null

    /** Initial-load sidecar only; weak identities never retain loader or image resources. */
    internal fun bindPageList(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any,
        targetPageObject: Any,
    ): Boolean {
        val session = activeSession(request, chapterId) ?: return false
        return synchronized(session) {
            if (activeSession(request, chapterId) !== session) return@synchronized false
            val existing = session.target
            if (existing !=
                null
            ) {
                return@synchronized existing.matches(chapter, pages) &&
                    existing.targetPageObject.get() === targetPageObject
            }
            session.target = TargetBinding(chapter, pages, targetPageObject)
            true
        }
    }

    internal fun bindViewerChapters(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        viewerChapters: Any,
    ): Boolean {
        val session = activeSession(request, chapterId) ?: return false
        return synchronized(session) {
            val target = session.target ?: return@synchronized false
            if (activeSession(request, chapterId) !== session || !target.matches(chapter, pages)) {
                return@synchronized false
            }
            val existing = target.viewerChapters
            if (existing != null) return@synchronized existing.get() === viewerChapters
            target.viewerChapters = WeakReference(viewerChapters)
            true
        }
    }

    /** Resolve the registered request, never the current global request for an old holder. */
    internal fun requestForTargetPage(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        page: Any,
        viewerChapters: Any?,
    ): Long {
        if (!enabled || request == 0L || pages == null || viewerChapters == null) return 0
        val session = sessions.firstOrNull { it.request == request && it.chapter == chapterId } ?: return 0
        val target = session.target ?: return 0
        return if (!session.exported && target.matches(chapter, pages) && target.targetPageObject.get() === page &&
            target.viewerChapters?.get() === viewerChapters
        ) {
            session.request
        } else {
            0
        }
    }

    internal fun recordForPageList(
        event: String,
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
    ): Boolean {
        val session = activeSession(request, chapterId) ?: return false
        if (session.target?.matches(chapter, pages) != true) return false
        return record(event, chapter = chapterId, request = request)
    }

    internal fun recordForViewerChapters(
        event: String,
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        viewerChapters: Any,
    ): Boolean {
        val session = activeSession(request, chapterId) ?: return false
        val target = session.target ?: return false
        if (!target.matches(chapter, pages) || target.viewerChapters?.get() !== viewerChapters) return false
        return record(event, chapter = chapterId, request = request)
    }

    private fun activeSession(request: Long, chapterId: Long): Session? {
        if (!enabled || request == 0L) return null
        return active?.takeIf { it.request == request && it.chapter == chapterId && !it.exported }
    }

    internal fun notePagerDispatch(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        viewerChapters: Any,
        awaitedIdle: Boolean,
    ): Boolean {
        val session = activeSession(request, chapterId) ?: return false
        val target = session.target ?: return false
        if (!target.matches(chapter, pages) || target.viewerChapters?.get() !== viewerChapters) return false
        return session.pagerDispatch.compareAndSet(0, if (awaitedIdle) 2 else 1)
    }

    internal fun capturedPagerAwaitedIdle(request: Long): Boolean? {
        if (!enabled) return null
        return when (sessions.firstOrNull { it.request == request }?.pagerDispatch?.get()) {
            1 -> false
            2 -> true
            else -> null
        }
    }

    /** Bounded attribution only: the captured request must still own the pre-header startup window. */
    internal fun isStartupWindow(request: Long, chapter: Long): Boolean {
        val session = activeSession(request, chapter) ?: return false
        return session.target != null && session.times.get(names.indexOf("TARGET_HEADER_BEGIN")) == 0L
    }

    /** Synchronous sections only; callers resolve the registered target identity before entering. */
    internal inline fun <T> traceStartup(name: String, request: Long, chapter: Long, block: () -> T): T {
        if (!isStartupWindow(request, chapter)) return block()
        android.os.Trace.beginSection(name)
        try {
            return block()
        } finally {
            android.os.Trace.endSection()
        }
    }

    /** One CAS into a preallocated timestamp slot; no lock, allocation, scheduling or I/O. */
    fun record(
        event: String,
        page: Int = -1,
        chapter: Long = active?.chapter ?: -1,
        request: Long = active?.request ?: 0,
    ): Boolean {
        if (!enabled || request == 0L) return false
        val session = active ?: return false
        if (page != -1 && page != session.page) return false
        if (chapter != session.chapter || request != session.request) return false
        return recordSlot(session, event)
    }

    private fun recordSlot(session: Session, event: String): Boolean {
        val slot = names.indexOf(event)
        if (slot < 0 || session.times.get(slot) != 0L) return false
        return session.times.compareAndSet(slot, 0, System.nanoTime())
    }

    fun contains(event: String): Boolean = capturedEvents().any { it.event == event }

    fun capturedEvents(): List<Event> = active?.let(::snapshot).orEmpty()

    private fun snapshot(session: Session): List<Event> = names.mapIndexedNotNull { index, name ->
        session.times.get(index).takeIf {
            it != 0L
        }?.let { Event(name, it, session.chapter, session.page, session.request) }
    }.sortedBy { it.monotonicNanos }

    /** Export only after reader onStop; force-killing before leaving intentionally loses the sample. */
    fun exportAfterReaderStopped(request: Long) {
        if (!enabled || request == 0L) return
        val directory = output ?: return
        val session = sessions.firstOrNull { it.request == request } ?: return
        if (session.exported) return
        session.exported = true
        val captured = snapshot(session)
        val capturedPagerAwaitedIdle = capturedPagerAwaitedIdle(request)
        session.target = null
        val capturedLabel = label
        writer.execute {
            runCatching {
                val array = JSONArray()
                captured.forEach { event ->
                    array.put(
                        JSONObject().put("event", event.event).put("monotonicNanos", event.monotonicNanos)
                            .put("chapterId", event.chapterId).put("page", event.page).put("request", event.request),
                    )
                }
                val id = captured.firstOrNull()?.request ?: return@runCatching
                val payload = JSONObject().put("label", capturedLabel).put("events", array)
                if (capturedPagerAwaitedIdle != null) payload.put("pagerAwaitedIdle", capturedPagerAwaitedIdle)
                File(directory, "$capturedLabel-$id.json").writeText(
                    payload.toString(2),
                    Charsets.UTF_8,
                )
            }
        }
    }
}
