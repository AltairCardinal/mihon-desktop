package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.domain.reader.ReaderNavigationCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderKeyboardNavigationPositionTest {

    @Test
    fun `all tap presets move logical pages before mapping to LTR pager storage`() {
        val presetCoordinates =
            listOf(
                NavigationMode.RightAndLeft to ((50f to 150f) to (250f to 150f)),
                NavigationMode.L to ((50f to 150f) to (250f to 150f)),
                NavigationMode.Kindle to ((50f to 250f) to (250f to 250f)),
                NavigationMode.Edge to ((150f to 270f) to (30f to 150f)),
            )

        listOf(false, true).forEach { isRtl ->
            val currentPagerIndex = if (isRtl) 3 else 2
            val expectedPreviousPager = if (isRtl) 4 else 1
            val expectedNextPager = if (isRtl) 2 else 3
            presetCoordinates.forEach { (mode, coordinates) ->
                val (defaultPreviousPoint, defaultNextPoint) = coordinates
                val (previousPoint, nextPoint) =
                    if (isRtl && mode == NavigationMode.RightAndLeft) {
                        defaultNextPoint to defaultPreviousPoint
                    } else {
                        defaultPreviousPoint to defaultNextPoint
                    }
                val previous = tapNavRegion(previousPoint.first, previousPoint.second, 300f, 300f, mode, isRtl)
                val next = tapNavRegion(nextPoint.first, nextPoint.second, 300f, 300f, mode, isRtl)

                assertEquals(TapNavRegion.PREV, previous, "$mode isRtl=$isRtl previous region")
                assertEquals(TapNavRegion.NEXT, next, "$mode isRtl=$isRtl next region")
                assertEquals(
                    ReaderPageAction.GoToPage(expectedPreviousPager),
                    ReaderKeyboardAction.forPagerCommand(
                        ReaderNavigationCommand.Previous,
                        isRtl,
                        currentPagerIndex,
                        totalPages = 6,
                    ),
                    "$mode isRtl=$isRtl previous action",
                )
                assertEquals(
                    ReaderPageAction.GoToPage(expectedNextPager),
                    ReaderKeyboardAction.forPagerCommand(
                        ReaderNavigationCommand.Next,
                        isRtl,
                        currentPagerIndex,
                        totalPages = 6,
                    ),
                    "$mode isRtl=$isRtl next action",
                )
            }
        }
        assertEquals(TapNavRegion.MENU, tapNavRegion(50f, 150f, 300f, 300f, NavigationMode.Disabled, true))
    }

    @Test
    fun `logical pager commands preserve chapter boundaries in LTR and RTL`() {
        listOf(false, true).forEach { isRtl ->
            val firstLogicalPager = if (isRtl) 5 else 0
            val lastLogicalPager = if (isRtl) 0 else 5
            assertEquals(
                ReaderPageAction.NoPrevPage,
                ReaderKeyboardAction.forPagerCommand(
                    ReaderNavigationCommand.Previous,
                    isRtl,
                    firstLogicalPager,
                    totalPages = 6,
                ),
            )
            assertEquals(
                ReaderPageAction.NoNextPage,
                ReaderKeyboardAction.forPagerCommand(
                    ReaderNavigationCommand.Next,
                    isRtl,
                    lastLogicalPager,
                    totalPages = 6,
                ),
            )
        }
    }

    @Test
    fun `dual page keyboard position uses display groups at last paired spread`() {
        val state = ReaderState(
            currentPage = 41,
            resolvedUrls = List(43) { "page-$it" },
            dualPageMode = true,
        )

        val position = readerKeyboardNavigationPosition(state)

        assertEquals(21, position.current)
        assertEquals(22, position.total)
        assertEquals(
            ReaderPageAction.NoNextPage,
            ReaderKeyboardAction.forRight(isRtl = false, currentPage = position.current, totalPages = position.total),
        )
    }
}
