package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.browse.components.catalogResultVisibleIndices
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.DelayedMangaFixture
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
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
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery

class BrowseSourceScreenModelBehaviorTest {
    private val modelHost = ScreenModelTestHost()

    @Test
    fun `real paging load classifies delayed manga fields before saving and keeps healthy pages independent`() =
        runTest {
            for (field in listOf("title", "memo", "url")) {
                val original = java.io.IOException("returned field", NoSuchMethodError("future $field"))
                val returned = DelayedMangaFixture(field, original, if (field == "url") 1 else 0)
                val source = object : eu.kanade.tachiyomi.source.Source {
                    override val id = 9L
                    override val name = "Paging returned fixture"
                    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                        MangasPage(listOf(returned), false)
                }
                val repository = mockk<MangaRepository>()
                coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
                val paging = SharedSourcePagingSource(
                    source,
                    BrowseSourceScreenModel.Listing.Search("query", FilterList()),
                    1L,
                    SourceMangaSearchService(),
                    NetworkToLocalManga(repository),
                    sourceId = 9L,
                )
                val attempt = runCatching { paging.load(PagingSource.LoadParams.Refresh<Long>(null, 25, false)) }
                assertNull(attempt.exceptionOrNull(), field)
                val error = assertInstanceOf(
                    PagingSource.LoadResult.Error::class.java,
                    attempt.getOrThrow(),
                ).throwable
                val pageError = assertInstanceOf(SourcePageException::class.java, error).pageError
                assertInstanceOf(
                    mihon.domain.error.AppError.ExtensionIncompatible::class.java,
                    pageError.error,
                )
                assertEquals(tachiyomi.domain.source.service.SourceRecoveryAction.None, pageError.recoveryAction)
                coVerify(exactly = 0) { repository.insertNetworkManga(any()) }
                val healthy = SManga.create().apply {
                    url = "/healthy"
                    title = "Healthy"
                }
                val good = object : eu.kanade.tachiyomi.source.Source {
                    override val id = 10L
                    override val name = "Healthy paging"
                    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                        MangasPage(listOf(healthy), false)
                }
                val healthyPage = SharedSourcePagingSource(
                    good,
                    BrowseSourceScreenModel.Listing.Search("query", FilterList()),
                    1L,
                    SourceMangaSearchService(),
                    NetworkToLocalManga(repository),
                    sourceId = 10L,
                )
                    .load(PagingSource.LoadParams.Refresh<Long>(null, 25, false))
                val storedPage = assertInstanceOf(PagingSource.LoadResult.Page::class.java, healthyPage)
                val storedManga = storedPage.data.single() as Manga
                assertEquals("Healthy", storedManga.title)
            }
        }

    @Test
    fun `registered browse ID and frozen owner survive poisoned getter and replacement during the real Pager`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            var idReads = 0
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val missing = NoSuchMethodError("returned title")
            val source = object : eu.kanade.tachiyomi.source.Source {
                override val id: Long get() {
                    idReads++
                    throw NoSuchMethodError("registered ID")
                }
                override val name = "Registered browse"
                override suspend fun getPopularManga(page: Int): MangasPage {
                    started.complete(Unit)
                    release.await()
                    return MangasPage(listOf(DelayedMangaFixture("title", missing)), false)
                }
            }
            fun owner(version: String) = mockk<Extension.Installed> {
                every { pkgName } returns "trusted.browse"
                every { name } returns "Trusted browse"
                every { versionName } returns version
                every { versionCode } returns 123L
                every { sources } returns listOf(source)
            }
            val installed = MutableStateFlow(listOf(owner("old")))
            val model = screenModel(source, registeredId = 9L, installed = installed)
            backgroundScope.launch(dispatcher) { model.mangaPagerFlowFlow.collect() }
            runCurrent()
            val load = backgroundScope.async(dispatcher) { runCatching { model.mangaPagerFlowFlow.value.asSnapshot() } }
            runCurrent()
            started.await()
            installed.value = listOf(owner("new"))
            release.complete(Unit)
            runCurrent()
            val failure = assertInstanceOf(
                SourcePageException::class.java,
                load.await().exceptionOrNull(),
            )
            val marker = assertInstanceOf(SourceCompatibilityException::class.java, failure.pageError.error.cause)
            assertEquals("old", marker.identity?.versionName)
            assertEquals("trusted.browse", marker.identity?.extensionPackage)
            assertEquals(9L, marker.identity?.sourceId)
            assertEquals(0, idReads)
        }

    @Test
    fun `paging duplicate URLs never read cached or duplicate presentation fields again`() =
        runTest {
            for (mode in listOf("cached", "same-page")) {
                val original = SManga.create().apply {
                    url = "/same"
                    title = "Original"
                }
                val duplicate = DelayedMangaFixture("title", AssertionError("unneeded title"))
                    .apply { url = "/same" }
                val source = object : eu.kanade.tachiyomi.source.Source {
                    override val id = 9L
                    override val name = "Duplicates"
                    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                        if (mode == "same-page") {
                            MangasPage(listOf(original, duplicate), false)
                        } else {
                            MangasPage(listOf(if (page == 1) original else duplicate), page == 1)
                        }
                }
                val repository = mockk<MangaRepository>()
                coEvery { repository.insertNetworkManga(any()) } answers {
                    firstArg<List<Manga>>().map { it.copy(id = 7L) }
                }
                val paging = SharedSourcePagingSource(
                    source,
                    BrowseSourceScreenModel.Listing.Search("query", FilterList()),
                    1L,
                    SourceMangaSearchService(),
                    NetworkToLocalManga(repository),
                    sourceId = 9L,
                )
                val attempt = runCatching {
                    val first = paging.load(PagingSource.LoadParams.Refresh<Long>(null, 25, false))
                    if (mode == "same-page") first else paging.load(PagingSource.LoadParams.Append(2L, 25, false))
                }
                assertNull(attempt.exceptionOrNull(), mode)
                val data = assertInstanceOf(
                    PagingSource.LoadResult.Page::class.java,
                    attempt.getOrThrow(),
                ).data.map { it as Manga }
                assertEquals(if (mode == "same-page") 2 else 1, data.size)
                assertEquals(setOf("Original"), data.map { it.title }.toSet())
                assertEquals(setOf(7L), data.map { it.id }.toSet())
                assertEquals(0, duplicate.fieldReads["title"] ?: 0)
                assertEquals(0, duplicate.fieldReads["memo"] ?: 0)
                coVerify(exactly = 1) { repository.insertNetworkManga(any()) }
            }
        }

    @Test
    fun `filter preparation freezes browse owner before a replacing extension hook`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val installed = MutableStateFlow<List<Extension.Installed>>(emptyList())
            lateinit var source: eu.kanade.tachiyomi.source.Source
            fun owner(version: String) = mockk<Extension.Installed> {
                every { pkgName } returns "trusted.filter"
                every { name } returns "Trusted filter"
                every { versionName } returns version
                every { versionCode } returns 1L
                every { sources } returns listOf(source)
            }
            var filterCalls = 0
            source = object : eu.kanade.tachiyomi.source.Source {
                override val id = 9L
                override val name = "Filter replacement"
                override fun getFilterList(): FilterList {
                    filterCalls++
                    installed.value = listOf(owner("new"))
                    throw NoSuchMethodError("filter getter")
                }
            }
            installed.value = listOf(owner("old"))
            val model = screenModel(source, registeredId = 9L, installed = installed)
            backgroundScope.launch { model.mangaPagerFlowFlow.collect() }
            runCurrent()
            val result = backgroundScope.async { runCatching { model.mangaPagerFlowFlow.value.asSnapshot() } }
            runCurrent()
            val failure = assertInstanceOf(
                SourcePageException::class.java,
                result.await().exceptionOrNull(),
            )
            val marker = assertInstanceOf(SourceCompatibilityException::class.java, failure.pageError.error.cause)
            assertEquals(1, filterCalls)
            assertEquals("trusted.filter", marker.identity?.extensionPackage)
            assertEquals("old", marker.identity?.versionName)
        }

    @Test
    fun `catalog filtering preserves author query source filters and the existing Pager`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val source = DirectBrowseRejectingSource()
            val model = screenModel(source)
            val sourceFilters = FilterList(eu.kanade.tachiyomi.source.model.Filter.Header("Selected source filter"))
            model.search("author:Alice", sourceFilters)
            backgroundScope.launch(dispatcher) { model.mangaPagerFlowFlow.collect() }
            runCurrent()
            val originalPager = model.mangaPagerFlowFlow.value
            val filter = CatalogResultFilter(languageTags = setOf("zh"))

            model.setCatalogFilter(filter)
            runCurrent()
            assertEquals(filter, model.state.value.catalogFilter)
            assertEquals("author:Alice", model.state.value.toolbarQuery)
            assertSame(sourceFilters, model.state.value.listing.filters)
            assertSame(originalPager, model.mangaPagerFlowFlow.value)

            model.setCatalogFilter(CatalogResultFilter())
            runCurrent()
            assertSame(originalPager, model.mangaPagerFlowFlow.value)
        }

    @Test
    fun `loaded result projection deduplicates and hides favorites without changing raw items`() {
        val favorite = Manga.create().copy(url = "/favorite", title = "[Chinese] Favorite", favorite = true)
        val english = Manga.create().copy(url = "/english", title = "[English] English")
        val chinese = Manga.create().copy(url = "/chinese", title = "[Chinese] Chinese")
        val unknown = Manga.create().copy(url = "/unknown", title = "Unknown")
        val raw = listOf(favorite, english, chinese, chinese, unknown)
        val filter = CatalogResultFilter(languageTags = setOf("zh"))

        assertEquals(listOf(2), catalogResultVisibleIndices(raw, filter, hideInLibrary = true))
        assertEquals(
            listOf(2, 4),
            catalogResultVisibleIndices(raw, filter.copy(includeUnknownLanguage = true), hideInLibrary = true),
        )
        assertEquals(listOf(0, 2), catalogResultVisibleIndices(raw, filter, hideInLibrary = false))
        assertEquals(listOf(0, 1, 2, 4), catalogResultVisibleIndices(raw, CatalogResultFilter(), hideInLibrary = false))
        assertEquals(listOf(favorite, english, chinese, chinese, unknown), raw)
    }

    @Test
    fun `browse favorite toggles reach the repository as explicit user operations`() =
        runTest {
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
    fun `source only browse exposes filters and accepts search`() =
        runTest {
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
    fun `source only production Pager calls search with filters and subsequent pages`() =
        runTest {
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
    fun `late old Pager generation cannot replace or pollute the current listing Pager`() =
        runTest {
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
    fun `production Pager publishes shared service content without calling source directly`() =
        runTest {
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
        registeredId: Long? = null,
        installed: MutableStateFlow<List<Extension.Installed>>? = null,
    ): BrowseSourceScreenModel {
        val hostId = registeredId ?: source.id
        val preferenceStore = InMemoryPreferenceStore()
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
        val getManga = mockk<GetManga> {
            every { subscribe(any(), any()) } returns flowOf(null)
        }
        val sourceManager = mockk<SourceManager> {
            every { getOrStub(hostId) } returns source
        }
        val getIncognitoState = mockk<GetIncognitoState> {
            every { await(hostId) } returns false
        }

        return modelHost.create {
            BrowseSourceScreenModel(
                sourceId = hostId,
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
                extensionManager = mockk<ExtensionManager> {
                    every { installedExtensionsFlow } returns (installed ?: MutableStateFlow(emptyList()))
                },
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
