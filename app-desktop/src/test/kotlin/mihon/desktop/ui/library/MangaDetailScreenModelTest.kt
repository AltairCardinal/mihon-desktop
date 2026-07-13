package mihon.desktop.ui.library

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DownloadItem
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.viewerFlagsWithReadingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters

/**
 * Stage 25.1 — MangaDetailScreenModel tests.
 *
 * Verifies that all manga detail state (filter, sort, dialogs, selection)
 * lives in a ScreenModel with StateFlow<MangaDetailState>.
 */
class MangaDetailScreenModelTest {

    @Test
    fun `selected read action exposes partial failure in state`() = runTest {
        val batch = BatchUpdateChapters()
        val model = MangaDetailScreenModel(mangaId = 1L, batchUpdateChapters = batch)
        val chapters = listOf(createFakeChapter(1L), createFakeChapter(2L))

        val result = model.runChapterBatch(chapters) { if (it.id == 2L) error("write failed") }

        assertEquals(listOf(1L), result.succeededIds)
        assertEquals("1 succeeded, 1 failed", model.state.value.batchActionMessage)
    }

    @Test
    fun `download batch returns empty result for empty selection`() = runTest {
        val model = MangaDetailScreenModel(mangaId = 1L, enqueueDownload = { error("must not run") })

        val result = model.enqueueDownloadBatch(createFakeManga(id = 1L), emptyList())

        assertTrue(result.succeededIds.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertEquals("0 succeeded, 0 failed", model.state.value.batchActionMessage)
    }

    @Test
    fun `download batch continues after an item fails and exposes partial failure`() = runTest {
        val enqueued = mutableListOf<Long>()
        val model = MangaDetailScreenModel(
            mangaId = 1L,
            enqueueDownload = { item ->
                if (item.chapterId == 2L) error("queue failed")
                enqueued += item.chapterId
            },
        )

        val result = model.enqueueDownloadBatch(
            createFakeManga(id = 1L),
            listOf(createFakeChapter(1L), createFakeChapter(2L), createFakeChapter(3L)),
        )

        assertEquals(listOf(1L, 3L), result.succeededIds)
        assertEquals(listOf(2L), result.failures.map { it.id })
        assertEquals(listOf(1L, 3L), enqueued)
        assertEquals("2 succeeded, 1 failed", model.state.value.batchActionMessage)
    }

    @Test
    fun `delete download batch continues after an item fails and exposes partial failure`() = runTest {
        val deleted = mutableListOf<String>()
        val model = MangaDetailScreenModel(
            mangaId = 1L,
            deleteDownload = { _, _, chapterName ->
                if (chapterName == "Chapter 2") error("delete failed")
                deleted += chapterName
            },
        )

        val result = model.deleteDownloadBatch(
            createFakeManga(id = 1L),
            listOf(createFakeChapter(1L), createFakeChapter(2L), createFakeChapter(3L)),
        )

        assertEquals(listOf(1L, 3L), result.succeededIds)
        assertEquals(listOf(2L), result.failures.map { it.id })
        assertEquals(listOf("Chapter 1", "Chapter 3"), deleted)
        assertEquals("2 succeeded, 1 failed", model.state.value.batchActionMessage)
    }

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun `state flow exists and is accessible`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        val flow: StateFlow<MangaDetailState> = model.state
        assertNotNull(flow)
        assertNotNull(flow.value)
    }

    @Test
    fun `cover state key changes when manga identity or thumbnail changes`() {
        val first = mangaCoverStateKey(
            mangaId = 1L,
            thumbnailUrl = "https://example.invalid/one.jpg",
            coverVersion = 0,
        )
        val sameMangaNewCover = mangaCoverStateKey(
            mangaId = 1L,
            thumbnailUrl = "https://example.invalid/two.jpg",
            coverVersion = 0,
        )
        val differentMangaSameCover = mangaCoverStateKey(
            mangaId = 2L,
            thumbnailUrl = "https://example.invalid/one.jpg",
            coverVersion = 0,
        )

        assertNotEquals(first, sameMangaNewCover)
        assertNotEquals(first, differentMangaSameCover)
    }

    @Test
    fun `cover request key changes when manga identity changes`() {
        val first = mangaCoverRequestKey(
            mangaId = 1L,
            model = "https://example.invalid/shared.jpg",
            coverVersion = 0,
        )
        val second = mangaCoverRequestKey(
            mangaId = 2L,
            model = "https://example.invalid/shared.jpg",
            coverVersion = 0,
        )

        assertNotEquals(first, second)
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
    fun `setManga restores sort mode and direction from each manga chapterFlags`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        val firstManga = createFakeManga(id = 1L).copy(
            chapterFlags = chapterSortFlags(
                mode = ChapterSortMode.BY_CHAPTER_NUMBER,
                ascending = true,
            ),
        )
        val secondManga = createFakeManga(id = 2L).copy(
            chapterFlags = chapterSortFlags(
                mode = ChapterSortMode.BY_DATE_UPLOAD,
                ascending = false,
            ),
        )

        model.setManga(firstManga)
        assertEquals(ChapterSortMode.BY_CHAPTER_NUMBER, model.state.value.chapterSortMode)
        assertTrue(model.state.value.chapterSortAscending)

        model.setManga(secondManga)
        assertEquals(ChapterSortMode.BY_DATE_UPLOAD, model.state.value.chapterSortMode)
        assertFalse(model.state.value.chapterSortAscending)
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

    // ── Actions ──────────────────────────────────────────────────────────────

    @Test
    fun `markAllRead updates every chapter`() = runTest {
        val chapterRepository = FakeChapterRepository()
        val chapters = listOf(createFakeChapter(1L), createFakeChapter(2L))
        chapterRepository.addAll(chapters)
        val model = MangaDetailScreenModel(mangaId = 1L, chapterRepository = chapterRepository)

        model.markAllRead(chapters)

        assertEquals(listOf(1L, 2L), chapterRepository.updates.map { it.id })
        assertTrue(chapterRepository.updates.all { it.read == true })
    }

    @Test
    fun `markSelectedBookmark uses true when any selected chapter is not bookmarked`() = runTest {
        val chapterRepository = FakeChapterRepository()
        val chapters = listOf(
            createFakeChapter(1L).copy(bookmark = true),
            createFakeChapter(2L).copy(bookmark = false),
        )
        chapterRepository.addAll(chapters)
        val model = MangaDetailScreenModel(mangaId = 1L, chapterRepository = chapterRepository)

        model.markSelectedBookmark(chapters)

        assertEquals(
            listOf(
                ChapterUpdate(id = 1L, bookmark = true),
                ChapterUpdate(id = 2L, bookmark = true),
            ),
            chapterRepository.updates,
        )
    }

    @Test
    fun `toggleLibrary flips favorite and preserves date when removing`() = runTest {
        val mangaRepository = FakeMangaRepository()
        mangaRepository.seed(createFakeManga(id = 1L).copy(favorite = true, dateAdded = 123L))
        val model = MangaDetailScreenModel(mangaId = 1L, mangaRepository = mangaRepository)

        model.toggleLibrary(mangaRepository.get(1L)!!)

        assertEquals(MangaUpdate(id = 1L, favorite = false, dateAdded = 123L), mangaRepository.updates.single())
    }

    @Test
    fun `setChapterSort persists chapter flags and updates state`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val manga = createFakeManga(id = 1L).copy(chapterFlags = chapterSortFlags(ChapterSortMode.BY_SOURCE_ORDER, false))
        mangaRepository.seed(manga)
        val model = MangaDetailScreenModel(mangaId = 1L, mangaRepository = mangaRepository)
        model.setManga(manga)

        model.setChapterSort(manga, ChapterSortMode.BY_CHAPTER_NUMBER)

        assertEquals(ChapterSortMode.BY_CHAPTER_NUMBER, model.state.value.chapterSortMode)
        assertFalse(model.state.value.chapterSortAscending)
        assertEquals(1, mangaRepository.updates.size)
        assertEquals(1L, mangaRepository.updates.single().id)
        assertNotNull(mangaRepository.updates.single().chapterFlags)
    }

    @Test
    fun `setReadingMode persists viewer flags`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val model = MangaDetailScreenModel(mangaId = 1L, mangaRepository = mangaRepository)

        model.setReadingMode(mangaId = 1L, currentFlags = 0L, mode = ReadingMode.WEBTOON)

        assertEquals(viewerFlagsWithReadingMode(0L, ReadingMode.WEBTOON), mangaRepository.updates.single().viewerFlags)
    }

    @Test
    fun `enqueueDownload skips already downloaded chapters`() {
        val enqueued = mutableListOf<DownloadItem>()
        val model = MangaDetailScreenModel(
            mangaId = 1L,
            enqueueDownload = enqueued::add,
            isDownloaded = { _, _, chapterName -> chapterName == "Chapter 1" },
        )
        val manga = createFakeManga(id = 1L, title = "M")
        val chapters = listOf(createFakeChapter(1L), createFakeChapter(2L))

        model.enqueueDownloads(manga, chapters)

        assertEquals(listOf(2L), enqueued.map { it.chapterId })
    }

    @Test
    fun `enqueueDownload skips chapters that must open in an external browser`() {
        val enqueued = mutableListOf<DownloadItem>()
        val model = MangaDetailScreenModel(
            mangaId = 1L,
            enqueueDownload = enqueued::add,
        )
        val manga = createFakeManga(id = 1L, title = "M")
        val externalChapter = createFakeChapter(1L).copy(url = "external:https://kodansha.us/chapter/1")

        model.enqueueDownloads(manga, listOf(externalChapter))

        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `readerRequest uses source order and last page`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        val manga = createFakeManga(id = 1L, title = "M").copy(source = 9L, viewerFlags = 7L)
        val chapters = listOf(
            createFakeChapter(2L).copy(sourceOrder = 2L),
            createFakeChapter(1L).copy(sourceOrder = 1L, lastPageRead = 4L),
        )

        val request = requireNotNull(model.readerRequest(manga, chapters, chapters[1]))

        assertNotNull(request)
        assertEquals(1L, request.chapterId)
        assertEquals(0, request.currentChapterIndex)
        assertEquals(4, request.initialPage)
        assertEquals(9L, request.sourceId)
    }

    @Test
    fun `readerRequest returns null for an external browser chapter`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        val manga = createFakeManga(id = 1L)
        val chapter = createFakeChapter(1L).copy(url = "external:https://kodansha.us/chapter/1")

        val request = model.readerRequest(manga, listOf(chapter), chapter)

        assertNull(request)
    }

    @Test
    fun `readerRequest excludes external chapters from reader navigation`() {
        val model = MangaDetailScreenModel(mangaId = 1L)
        val manga = createFakeManga(id = 1L)
        val internalChapter = createFakeChapter(1L).copy(url = "/chapter/internal")
        val externalChapter = createFakeChapter(2L).copy(url = "external:https://example.com/chapter/2")

        val request = requireNotNull(
            model.readerRequest(manga, listOf(internalChapter, externalChapter), internalChapter),
        )

        assertEquals(listOf(1L), request.chapters.map { it.id })
    }

    @Test
    fun `setCategoriesForManga delegates to category interactor`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val model = MangaDetailScreenModel(mangaId = 1L, setMangaCategories = SetMangaCategories(mangaRepository))

        model.setCategoriesForManga(1L, listOf(10L, 11L))

        assertEquals(listOf(10L, 11L), mangaRepository.getMangaCategoryIds(1L))
    }

    @Test
    fun `migrateTo persists target source and manga identity`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val model = MangaDetailScreenModel(mangaId = 1L, mangaRepository = mangaRepository)
        val target = SManga.create().apply {
            url = "/new"
            title = "New title"
            thumbnail_url = "https://example.invalid/new.jpg"
        }

        model.migrateTo(targetSourceId = 9L, item = target, fallbackTitle = "Old")

        assertEquals(9L, mangaRepository.updates.single().source)
        assertEquals("/new", mangaRepository.updates.single().url)
        assertEquals("New title", mangaRepository.updates.single().title)
    }

    @Test
    fun `linkCreator stores creator relation and returns creator id`() = runTest {
        val creatorRepository = FakeCreatorRepository()
        val model = MangaDetailScreenModel(mangaId = 42L, creatorRepository = creatorRepository)

        val creatorId = model.linkCreator("Jane", CreatorRole.AUTHOR)

        assertEquals(1L, creatorId)
        assertEquals(42L, creatorRepository.links.single().mangaId)
        assertEquals(CreatorRole.AUTHOR, creatorRepository.links.single().role)
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

private class FakeCreatorRepository : CreatorRepository {
    data class Link(val mangaId: Long, val creatorId: Long, val role: CreatorRole)

    val links = mutableListOf<Link>()
    private var nextId = 1L

    override suspend fun upsertCreator(displayName: String, aliases: List<String>): Creator =
        Creator(
            id = nextId++,
            displayName = displayName,
            normalizedName = displayName.lowercase(),
            sortName = displayName.lowercase(),
            aliases = aliases,
            createdAt = 1L,
            lastModifiedAt = 1L,
        )

    override suspend fun linkMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) {
        links += Link(mangaId, creatorId, role)
    }

    override suspend fun getCreator(id: Long) = null
    override fun getCreatorsAsFlow() = kotlinx.coroutines.flow.flowOf(emptyList<Creator>())
    override suspend fun linkDiscoveryCandidateCreator(
        candidateId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = Unit
    override suspend fun followCreator(
        creatorId: Long,
        sourceIds: List<Long>,
        languageTags: List<String>,
    ) = error("unused")
    override suspend fun unfollowCreator(creatorId: Long) = Unit
    override suspend fun getFollowedCreators() = emptyList<tachiyomi.domain.creator.model.CreatorWatch>()
    override fun getFollowedCreatorsAsFlow() =
        kotlinx.coroutines.flow.flowOf(emptyList<tachiyomi.domain.creator.model.CreatorWatch>())
    override suspend fun updateWatchCheckResult(creatorId: Long, checkedAt: Long, success: Boolean, error: String?) = Unit
    override suspend fun upsertDiscoveryCandidate(
        source: Long,
        url: String,
        title: String,
        authorText: String?,
        artistText: String?,
        languageTag: String,
        languageConfidence: Double,
        languageEvidence: String,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
        state: tachiyomi.domain.creator.model.DiscoveryCandidateState,
    ) = error("unused")
    override suspend fun getDiscoveryCandidatesForCreator(creatorId: Long) =
        emptyList<tachiyomi.domain.creator.model.DiscoveryCandidate>()
    override suspend fun getDiscoveryCandidate(id: Long) = null
    override suspend fun getMangaCreatorsForCreator(creatorId: Long) =
        emptyList<tachiyomi.domain.creator.model.MangaCreator>()
    override suspend fun getDiscoveryCandidateCreatorsForCreator(creatorId: Long) =
        emptyList<tachiyomi.domain.creator.model.DiscoveryCandidateCreator>()
    override suspend fun createCanonicalWork(primaryTitle: String, primaryCreatorId: Long?, originalLanguage: String?) =
        error("unused")
    override suspend fun upsertMangaWorkMatch(
        mangaId: Long,
        workId: Long,
        confidence: Double,
        matchReason: String,
        state: tachiyomi.domain.creator.model.WorkMatchState,
        manuallyConfirmed: Boolean,
    ) = error("unused")
}
