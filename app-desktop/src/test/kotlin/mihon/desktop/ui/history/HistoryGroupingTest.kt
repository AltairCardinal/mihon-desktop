package mihon.desktop.ui.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date
import java.util.Calendar

class HistoryGroupingTest {

    private fun historyItem(id: Long, readAt: Date?) = HistoryWithRelations(
        id = id,
        chapterId = id,
        mangaId = id,
        title = "Manga $id",
        chapterNumber = 1.0,
        readAt = readAt,
        readDuration = 0L,
        coverData = MangaCover(mangaId = id, sourceId = 1L, isMangaFavorite = false, url = null, lastModified = 0L),
    )

    private fun daysAgo(days: Int): Date {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return cal.time
    }

    @Test
    fun `items from same day are grouped together`() {
        val today = daysAgo(0)
        val items = listOf(
            historyItem(1, today),
            historyItem(2, today),
        )
        val sections = groupHistoryByDate(items)
        assertEquals(1, sections.size)
        assertEquals(2, sections[0].items.size)
    }

    @Test
    fun `items from different days produce separate sections`() {
        val items = listOf(
            historyItem(1, daysAgo(0)),
            historyItem(2, daysAgo(1)),
            historyItem(3, daysAgo(5)),
        )
        val sections = groupHistoryByDate(items)
        assertEquals(3, sections.size)
    }

    @Test
    fun `today label is Today`() {
        val items = listOf(historyItem(1, daysAgo(0)))
        val sections = groupHistoryByDate(items)
        assertEquals("Today", sections[0].dateLabel)
    }

    @Test
    fun `yesterday label is Yesterday`() {
        val items = listOf(historyItem(1, daysAgo(1)))
        val sections = groupHistoryByDate(items)
        assertEquals("Yesterday", sections[0].dateLabel)
    }

    @Test
    fun `older dates use formatted date string`() {
        val items = listOf(historyItem(1, daysAgo(10)))
        val sections = groupHistoryByDate(items)
        // Should not be "Today" or "Yesterday"
        val label = sections[0].dateLabel
        assert(label != "Today" && label != "Yesterday") {
            "Expected formatted date, got: $label"
        }
    }

    @Test
    fun `null readAt items are excluded from grouping`() {
        val items = listOf(
            historyItem(1, daysAgo(0)),
            historyItem(2, null),
        )
        val sections = groupHistoryByDate(items)
        assertEquals(1, sections.size)
        assertEquals(1, sections[0].items.size)
    }

    @Test
    fun `empty list produces empty sections`() {
        val sections = groupHistoryByDate(emptyList())
        assertEquals(0, sections.size)
    }

    @Test
    fun `sections preserve original item order within each group`() {
        val today = daysAgo(0)
        val items = listOf(
            historyItem(3, today),
            historyItem(1, today),
            historyItem(2, today),
        )
        val sections = groupHistoryByDate(items)
        assertEquals(listOf(3L, 1L, 2L), sections[0].items.map { it.id })
    }
}
