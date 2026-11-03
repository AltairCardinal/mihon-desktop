package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Diagnostic state only. Configuration is read before normal UI navigation, never at reader hotspots. */
object ReaderOpenLatencyProbe {
    data class Event(val event: String, val monotonicNanos: Long, val chapterId: Long, val page: Int, val request: Long)

    @Volatile var enabled = false
    private var targetManga = -1L
    private var targetChapter = -1L
    private val names = arrayOf(
        "MAIN_TOUCH_UP", "ACTIVITY_CREATE", "OPEN_READER_INTENT", "INIT_IO_ENTER", "MANGA_READY",
        "SOURCE_READY", "CHAPTER_LIST_BEGIN", "CHAPTER_LIST_READY", "CHAPTER_LOAD_BEGIN", "CHAPTER_IO_ENTER",
        "DOWNLOAD_CHECK_BEGIN", "DOWNLOAD_CHECK_END", "GET_PAGES_BEGIN", "CHAPTER_PATH_BEGIN", "CHAPTER_PATH_END",
        "ARCHIVE_OPEN_BEGIN", "ARCHIVE_OPEN_END", "ARCHIVE_LIST_BEGIN", "ARCHIVE_LIST_END", "PAGE_LIST_READY",
        "OPEN_PAGE", "ENCODED_READ_COMPLETE", "DECODE_SUBMIT", "FIRST_DRAW", "FRAME_COMMIT",
        "MAIN_TOUCH_DOWN", "GET_PAGES_END", "HEADER_FACTS_BEGIN", "HEADER_FACTS_END",
    )
    private class Session(val request: Long, val chapter: Long) {
        val times = AtomicLongArray(names.size)
        var exported = false
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
            )
            output = File(root, "sessions").apply { mkdirs() }
        }.onFailure { configure(false, -1, -1, "missing") }
    }

    /** Only called on main before navigation; allocates the entire bounded measurement budget. */
    fun configure(on: Boolean, manga: Long, chapter: Long, runLabel: String) {
        enabled = false
        active = null
        targetManga = manga
        targetChapter = chapter
        nextSession = 0
        lastTouch = 0
        lastDown = 0
        label = runLabel
        output = null
        sessions = if (on) Array(32) { Session(requestSequence.incrementAndGet(), chapter) } else emptyArray()
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

    fun requestFor(chapter: Long, page: Int = 0): Long {
        if (!enabled || page != 0) return 0
        val session = active ?: return 0
        return if (chapter == session.chapter) session.request else 0
    }

    /** One CAS into a preallocated timestamp slot; no lock, allocation, scheduling or I/O. */
    fun record(
        event: String,
        page: Int = 0,
        chapter: Long = active?.chapter ?: -1,
        request: Long = active?.request ?: 0,
    ): Boolean {
        if (!enabled || page != 0 || request == 0L) return false
        val session = active ?: return false
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
        session.times.get(index).takeIf { it != 0L }?.let { Event(name, it, session.chapter, 0, session.request) }
    }.sortedBy { it.monotonicNanos }

    /** Export only after reader onStop; force-killing before leaving intentionally loses the sample. */
    fun exportAfterReaderStopped(request: Long) {
        if (!enabled || request == 0L) return
        val directory = output ?: return
        val session = sessions.firstOrNull { it.request == request } ?: return
        if (session.exported) return
        session.exported = true
        val captured = snapshot(session)
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
                File(directory, "$capturedLabel-$id.json").writeText(
                    JSONObject().put("label", capturedLabel).put("events", array).toString(2),
                    Charsets.UTF_8,
                )
            }
        }
    }
}
