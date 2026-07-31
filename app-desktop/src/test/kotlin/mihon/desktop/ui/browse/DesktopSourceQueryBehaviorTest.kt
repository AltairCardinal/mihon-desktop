package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.i18n.MR
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale

class DesktopSourceQueryBehaviorTest {

    @Test
    fun `desktop source errors identify timeout and known source failures`() {
        assertEquals(
            MR.strings.desktop_source_network_timeout.localized(Locale.ENGLISH),
            desktopSourceErrorMessage(
                AppError.Network(SocketTimeoutException("Read timed out")),
                Locale.ENGLISH,
            ),
        )
        assertEquals(
            MR.strings.desktop_source_network_timeout.localized(Locale.ENGLISH),
            desktopSourceErrorMessage(
                AppError.Network(
                    IllegalStateException(
                        "request failed",
                        InterruptedIOException("Read timed out"),
                    ),
                ),
                Locale.ENGLISH,
            ),
        )
        assertEquals(
            MR.strings.desktop_ui_download_rate_limited_seconds.localized(Locale.ENGLISH, 30L),
            desktopSourceErrorMessage(AppError.RateLimited(retryAfterSeconds = 30L), Locale.ENGLISH),
        )
        assertEquals(
            MR.strings.desktop_ui_download_server_error.localized(Locale.ENGLISH, 503),
            desktopSourceErrorMessage(AppError.Server(503), Locale.ENGLISH),
        )
        assertEquals(
            MR.strings.desktop_ui_download_malformed_error.localized(Locale.ENGLISH),
            desktopSourceErrorMessage(AppError.MalformedData(), Locale.ENGLISH),
        )
    }

    @Test
    fun `desktop source errors use stable i18n messages instead of class names`() {
        assertEquals(
            MR.strings.exception_offline.localized(Locale.ENGLISH),
            desktopSourceErrorMessage(AppError.Network(), Locale.ENGLISH),
        )
        assertEquals(
            MR.strings.login.localized(Locale.SIMPLIFIED_CHINESE),
            desktopSourceErrorMessage(AppError.Authentication(), Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            MR.strings.desktop_ui_download_server_error.localized(Locale.ENGLISH, 500),
            desktopSourceErrorMessage(AppError.Server(500), Locale.ENGLISH),
        )
        assertEquals(
            MR.strings.no_results_found.localized(Locale.SIMPLIFIED_CHINESE),
            desktopSourceErrorMessage(AppError.NoResults, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            MR.strings.action_retry.localized(Locale.SIMPLIFIED_CHINESE),
            desktopSourceRecoveryActionLabel(SourceRecoveryAction.Retry, Locale.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `desktop source recovery Kotlin has no hardcoded user messages`() {
        val root = repositoryRoot()
        val source = listOf(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/DesktopSourceQueryCoordinators.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt",
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt",
        ).joinToString("\n") { root.resolve(it).readText() }

        listOf("No Internet connection", "Login", "Unknown error", "Retry").forEach { message ->
            assertEquals(false, source.contains("\"$message\""), "recovery text must come from MR: $message")
        }
    }

    @Test
    fun `later page failure stays visible and retry reloads the same page`() = runBlocking {
        val source = RetryPageSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, page = 1, query = SourceQuery.Popular)
        val failed = coordinator.load(source, page = 2, query = SourceQuery.Popular) as SourceQueryState.Content

        assertEquals(listOf("/first"), failed.items.map { it.url })
        assertInstanceOf(AppError.Server::class.java, failed.pageError?.error)
        assertEquals(SourceRecoveryAction.Retry, failed.pageError?.recoveryAction)
        assertEquals(2, failed.request.page)

        val retried = coordinator.retry(source) as SourceQueryState.Content

        assertEquals(listOf(1, 2, 2), source.requestedPages)
        assertEquals(listOf("/first", "/second"), retried.items.map { it.url })
    }

    @Test
    fun `empty first page stays empty while empty append keeps rows and retries the same page`() = runBlocking {
        val firstPageCoordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        assertInstanceOf(
            SourceQueryState.Empty::class.java,
            firstPageCoordinator.load(EmptyPageSource(emptyFirstPage = true), 1, SourceQuery.Popular),
        )

        val source = EmptyPageSource(emptyFirstPage = false)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, page = 1, query = SourceQuery.Popular)
        val emptyAppend = coordinator.load(source, page = 2, query = SourceQuery.Popular) as SourceQueryState.Content

        assertEquals(listOf("/first"), emptyAppend.items.map { it.url })
        assertEquals(2, emptyAppend.request.page)
        assertEquals(SourceRecoveryAction.Retry, emptyAppend.pageError?.recoveryAction)

        val retried = coordinator.retry(source) as SourceQueryState.Content
        assertEquals(listOf(1, 2, 2), source.requestedPages)
        assertEquals(listOf("/first", "/second"), retried.items.map { it.url })
    }

    @Test
    fun `concurrent append requests share one source load`() = runBlocking {
        val source = SerializedPagingSource(blockPopularPageTwo = true)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, page = 1, query = SourceQuery.Popular)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }
        withTimeout(2_000) { source.pageTwoStarted.await() }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }
        val pageTwoRequestsBeforeRelease = source.requests.count { it == "popular" to 2 }
        source.releasePageTwo.complete(Unit)

        val completed = withTimeout(2_000) { first.await() }
        assertEquals(completed, withTimeout(2_000) { duplicate.await() })
        assertEquals(1, pageTwoRequestsBeforeRelease)
        assertEquals(listOf("/popular-1", "/popular-2"), completed.items.map { it.url })
    }

    @Test
    fun `append cannot skip the next successful page`() = runBlocking {
        val source = SerializedPagingSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, page = 1, query = SourceQuery.Popular)

        val unchanged = coordinator.load(source, page = 3, query = SourceQuery.Popular)

        assertEquals(listOf("popular" to 1), source.requests)
        assertEquals(1, unchanged.request.page)
        assertEquals(listOf("/popular-1"), unchanged.items.map { it.url })
    }

    @Test
    fun `late append cannot contaminate a replacement first page`() = runBlocking {
        val source = SerializedPagingSource(blockOldSearchPageTwo = true)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val oldQuery = SourceQuery.Search("old", FilterList())
        coordinator.load(source, page = 1, query = oldQuery)
        val oldGeneration = coordinator.state!!.request.generation
        val oldAppend = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(source, page = 2, query = oldQuery)
        }
        withTimeout(2_000) { source.pageTwoStarted.await() }

        val replacement = coordinator.load(source, page = 1, query = SourceQuery.Search("new", FilterList()))
        source.releasePageTwo.complete(Unit)
        withTimeout(2_000) { oldAppend.await() }

        val current = coordinator.state!!
        assertEquals(oldGeneration + 1, current.request.generation)
        assertEquals(replacement.request, current.request)
        assertEquals("new", (current.request.query as SourceQuery.Search).query)
        assertEquals(listOf("/new-1"), current.items.map { it.url })
    }

    @Test
    fun `started callback failure retires pending append and restores previous content`() = runBlocking {
        val source = SerializedPagingSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val first = coordinator.load(source, page = 1, query = SourceQuery.Popular)

        val failure = runCatching {
            coordinator.load(source, page = 2, query = SourceQuery.Popular) { error("callback failed") }
        }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, failure)
        assertFalse(coordinator.state!!.isLoading)
        assertEquals(first, coordinator.state)
        val recovered = withTimeout(2_000) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }
        assertEquals(listOf("/popular-1", "/popular-2"), recovered.items.map { it.url })
    }

    @Test
    fun `owner cancellation wakes waiter retires loading and permits reissue`() = runBlocking {
        val source = SerializedPagingSource(blockPopularPageTwo = true)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, page = 1, query = SourceQuery.Popular)
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }
        withTimeout(2_000) { source.pageTwoStarted.await() }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { coordinator.load(source, page = 2, query = SourceQuery.Popular) }
        }

        withTimeout(2_000) { owner.cancelAndJoin() }
        val waiterFailure = withTimeout(2_000) { waiter.await().exceptionOrNull() }
        source.releasePageTwo.complete(Unit)

        assertInstanceOf(CancellationException::class.java, waiterFailure)
        assertFalse(coordinator.state!!.isLoading)
        val recovered = withTimeout(2_000) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }
        assertEquals(2, source.requests.count { it == "popular" to 2 })
        assertEquals(listOf("/popular-1", "/popular-2"), recovered.items.map { it.url })
    }

    @Test
    fun `waiter cancellation does not cancel append owner`() = runBlocking {
        val source = SerializedPagingSource(blockPopularPageTwo = true)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, page = 1, query = SourceQuery.Popular)
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }
        withTimeout(2_000) { source.pageTwoStarted.await() }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.load(source, page = 2, query = SourceQuery.Popular)
        }

        withTimeout(2_000) { waiter.cancelAndJoin() }
        val ownerStillActive = owner.isActive
        source.releasePageTwo.complete(Unit)
        val completed = withTimeout(2_000) { owner.await() }

        assertTrue(ownerStillActive)
        assertEquals(1, source.requests.count { it == "popular" to 2 })
        assertEquals(listOf("/popular-1", "/popular-2"), completed.items.map { it.url })
    }

    @Test
    fun `mutable filter state cannot orphan or duplicate pending append`() = runBlocking {
        val filter = object : Filter.Text("Title") {}
        val query = SourceQuery.Search("old", FilterList(listOf(filter)))
        val source = SerializedPagingSource(blockOldSearchPageTwo = true)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, page = 1, query = query)
        val owner = async(start = CoroutineStart.UNDISPATCHED) { coordinator.load(source, page = 2, query = query) }
        withTimeout(2_000) { source.pageTwoStarted.await() }

        filter.state = "changed while loading"
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { coordinator.load(source, page = 2, query = query) }
        val waiterCompletedBeforeOwner = waiter.isCompleted
        source.releasePageTwo.complete(Unit)
        withTimeout(2_000) { owner.await() }
        withTimeout(2_000) { waiter.await() }

        val next = withTimeout(2_000) { coordinator.load(source, page = 3, query = query) }
        assertFalse(waiterCompletedBeforeOwner)
        assertEquals(1, source.requests.count { it == "old" to 2 })
        assertEquals(3, next.request.page)
    }

    @Test
    fun `authentication recovery identity includes its captured request`() = runBlocking {
        val source = AuthenticationSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, page = 1, query = SourceQuery.Popular)

        val intent = assertInstanceOf(
            DesktopSourceRecoveryIntent.OpenLogin::class.java,
            coordinator.recoveryIntent(source),
        )
        assertEquals(source.baseUrl, intent.url)
        assertEquals(coordinator.state!!.request, intent.request)
        assertNotEquals(intent, intent.copy(request = intent.request.copy(generation = intent.request.generation + 1)))
    }

    private class AuthenticationSource : eu.kanade.tachiyomi.source.online.HttpSource() {
        override val id = 7L
        override val name = "Login source"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://example.com"
        override val client = OkHttpClient.Builder().addInterceptor { throw HttpException(403) }.build()

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

    @Test
    fun `late global search generation cannot overwrite current UI state`() = runBlocking {
        val source = HangingSearchSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())

        val old = async { coordinator.search(listOf(source), "old") }
        source.oldStarted.await()
        val current = async { coordinator.search(listOf(source), "new") }
        source.newStarted.await()
        source.newResult.complete(MangasPage(listOf(manga("/new", "New")), false))
        current.await()
        source.oldResult.complete(MangasPage(listOf(manga("/old", "Old")), false))
        old.await()

        val state = coordinator.state
        val result = state.queryStates.getValue(source.id) as SourceQueryState.Content
        assertEquals(listOf("/new"), result.items.map { it.url })
        assertEquals(false, state.isSearching)
        assertEquals("new", (result.request.query as SourceQuery.Search).query)
    }

    private class RetryPageSource : CatalogueSource {
        val requestedPages = mutableListOf<Int>()

        override val id = 1L
        override val name = "Retry"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            requestedPages += page
            return when (requestedPages.count { it == page }) {
                1 -> if (page == 1) MangasPage(listOf(manga("/first", "First")), true) else throw HttpException(500)
                else -> MangasPage(listOf(manga("/second", "Second")), false)
            }
        }

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class EmptyPageSource(private val emptyFirstPage: Boolean) : CatalogueSource {
        val requestedPages = mutableListOf<Int>()
        override val id = 8L
        override val name = "Empty page"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            requestedPages += page
            return when {
                emptyFirstPage -> MangasPage(emptyList(), false)
                page == 1 -> MangasPage(listOf(manga("/first", "First")), true)
                requestedPages.count { it == page } == 1 -> MangasPage(emptyList(), false)
                else -> MangasPage(listOf(manga("/second", "Second")), false)
            }
        }

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class HangingSearchSource : CatalogueSource {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<MangasPage>()
        val newResult = CompletableDeferred<MangasPage>()

        override val id = 2L
        override val name = "Hanging"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val (started, result) = if (query == "old") oldStarted to oldResult else newStarted to newResult
            started.complete(Unit)
            return withContext(NonCancellable) { result.await() }
        }
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private class SerializedPagingSource(
        private val blockPopularPageTwo: Boolean = false,
        private val blockOldSearchPageTwo: Boolean = false,
    ) : CatalogueSource {
        val requests = mutableListOf<Pair<String, Int>>()
        val pageTwoStarted = CompletableDeferred<Unit>()
        val releasePageTwo = CompletableDeferred<Unit>()

        override val id = 3L
        override val name = "Serialized"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = result("popular", page, blockPopularPageTwo)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            result(query, page, blockOldSearchPageTwo && query == "old")

        private suspend fun result(query: String, page: Int, blockPageTwo: Boolean): MangasPage {
            requests += query to page
            if (page == 2 && blockPageTwo) {
                pageTwoStarted.complete(Unit)
                releasePageTwo.await()
            }
            return MangasPage(listOf(manga("/$query-$page", "$query $page")), true)
        }

        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun repositoryRoot(): File {
            var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
            while (current != null && !current.resolve("settings.gradle.kts").isFile) current = current.parentFile
            return requireNotNull(current)
        }

        fun manga(url: String, title: String) = SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
