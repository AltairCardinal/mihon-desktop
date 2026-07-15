package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.domain.reader.ReaderNavigationCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TapZoneTest {

    @Test
    fun `tap regions delegate to the shared navigation presets`() {
        assertEquals(
            TapNavRegion.PREV,
            tapNavRegion(50f, 150f, 300f, 300f, NavigationMode.RightAndLeft),
        )
        assertEquals(
            TapNavRegion.NEXT,
            tapNavRegion(250f, 150f, 300f, 300f, NavigationMode.RightAndLeft),
        )
        assertEquals(
            TapNavRegion.MENU,
            tapNavRegion(150f, 150f, 300f, 300f, NavigationMode.RightAndLeft),
        )
    }

    @Test
    fun `pager input maps shared logical commands to pager storage and chapter boundaries`() {
        assertEquals(
            ReaderPageAction.GoToPage(0),
            ReaderKeyboardAction.forPagerCommand(ReaderNavigationCommand.Previous, false, 1, 5),
        )
        assertEquals(
            ReaderPageAction.NoPrevPage,
            ReaderKeyboardAction.forPagerCommand(ReaderNavigationCommand.Previous, false, 0, 5),
        )
        assertEquals(
            ReaderPageAction.NoNextPage,
            ReaderKeyboardAction.forPagerCommand(ReaderNavigationCommand.Next, true, 0, 5),
        )
        assertEquals(
            ReaderPageAction.NoPrevPage,
            ReaderKeyboardAction.forPagerCommand(ReaderNavigationCommand.Previous, true, 4, 5),
        )
    }
}
