package mihon.desktop.ui.browse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceBrowseModeTest {

    @Test
    fun `available modes contains only POPULAR when source does not support latest`() {
        val modes = availableBrowseModes(supportsLatest = false)
        assertEquals(listOf(BrowseMode.POPULAR), modes)
    }

    @Test
    fun `available modes contains POPULAR and LATEST when source supports latest`() {
        val modes = availableBrowseModes(supportsLatest = true)
        assertEquals(listOf(BrowseMode.POPULAR, BrowseMode.LATEST), modes)
    }

    @Test
    fun `POPULAR is first in the list`() {
        val modes = availableBrowseModes(supportsLatest = true)
        assertEquals(BrowseMode.POPULAR, modes.first())
    }
}
