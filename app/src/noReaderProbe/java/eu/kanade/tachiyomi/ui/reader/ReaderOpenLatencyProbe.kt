package eu.kanade.tachiyomi.ui.reader

import android.content.Context

/** Compile-time adapter for signed tablet builds. No recorder, configuration or event storage. */
@Suppress("UNUSED_PARAMETER")
internal object ReaderOpenLatencyProbe {
    @Suppress("ktlint:standard:property-naming") // Preserve the shared diagnostic adapter API.
    const val enabled = false

    fun loadConfiguration(context: Context) = Unit

    fun noteMainTouchDown() = Unit

    fun noteMainTouchUp() = Unit

    fun begin() = Unit

    fun startChapter(manga: Long, chapter: Long): Long = 0L

    fun requestFor(chapter: Long, page: Int = -1): Long = 0L

    internal fun targetPageFor(request: Long): Int? = null

    internal fun bindPageList(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any,
        targetPageObject: Any,
    ): Boolean = false

    internal fun bindViewerChapters(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        viewerChapters: Any,
    ): Boolean = false

    internal fun requestForTargetPage(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        page: Any,
        viewerChapters: Any?,
    ): Long = 0L

    internal fun recordForPageList(
        event: String,
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
    ): Boolean = false

    internal fun recordForViewerChapters(
        event: String,
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        viewerChapters: Any,
    ): Boolean = false

    internal fun notePagerDispatch(
        request: Long,
        chapterId: Long,
        chapter: Any,
        pages: Any?,
        viewerChapters: Any,
        awaitedIdle: Boolean,
    ): Boolean = false

    internal fun capturedPagerAwaitedIdle(request: Long): Boolean? = null

    internal fun isStartupWindow(request: Long, chapter: Long): Boolean = false

    internal inline fun <T> traceStartup(name: String, request: Long, chapter: Long, block: () -> T): T = block()

    fun record(
        event: String,
        page: Int = -1,
        chapter: Long = -1,
        request: Long = 0,
    ): Boolean = false

    fun exportAfterReaderStopped(request: Long) = Unit
}
