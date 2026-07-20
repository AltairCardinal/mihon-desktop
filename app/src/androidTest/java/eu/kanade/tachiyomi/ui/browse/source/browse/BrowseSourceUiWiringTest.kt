package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.map
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.presentation.core.components.material.Scaffold
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class BrowseSourceUiWiringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun browseErrorRetryClicksRealPagingRetryBoundary() {
        val source = RetryCatalogueSource()
        val pagingFlow = pagingFlow(source)

        composeTestRule.setContent {
            MaterialTheme {
                Navigator(RootScreen) { navigator ->
                    BrowseSourceScreenContent(
                        source = source,
                        mangaList = pagingFlow.collectAsLazyPagingItems(),
                        navigator = navigator,
                        columns = GridCells.Fixed(1),
                        displayMode = LibraryDisplayMode.List,
                        snackbarHostState = remember { SnackbarHostState() },
                        contentPadding = PaddingValues(),
                        onWebViewClick = {},
                        onHelpClick = {},
                        onLocalSourceHelpClick = {},
                        onMangaClick = {},
                        onMangaLongClick = {},
                    )
                }
            }
        }

        composeTestRule.waitUntil(10_000) { source.requestCount.get() == 1 }
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitUntil(10_000) { source.requestCount.get() == 2 }

        assertEquals(2, source.requestCount.get())
    }

    @Test
    fun browseOpenLoginClicksThroughRealScreenWiringIntoVoyagerWebView() {
        val source = LoginCatalogueSource()
        val pagingFlow = pagingFlow(source)
        lateinit var navigator: Navigator

        composeTestRule.setContent {
            MaterialTheme {
                Navigator(RootScreen) { currentNavigator ->
                    navigator = currentNavigator
                    BrowseSourceScreenContent(
                        source = source,
                        mangaList = pagingFlow.collectAsLazyPagingItems(),
                        navigator = currentNavigator,
                        columns = GridCells.Fixed(1),
                        displayMode = LibraryDisplayMode.List,
                        snackbarHostState = remember { SnackbarHostState() },
                        contentPadding = PaddingValues(),
                        onWebViewClick = {},
                        onHelpClick = {},
                        onLocalSourceHelpClick = {},
                        onMangaClick = {},
                        onMangaLongClick = {},
                    )
                }
            }
        }

        composeTestRule.waitUntil(10_000) { source.requestCount.get() == 1 }
        composeTestRule.onNode(hasText("Login") and hasClickAction()).performClick()

        composeTestRule.runOnIdle {
            assertTrue(navigator.lastItem is WebViewScreen)
        }
    }

    @Test
    fun firstPageEmptyFromProductionPagingShowsLocalizedNoResults() {
        val source = EmptyFirstPageCatalogueSource()

        showBrowse(source, productionPagingFlow(source))

        composeTestRule.waitForText("No results found")
    }

    @Test
    fun appendEmptyKeepsRowsAndVisibleRetryRecoversTheSameProductionPage() {
        val source = RecoveringAppendCatalogueSource()

        showBrowse(source, productionPagingFlow(source))

        composeTestRule.waitForText("First row")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Last row"))
        composeTestRule.waitUntil(10_000) { source.pageTwoRequests.get() == 1 }
        composeTestRule.waitForText("Retry")
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitUntil(10_000) { source.pageTwoRequests.get() == 2 }
        composeTestRule.waitForText("Recovered row")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("First row"))
        composeTestRule.waitForText("First row")
    }

    private fun showBrowse(
        source: CatalogueSource,
        pagingFlow: Flow<PagingData<StateFlow<Manga>>>,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                Navigator(RootScreen) { navigator ->
                    val snackbarHostState = remember { SnackbarHostState() }
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                    ) { contentPadding ->
                        BrowseSourceScreenContent(
                            source = source,
                            mangaList = pagingFlow.collectAsLazyPagingItems(),
                            navigator = navigator,
                            columns = GridCells.Fixed(1),
                            displayMode = LibraryDisplayMode.List,
                            snackbarHostState = snackbarHostState,
                            contentPadding = contentPadding,
                            onWebViewClick = {},
                            onHelpClick = {},
                            onLocalSourceHelpClick = {},
                            onMangaClick = {},
                            onMangaLongClick = {},
                        )
                    }
                }
            }
        }
    }

    private fun productionPagingFlow(source: CatalogueSource): Flow<PagingData<StateFlow<Manga>>> {
        val repository = Proxy.newProxyInstance(
            MangaRepository::class.java.classLoader,
            arrayOf(MangaRepository::class.java),
        ) { _, method, args ->
            if (method.name == "insertNetworkManga") args?.first() else error("Unexpected ${method.name}")
        } as MangaRepository
        return Pager(PagingConfig(pageSize = 1, prefetchDistance = 1)) {
            SharedSourcePagingSource(
                source = source,
                listing = BrowseSourceScreenModel.Listing.Popular,
                generation = 1,
                sourceMangaSearchService = SourceMangaSearchService(),
                networkToLocalManga = NetworkToLocalManga(repository),
            )
        }.flow.map { pagingData -> pagingData.map { MutableStateFlow(it) as StateFlow<Manga> } }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForText(text: String) {
        waitUntil(10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pagingFlow(source: CatalogueSource): Flow<PagingData<StateFlow<Manga>>> {
        return Pager(PagingConfig(pageSize = 25)) {
            FailingThenEmptyPagingSource(source)
        }.flow
    }

    private class FailingThenEmptyPagingSource(
        private val source: CatalogueSource,
    ) : PagingSource<Int, StateFlow<Manga>>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, StateFlow<Manga>> {
            val result = SourceMangaSearchService().loadPageResult(
                source = source,
                request = SourcePageRequest(
                    sourceId = source.id,
                    page = params.key ?: 1,
                    generation = 1,
                    query = SourceQuery.Popular,
                ),
            )
            return when (result) {
                is SourcePageResult.Content, is SourcePageResult.Empty ->
                    LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
                is SourcePageResult.Failure -> LoadResult.Error(
                    SourcePageException(SourcePageError(result.error, result.recoveryAction)),
                )
            }
        }

        override fun getRefreshKey(state: PagingState<Int, StateFlow<Manga>>): Int? = null
    }

    private data object RootScreen : Screen() {
        @Composable
        override fun Content() = Unit
    }

    private class RetryCatalogueSource : BaseCatalogueSource() {
        val requestCount = AtomicInteger()

        override suspend fun getPopularManga(page: Int): MangasPage {
            if (requestCount.incrementAndGet() == 1) throw HttpException(500)
            return MangasPage(emptyList(), false)
        }
    }

    private class EmptyFirstPageCatalogueSource : BaseCatalogueSource() {
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    }

    private class RecoveringAppendCatalogueSource : BaseCatalogueSource() {
        val pageTwoRequests = AtomicInteger()

        override suspend fun getPopularManga(page: Int): MangasPage = when (page) {
            1 -> MangasPage(
                (1..30).map {
                    manga(
                        "/row-$it",
                        when (it) {
                            1 -> "First row"
                            30 -> "Last row"
                            else -> "Row $it"
                        },
                    )
                },
                true,
            )
            2 -> if (pageTwoRequests.incrementAndGet() == 1) {
                MangasPage(emptyList(), false)
            } else {
                MangasPage(listOf(manga("/recovered", "Recovered row")), false)
            }
            else -> error("Unexpected page $page")
        }
    }

    private class LoginCatalogueSource : HttpSource() {
        val requestCount = AtomicInteger()
        override val id = 8L
        override val name = "Login source"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://example.com"
        override val client = OkHttpClient()

        override suspend fun getPopularManga(page: Int): MangasPage {
            requestCount.incrementAndGet()
            throw HttpException(403)
        }

        override fun popularMangaRequest(page: Int) = Request.Builder().url(baseUrl).build()
        override fun popularMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
        override fun latestUpdatesParse(response: Response) = MangasPage(emptyList(), false)
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)
        override fun searchMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun mangaDetailsParse(response: Response) = SManga.create()
        override fun chapterListParse(response: Response) = emptyList<SChapter>()
        override fun chapterPageParse(response: Response) = SChapter.create()
        override fun pageListParse(response: Response) = emptyList<Page>()
        override fun imageUrlParse(response: Response) = ""
    }

    private abstract class BaseCatalogueSource : CatalogueSource {
        override val id = 7L
        override val name = "Retry source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
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
