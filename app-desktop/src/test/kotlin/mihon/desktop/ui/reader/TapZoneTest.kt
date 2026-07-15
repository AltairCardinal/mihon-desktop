package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
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
    fun `pager input keeps physical movement while shared direction selects the chapter boundary`() {
        assertEquals(
            ReaderPageAction.GoToPage(0),
            ReaderKeyboardAction.forPagerLeft(isRtl = false, currentPagerIndex = 1, totalPages = 5),
        )
        assertEquals(
            ReaderPageAction.NoPrevPage,
            ReaderKeyboardAction.forPagerLeft(isRtl = false, currentPagerIndex = 0, totalPages = 5),
        )
        assertEquals(
            ReaderPageAction.NoNextPage,
            ReaderKeyboardAction.forPagerLeft(isRtl = true, currentPagerIndex = 0, totalPages = 5),
        )
        assertEquals(
            ReaderPageAction.NoPrevPage,
            ReaderKeyboardAction.forPagerRight(isRtl = true, currentPagerIndex = 4, totalPages = 5),
        )
    }
}
