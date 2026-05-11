package mihon.desktop.ui.library

import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/**
 * Stage 25.1 — MangaDetailScreenModel tests.
 *
 * Verifies that all manga detail state (filter, sort, dialogs, selection)
 * lives in a ScreenModel with StateFlow<MangaDetailState>.
 */
class MangaDetailScreenModelTest {

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun `state flow exists and is accessible`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        val flow: StateFlow<MangaDetailState> = model.state
        assertNotNull(flow)
        assertNotNull(flow.value)
    }

    @Test
    fun `initial state has expected defaults`() {
        val model = MangaDetailScreenModel(mangaId = 42L)
        val s = model.state.value
        assertNull(s.manga)
        assertTrue(s.chapters.isEmpty())
        assertFalse(s.isUpdating)
        assertTrue(s.filterShowRead)
        assertTrue(s.filterShowUnread)
        assertFalse(s.filterShowBookmarked)
        assertFalse(s.filterShowDownloaded)
        assertEquals(ChapterSortMode.BY_SOURCE_ORDER, s.chapterSortMode)
        assertFalse(s.chapterSortAscending)
        assertFalse(s.showFilterMenu)
        assertFalse(s.showNotesDialog)
        assertFalse(s.showMigrateSourcePicker)
        assertNull(s.deleteConfirmChapter)
        assertFalse(s.markAllReadConfirm)
    }

    // ── Filter toggles ────────────────────────────────────────────────────────

    @Test
    fun `setFilterShowRead toggles filterShowRead`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertTrue(model.state.value.filterShowRead)
        model.setFilterShowRead(false)
        assertFalse(model.state.value.filterShowRead)
        model.setFilterShowRead(true)
        assertTrue(model.state.value.filterShowRead)
    }

    @Test
    fun `setFilterShowUnread toggles filterShowUnread`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        model.setFilterShowUnread(false)
        assertFalse(model.state.value.filterShowUnread)
    }

    @Test
    fun `setFilterShowBookmarked toggles filterShowBookmarked`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.filterShowBookmarked)
        model.setFilterShowBookmarked(true)
        assertTrue(model.state.value.filterShowBookmarked)
    }

    @Test
    fun `setFilterShowDownloaded toggles filterShowDownloaded`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.filterShowDownloaded)
        model.setFilterShowDownloaded(true)
        assertTrue(model.state.value.filterShowDownloaded)
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    @Test
    fun `setSortMode updates chapterSortMode`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        model.setSortMode(ChapterSortMode.BY_CHAPTER_NUMBER)
        assertEquals(ChapterSortMode.BY_CHAPTER_NUMBER, model.state.value.chapterSortMode)
    }

    @Test
    fun `setSortAscending updates chapterSortAscending`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.chapterSortAscending)
        model.setSortAscending(true)
        assertTrue(model.state.value.chapterSortAscending)
    }

    @Test
    fun `toggling same sort mode flips ascending`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        // default: BY_SOURCE_ORDER, descending
        model.toggleSort(ChapterSortMode.BY_SOURCE_ORDER)
        // Same mode tapped → flip direction
        assertTrue(model.state.value.chapterSortAscending)
        model.toggleSort(ChapterSortMode.BY_SOURCE_ORDER)
        assertFalse(model.state.value.chapterSortAscending)
    }

    @Test
    fun `toggling different sort mode sets new mode and resets to descending`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        model.setSortAscending(true)
        model.toggleSort(ChapterSortMode.BY_CHAPTER_NUMBER)
        assertEquals(ChapterSortMode.BY_CHAPTER_NUMBER, model.state.value.chapterSortMode)
        assertFalse(model.state.value.chapterSortAscending) // reset to desc
    }

    // ── Dialog visibility ─────────────────────────────────────────────────────

    @Test
    fun `toggleFilterMenu flips showFilterMenu`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.showFilterMenu)
        model.toggleFilterMenu()
        assertTrue(model.state.value.showFilterMenu)
        model.toggleFilterMenu()
        assertFalse(model.state.value.showFilterMenu)
    }

    @Test
    fun `setShowNotesDialog updates showNotesDialog`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.showNotesDialog)
        model.setShowNotesDialog(true)
        assertTrue(model.state.value.showNotesDialog)
    }

    @Test
    fun `setShowMigrateSourcePicker updates showMigrateSourcePicker`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.showMigrateSourcePicker)
        model.setShowMigrateSourcePicker(true)
        assertTrue(model.state.value.showMigrateSourcePicker)
    }

    @Test
    fun `setDeleteConfirmChapter sets and clears deleteConfirmChapter`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertNull(model.state.value.deleteConfirmChapter)
        val fakeChapter = createFakeChapter(id = 99L)
        model.setDeleteConfirmChapter(fakeChapter)
        assertEquals(99L, model.state.value.deleteConfirmChapter?.id)
        model.setDeleteConfirmChapter(null)
        assertNull(model.state.value.deleteConfirmChapter)
    }

    @Test
    fun `setMarkAllReadConfirm updates markAllReadConfirm`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.markAllReadConfirm)
        model.setMarkAllReadConfirm(true)
        assertTrue(model.state.value.markAllReadConfirm)
    }

    // ── Manga + chapter data ──────────────────────────────────────────────────

    @Test
    fun `setManga updates manga in state`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertNull(model.state.value.manga)
        val fakeManga = createFakeManga(id = 1L, title = "Test Manga")
        model.setManga(fakeManga)
        assertEquals("Test Manga", model.state.value.manga?.title)
    }

    @Test
    fun `setChapters updates chapters in state`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertTrue(model.state.value.chapters.isEmpty())
        val chapters = listOf(createFakeChapter(1L), createFakeChapter(2L))
        model.setChapters(chapters)
        assertEquals(2, model.state.value.chapters.size)
    }

    @Test
    fun `setIsUpdating updates isUpdating flag`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertFalse(model.state.value.isUpdating)
        model.setIsUpdating(true)
        assertTrue(model.state.value.isUpdating)
        model.setIsUpdating(false)
        assertFalse(model.state.value.isUpdating)
    }

    @Test
    fun `setAvailableScanlators updates availableScanlators`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        assertTrue(model.state.value.availableScanlators.isEmpty())
        model.setAvailableScanlators(setOf("Group A", "Group B"))
        assertEquals(setOf("Group A", "Group B"), model.state.value.availableScanlators)
    }

    @Test
    fun `setExcludedScanlators updates excludedScanlators`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        model.setExcludedScanlators(setOf("Group A"))
        assertEquals(setOf("Group A"), model.state.value.excludedScanlators)
    }

    // ── MangaDetailState data class sanity ───────────────────────────────────

    @Test
    fun `MangaDetailState has expected fields`() {
        val state = MangaDetailState(
            filterShowRead = false,
            filterShowUnread = false,
            chapterSortMode = ChapterSortMode.BY_CHAPTER_NUMBER,
            chapterSortAscending = true,
        )
        assertFalse(state.filterShowRead)
        assertFalse(state.filterShowUnread)
        assertEquals(ChapterSortMode.BY_CHAPTER_NUMBER, state.chapterSortMode)
        assertTrue(state.chapterSortAscending)
    }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

private fun createFakeManga(id: Long, title: String = "Manga $id") =
    Manga.create().copy(id = id, title = title, source = 1L)

private fun createFakeChapter(id: Long) =
    tachiyomi.domain.chapter.model.Chapter.create().copy(id = id, mangaId = 1L, name = "Chapter $id")
