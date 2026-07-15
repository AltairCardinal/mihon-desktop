package mihon.desktop.reader

import mihon.domain.reader.PhysicalDirection
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderNavigation
import mihon.domain.reader.ReaderNavigationCommand

/** Result of mapping a key event to a reader action. */
sealed interface ReaderPageAction {
    data class GoToPage(val page: Int) : ReaderPageAction
    data object NoPrevPage : ReaderPageAction
    data object NoNextPage : ReaderPageAction
}

private const val PAGE_SKIP = 5

/**
 * Pure functions that map keyboard input to [ReaderPageAction].
 * No Compose/Android imports — fully unit-testable on JVM.
 */
object ReaderKeyboardAction {

    fun forLeft(isRtl: Boolean, currentPage: Int, totalPages: Int): ReaderPageAction =
        fromShared(
            ReaderNavigation.resolvePhysicalPageCommand(
                PhysicalDirection.LEFT,
                if (isRtl) ReaderDirection.RTL else ReaderDirection.LTR,
            ),
            currentPage,
            totalPages,
        )

    fun forRight(isRtl: Boolean, currentPage: Int, totalPages: Int): ReaderPageAction =
        fromShared(
            ReaderNavigation.resolvePhysicalPageCommand(
                PhysicalDirection.RIGHT,
                if (isRtl) ReaderDirection.RTL else ReaderDirection.LTR,
            ),
            currentPage,
            totalPages,
        )

    fun forHome(): ReaderPageAction = ReaderPageAction.GoToPage(0)

    fun forEnd(totalPages: Int): ReaderPageAction =
        ReaderPageAction.GoToPage((totalPages - 1).coerceAtLeast(0))

    fun forPageUp(currentPage: Int, totalPages: Int): ReaderPageAction =
        ReaderPageAction.GoToPage((currentPage - PAGE_SKIP).coerceAtLeast(0))

    fun forPageDown(currentPage: Int, totalPages: Int): ReaderPageAction =
        ReaderPageAction.GoToPage((currentPage + PAGE_SKIP).coerceAtMost(totalPages - 1))

    /**
     * Maps digit keys 1–9 → page index 0–8, and 0 → last page.
     */
    fun forDigit(digit: Int, totalPages: Int): ReaderPageAction {
        val page = if (digit == 0) totalPages - 1 else (digit - 1).coerceAtMost(totalPages - 1)
        return ReaderPageAction.GoToPage(page)
    }

    // ──────────────────────────────────────────────────────────────────────
    private fun advance(currentPage: Int, totalPages: Int): ReaderPageAction =
        if (currentPage >= totalPages - 1) ReaderPageAction.NoNextPage
        else ReaderPageAction.GoToPage(currentPage + 1)

    private fun retreat(currentPage: Int): ReaderPageAction =
        if (currentPage <= 0) ReaderPageAction.NoPrevPage
        else ReaderPageAction.GoToPage(currentPage - 1)

    private fun fromShared(
        command: ReaderNavigationCommand,
        currentPage: Int,
        totalPages: Int,
    ): ReaderPageAction = when (command) {
        ReaderNavigationCommand.Next -> advance(currentPage, totalPages)
        ReaderNavigationCommand.Previous -> retreat(currentPage)
        else -> error("Unsupported keyboard page command: $command")
    }
}
