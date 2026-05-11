package mihon.desktop.ui.updates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

class UpdatesFilterTest {

    private fun item(chapterId: Long, read: Boolean = false, bookmark: Boolean = false): UpdatesWithRelations =
        UpdatesWithRelations(
            mangaId = 1L,
            mangaTitle = "Title",
            chapterId = chapterId,
            chapterName = "Ch $chapterId",
            scanlator = null,
            chapterUrl = "/ch/$chapterId",
            read = read,
            bookmark = bookmark,
            lastPageRead = 0L,
            sourceId = 1L,
            dateFetch = 0L,
            coverData = MangaCover(mangaId = 1L, sourceId = 1L, isMangaFavorite = true, url = null, lastModified = 0L),
        )

    // ── Downloaded filter ────────────────────────────────────────────────────

    @Test
    fun `DISABLED downloaded filter returns all items`() {
        val items = listOf(item(1), item(2), item(3))
        val downloaded = setOf(1L)
        val result = items.applyDownloadedFilter(TriState.DISABLED) { it.chapterId in downloaded }
        assertEquals(3, result.size)
    }

    @Test
    fun `ENABLED_IS downloaded filter returns only downloaded items`() {
        val items = listOf(item(1), item(2), item(3))
        val downloaded = setOf(1L, 3L)
        val result = items.applyDownloadedFilter(TriState.ENABLED_IS) { it.chapterId in downloaded }
        assertEquals(listOf(item(1), item(3)), result)
    }

    @Test
    fun `ENABLED_NOT downloaded filter returns only non-downloaded items`() {
        val items = listOf(item(1), item(2), item(3))
        val downloaded = setOf(1L)
        val result = items.applyDownloadedFilter(TriState.ENABLED_NOT) { it.chapterId in downloaded }
        assertEquals(listOf(item(2), item(3)), result)
    }

    @Test
    fun `ENABLED_IS downloaded filter with no downloaded items returns empty`() {
        val items = listOf(item(1), item(2))
        val result = items.applyDownloadedFilter(TriState.ENABLED_IS) { _ -> false }
        assertTrue(result.isEmpty())
    }

    // ── hasActiveFilters ─────────────────────────────────────────────────────

    @Test
    fun `hasActiveFilters is false when all filters DISABLED`() {
        val active = hasActiveUpdatesFilters(
            filterUnread = TriState.DISABLED,
            filterDownloaded = TriState.DISABLED,
            filterStarted = TriState.DISABLED,
            filterBookmarked = TriState.DISABLED,
            filterExcludedScanlators = false,
        )
        assertEquals(false, active)
    }

    @Test
    fun `hasActiveFilters is true when one filter is ENABLED_IS`() {
        val active = hasActiveUpdatesFilters(
            filterUnread = TriState.ENABLED_IS,
            filterDownloaded = TriState.DISABLED,
            filterStarted = TriState.DISABLED,
            filterBookmarked = TriState.DISABLED,
            filterExcludedScanlators = false,
        )
        assertEquals(true, active)
    }

    @Test
    fun `hasActiveFilters is true when excludedScanlators toggle is on`() {
        val active = hasActiveUpdatesFilters(
            filterUnread = TriState.DISABLED,
            filterDownloaded = TriState.DISABLED,
            filterStarted = TriState.DISABLED,
            filterBookmarked = TriState.DISABLED,
            filterExcludedScanlators = true,
        )
        assertEquals(true, active)
    }
}
