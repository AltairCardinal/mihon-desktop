package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.paging.testing.asSnapshot
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import mihon.domain.sync.SyncOrigin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery

class BrowseSourceScreenModelBehaviorTest {
    private val modelHost = ScreenModelTestHost()

    @Test
    fun `browse favorite toggles reach the repository as explicit user operations`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val updates = mutableListOf<MangaUpdate>()
        val repository = mockk<MangaRepository>()
        coEvery { repository.update(any()) } answers {
            updates.add(firstArg())
            true
        }
        val model = screenModel(DirectBrowseRejectingSource(), updateManga = UpdateManga(repository, mockk()))
        for (favorite in listOf(false, true)) {
            model.changeMangaFavorite(
                Manga.create().copy(id = 31, source = 17, favorite = favorite),
            )
        }
        runCurrent()
        assertEquals(listOf(true, false), updates.map { it.favorite })
        assertEquals(List(2) { SyncOrigin.USER }, updates.map { it.syncContext.origin })
    }

    @Test
    fun `source only browse exposes filters and accepts search`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 81L
            override val name = "Source only"
            override fun getFilterList() = FilterList(
                eu.kanade.tachiyomi.source.model.Filter.Header("Available filter"),
            )
        }
        val model = screenModel(source)
        assertEquals(1, model.state.value.filters.size)
        model.search("source-only-query")
        assertEquals("source-only-query", model.state.value.toolbarQuery)
    }

    @Test
    fun `source only production Pager calls search with filters and subsequent pages`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val calls = mutableListOf<Pair<Int, String>>()
        val selectedFilters = FilterList(eu.kanade.tachiyomi.source.model.Filter.Header("Selected"))
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 82L
            override val name = "Paged Source only"
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                calls += page to query
                assertSame(selectedFilters, filters)
                return MangasPage(listOf(manga("/page/$page", "Page $page")), page == 1)
            }
        }
        val model = screenModel(source)
        model.search("filtered query", selectedFilters)
        backgroundScope.launch(dispatcher) { model.mangaPagerFlowFlow.collect() }
        runCurrent()
        val snapshot = backgroundScope.async(dispatcher) {
            model.mangaPagerFlowFlow.value.asSnapshot { appendScrollWhile { true } }
        }
        runCurrent()
        assertEquals(listOf("/page/1", "/page/2"), snapshot.await().map { it.value.url })
        assertEquals(listOf(1 to "filtered query", 2 to "filtered query"), calls)
    }

    @AfterEach
    fun tearDown() {
        modelHost.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `late old Pager generation cannot replace or pollute the current listing Pager`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = HangingBrowseSource()
        val model = screenModel(source)
        backgroundScope.launch(dispatcher) { model.mangaPagerFlowFlow.collect() }
        runCurrent()

        val oldPager = model.mangaPagerFlowFlow.value
        val oldSnapshot = backgroundScope.async(dispatcher) { oldPager.asSnapshot() }
        runCurrent()
        source.oldStarted.await()

        model.setListing(BrowseSourceScreenModel.Listing.Search("new", FilterList()))
        runCurrent()
        val newPager = model.mangaPagerFlowFlow.value
        assertNotSame(oldPager, newPager)
        val newSnapshot = backgroundScope.async(dispatcher) { newPager.asSnapshot() }
        runCurrent()
        source.newStarted.await()

        source.oldResult.complete(MangasPage(listOf(manga("/old", "Old")), false))
        runCurrent()

        assertEquals(listOf("/old"), oldSnapshot.await().map { it.value.url })
        assertSame(newPager, model.mangaPagerFlowFlow.value)
        assertFalse(newSnapshot.isCompleted)

        source.newResult.complete(MangasPage(listOf(manga("/new", "New")), false))
        runCurrent()

        assertEquals(listOf("/new"), newSnapshot.await().map { it.value.url })
        assertSame(newPager, model.mangaPagerFlowFlow.value)
    }

    @Test
    fun `production Pager publishes shared service content without calling source directly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = DirectBrowseRejectingSource()
        val service = mockk<SourceMangaSearchService>()
        val sentinel = manga("/shared-service-sentinel", "Shared service sentinel")
        coEvery { service.loadPageResult(source, any()) } answers {
            SourcePageResult.Content(secondArg(), listOf(sentinel), hasNextPage = false)
        }
        val model = screenModel(source, service)
        backgroundScope.launch(dispatcher) { model.mangaPagerFlowFlow.collect() }
        runCurrent()

        val snapshot = backgroundScope.async(dispatcher) { model.mangaPagerFlowFlow.value.asSnapshot() }
        runCurrent()
        coVerify(exactly = 1) {
            service.loadPageResult(
                source,
                match { it.sourceId == source.id && it.page == 1 && it.query == SourceQuery.Popular },
            )
        }
        assertEquals(listOf(sentinel.url), snapshot.await().map { it.value.url })
    }

    private fun kotlinx.coroutines.test.TestScope.screenModel(
        source: eu.kanade.tachiyomi.source.Source,
        sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
        updateManga: UpdateManga = mockk(),
    ): BrowseSourceScreenModel {
        val preferenceStore = InMemoryPreferenceStore()
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
        val getManga = mockk<GetManga> {
            every { subscribe(any(), any()) } returns flowOf(null)
        }
        val sourceManager = mockk<SourceManager> {
            every { getOrStub(source.id) } returns source
        }
        val getIncognitoState = mockk<GetIncognitoState> {
            every { await(source.id) } returns false
        }

        return modelHost.create {
            BrowseSourceScreenModel(
                sourceId = source.id,
                listingQuery = BrowseSourceScreenModel.Listing.Popular.query,
                sourceManager = sourceManager,
                sourcePreferences = SourcePreferences(preferenceStore),
                libraryPreferences = LibraryPreferences(preferenceStore),
                coverCache = mockk<CoverCache>(relaxed = true),
                sourceMangaSearchService = sourceMangaSearchService,
                networkToLocalManga = NetworkToLocalManga(repository),
                getDuplicateLibraryManga = mockk<GetDuplicateLibraryManga>(),
                getCategories = mockk<GetCategories>(),
                setMangaCategories = mockk<SetMangaCategories>(),
                setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>(relaxed = true),
                getManga = getManga,
                updateManga = updateManga,
                addTracks = mockk<AddTracks>(relaxed = true),
                getIncognitoState = getIncognitoState,
                pagerCoroutineScope = backgroundScope,
            )
        }
    }

    private class HangingBrowseSource : eu.kanade.tachiyomi.source.Source {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<MangasPage>()
        val newResult = CompletableDeferred<MangasPage>()

        override val id = 13L
        override val name = "Hanging browse source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            oldStarted.complete(Unit)
            return withContext(NonCancellable) { oldResult.await() }
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            newStarted.complete(Unit)
            return withContext(NonCancellable) { newResult.await() }
        }

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class DirectBrowseRejectingSource : CatalogueSource {
        override val id = 17L
        override val name = "Direct browse rejecting source"
        override val lang = "en"
        override val supportsLatest = true

        override suspend fun getPopularManga(page: Int): MangasPage =
            error("Production Pager bypassed SourceMangaSearchService for popular")

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            error("Production Pager bypassed SourceMangaSearchService for search")

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            error("Production Pager bypassed SourceMangaSearchService for latest")

        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun manga(url: String, title: String) = SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
