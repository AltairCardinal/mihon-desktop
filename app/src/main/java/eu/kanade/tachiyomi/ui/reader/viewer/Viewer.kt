package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /** Current physical page slots have laid out loading, error, or image feedback for this chapter. */
    fun isInitialPageFeedbackReady(chapter: ReaderChapter): Boolean = false

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean
}

/** Alpha is deliberately excluded: the initial overlay keeps the viewer transparent until handoff. */
internal fun View.hasReaderFeedbackLayout(): Boolean =
    isAttachedToWindow && isShown && width > 0 && height > 0 && !isLayoutRequested

internal fun View.isSelectedReaderViewport(viewport: View): Boolean {
    if (!hasReaderFeedbackLayout() || !viewport.hasReaderFeedbackLayout()) return false
    val actual = Rect()
    val expected = Rect()
    return getGlobalVisibleRect(actual) && viewport.getGlobalVisibleRect(expected) && actual == expected
}
