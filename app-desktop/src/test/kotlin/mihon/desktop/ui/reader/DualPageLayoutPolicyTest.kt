package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DualPageLayoutPolicyTest {

    @Test
    fun `dual page loading policy centers shared spinner when both pages are loading`() {
        assertEquals(
            DualPageLoadingIndicatorPlacement.Center,
            dualPageLoadingIndicatorPlacement(leftLoading = true, rightLoading = true),
        )
    }

    @Test
    fun `dual page loading policy centers spinner in the loading half when one page is loading`() {
        assertEquals(
            DualPageLoadingIndicatorPlacement.LeftHalfCenter,
            dualPageLoadingIndicatorPlacement(leftLoading = true, rightLoading = false),
        )
        assertEquals(
            DualPageLoadingIndicatorPlacement.RightHalfCenter,
            dualPageLoadingIndicatorPlacement(leftLoading = false, rightLoading = true),
        )
    }

    @Test
    fun `dual page loading policy hides spinner when both pages are loaded`() {
        assertEquals(
            DualPageLoadingIndicatorPlacement.None,
            dualPageLoadingIndicatorPlacement(leftLoading = false, rightLoading = false),
        )
    }
}
