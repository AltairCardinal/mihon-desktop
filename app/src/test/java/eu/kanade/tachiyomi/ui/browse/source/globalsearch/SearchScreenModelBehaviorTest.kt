package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceRecoveryAction

class SearchScreenModelBehaviorTest {

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `late old generation cannot replace or close the current loading result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = HangingSearchSource()
        val model = screenModel(source)

        model.updateSearchQuery("old")
        model.search()
        runCurrent()
        source.oldStarted.await()

        model.updateSearchQuery("new")
        model.search()
        runCurrent()
        source.newStarted.await()

        source.oldResult.complete(MangasPage(listOf(manga("/old", "Old")), false))
        runCurrent()
        assertEquals(SearchItemResult.Loading, model.state.value.items[source])

        source.newResult.complete(MangasPage(listOf(manga("/new", "New")), false))
        runCurrent()

        val result = assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[source])
        assertEquals(listOf("/new"), result.result.map { it.url })
    }

    @Test
    fun `retry performs the same ScreenModel query again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = RetrySearchSource()
        val model = screenModel(source)

        model.updateSearchQuery("retry-me")
        model.search()
        runCurrent()

        val failed = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[source])
        assertInstanceOf(AppError.Server::class.java, failed.pageError.error)
        assertEquals(SourceRecoveryAction.Retry, failed.pageError.recoveryAction)

        model.retry()
        runCurrent()

        val retried = assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[source])
        assertEquals(true, retried.isEmpty)
        assertEquals(2, source.requestCount)
    }

    @Test
    fun `production global search uses shared failure and recovery without direct source call`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = DirectSearchRejectingSource()
        val service = mockk<SourceMangaSearchService>()
        val sentinelError = AppError.MalformedData(IllegalStateException("shared-service-sentinel"))
        coEvery { service.loadPageResult(source, any()) } answers {
            SourcePageResult.Failure(secondArg(), sentinelError, SourceRecoveryAction.OpenLogin)
        }
        val model = screenModel(source, service)

        model.updateSearchQuery("shared-only")
        model.search()
        runCurrent()

        val failure = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[source])
        assertSame(sentinelError, failure.pageError.error)
        assertEquals(SourceRecoveryAction.OpenLogin, failure.pageError.recoveryAction)
        coVerify(exactly = 1) {
            service.loadPageResult(
                source,
                match {
                    it.sourceId == source.id &&
                        it.page == 1 &&
                        (it.query as? SourceQuery.Search)?.query == "shared-only"
                },
            )
        }
    }

    private fun kotlinx.coroutines.test.TestScope.screenModel(
        source: CatalogueSource,
        sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
    ): SearchScreenModel {
        val preferences = preferences()
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
        return object : SearchScreenModel(
            sourcePreferences = preferences,
            sourceManager = mockk<SourceManager>(),
            extensionManager = mockk<ExtensionManager>(),
            networkToLocalManga = NetworkToLocalManga(repository),
            getManga = mockk<GetManga>(),
            preferences = preferences,
            sourceMangaSearchService = sourceMangaSearchService,
            workerScope = this,
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
        ) {
            override fun getEnabledSources() = listOf(source)
        }
    }

    private fun preferences(): SourcePreferences {
        val setPreference = { value: Set<String> ->
            mockk<Preference<Set<String>>> {
                every { get() } returns value
            }
        }
        val booleanPreference = mockk<Preference<Boolean>> {
            every { get() } returns false
            every { changes() } returns flowOf(false)
        }
        return mockk {
            every { enabledLanguages() } returns setPreference(setOf("en"))
            every { disabledSources() } returns setPreference(emptySet())
            every { pinnedSources() } returns setPreference(emptySet())
            every { globalSearchFilterState() } returns booleanPreference
        }
    }

    private class HangingSearchSource : BaseCatalogueSource() {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<MangasPage>()
        val newResult = CompletableDeferred<MangasPage>()

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val (started, result) = if (query == "old") oldStarted to oldResult else newStarted to newResult
            started.complete(Unit)
            return withContext(NonCancellable) { result.await() }
        }
    }

    private class RetrySearchSource : BaseCatalogueSource() {
        var requestCount = 0

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            requestCount += 1
            if (requestCount == 1) throw HttpException(500)
            return MangasPage(emptyList(), false)
        }
    }

    private class DirectSearchRejectingSource : BaseCatalogueSource() {
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            error("Production global search bypassed SourceMangaSearchService")
    }

    private abstract class BaseCatalogueSource : CatalogueSource {
        override val id = 9L
        override val name = "Behavior source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
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
