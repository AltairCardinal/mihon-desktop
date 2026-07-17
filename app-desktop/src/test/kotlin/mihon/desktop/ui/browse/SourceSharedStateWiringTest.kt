package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.DesktopCookieJar
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopAuthenticatedSessionCommitter
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.domain.error.AppError
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceLoginState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SourceSharedStateWiringTest {

    @Test
    fun `source projector preserves content while a later page loads and fails`() {
        val request = SourcePageRequest(1, 2, 7, SourceQuery.Popular)
        val item = manga("/kept")

        val loading = SourceBrowseStateProjector.project(SourceQueryState.Loading(request, listOf(item)))
        val failed = SourceBrowseStateProjector.project(
            SourceQueryState.Content(request, listOf(item), false, pageError = tachiyomi.domain.source.service.SourcePageError(AppError.Server(500), tachiyomi.domain.source.service.SourceRecoveryAction.Retry)),
        )

        assertEquals(listOf("/kept"), loading.items.map(SManga::url))
        assertTrue(loading.loading)
        assertEquals(listOf("/kept"), failed.items.map(SManga::url))
        assertInstanceOf(AppError.Server::class.java, failed.pageError?.error)
    }

    @Test
    fun `source projector distinguishes first load from a successful empty page`() {
        val request = SourcePageRequest(1, 1, 1, SourceQuery.Popular)
        val screen = SourceBrowseScreen(1)
        assertTrue(screen.projectState(SourceQueryState.Loading(request)).loading)
        assertFalse(screen.projectState(SourceQueryState.Empty(request)).loading)
        assertTrue(screen.projectState(SourceQueryState.Empty(request)).empty)
    }

    @Test
    fun `source coordinator publishes shared loading and retries the exact request`() = runBlocking {
        val source = PagingSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val observed = mutableListOf<SourceQueryState>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            coordinator.states.filterNotNull().collect(observed::add)
        }

        coordinator.load(source, 1, SourceQuery.Popular)
        coordinator.load(source, 2, SourceQuery.Popular)
        val failedRequest = coordinator.state!!.request
        coordinator.retry(source)
        collector.cancelAndJoin()

        assertInstanceOf(SourceQueryState.Loading::class.java, observed.first())
        assertTrue(observed.any { it.request == failedRequest && it.isLoading && it.items.isNotEmpty() })
        assertEquals(failedRequest, coordinator.state!!.request)
        assertEquals(listOf(1, 2, 2), source.pages)
    }

    @Test
    fun `first page retry preserves the failed request generation`() = runBlocking {
        val source = FirstPageRetrySource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())

        coordinator.load(source, 1, SourceQuery.Popular)
        val failedRequest = coordinator.state!!.request
        coordinator.retry(source)

        assertEquals(failedRequest, coordinator.state!!.request)
    }

    @Test
    fun `authenticated login keeps its captured request and never retries a newer generation`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, 1, SourceQuery.Search("old", FilterList()))
        val captured = coordinator.state!!.request
        val intent = DesktopSourceRecoveryIntent.OpenLogin("https://example.com/path", captured)
        coordinator.load(source, 1, SourceQuery.Search("new", FilterList()))
        val factory = DesktopSourceLoginSessionFactory(
            committer = AuthenticatedSessionCommitter { _, _ -> },
            browserOpener = DesktopBrowserOpener { _, ticket ->
                ticket.complete(session("session", "secret"))
                true
            },
        )
        val controller = DesktopSourceLoginController(factory, coordinator)

        val result = controller.login(source, intent)

        assertEquals(SourceLoginState.Authenticated(setOf("session"), 1), result)
        assertEquals(captured, intent.request)
        assertEquals(listOf("old", "new"), source.queries)
    }

    @Test
    fun `cookie header commits through the real jar before exact source retry`() = runBlocking {
        val jar = DesktopCookieJar()
        val source = CookieQuerySource(jar)
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, 1, SourceQuery.Search("captured", FilterList()))
        val captured = coordinator.state!!.request
        val factory = DesktopSourceLoginSessionFactory(
            DesktopAuthenticatedSessionCommitter(jar),
            DesktopBrowserOpener { _, _ -> true },
        )
        val controller = DesktopSourceLoginController(factory, coordinator)
        val login = async(start = CoroutineStart.UNDISPATCHED) {
            controller.login(source, DesktopSourceRecoveryIntent.OpenLogin(source.url.toString(), captured))
        }

        assertTrue(controller.submitCookies("session=secret; auth_token=token"))

        assertEquals(SourceLoginState.Authenticated(setOf("auth_token", "session"), 2), login.await())
        assertEquals(listOf("auth_token", "session"), jar.get(source.url).map { it.name }.sorted())
        assertEquals(listOf(1, 1), source.requests.map { it.page })
        assertEquals(listOf("captured", "captured"), source.requests.map { (it.query as SourceQuery.Search).query })
        assertSame((captured.query as SourceQuery.Search).filters, source.filters.last())
        assertEquals(captured, coordinator.state!!.request)
        assertEquals(setOf("auth_token", "session"), source.cookies.last())
        assertEquals(null, CloudflareChallengeManager().tryReceive())
    }

    @Test
    fun `cookie header parser rejects unsafe input without exposing values`() {
        val url = "https://example.com/path".toHttpUrl()
        listOf("", "missing", "=secret", "session=", "session=one; session=two", "bad name=secret")
            .forEach { assertEquals(null, DesktopSourceCookieHeaderParser.parse(it, url)) }
        val parsed = requireNotNull(DesktopSourceCookieHeaderParser.parse("session=secret", url))
        assertFalse(parsed.toString().contains("secret"))
    }

    @Test
    fun `non authenticated login outcomes never retry the source`() = runBlocking {
        suspend fun outcome(
            opener: DesktopBrowserOpener,
            committer: AuthenticatedSessionCommitter = AuthenticatedSessionCommitter { _, _ -> },
            timeout: Long = 100,
        ): SourceLoginState {
            val source = QueryFailureSource()
            val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
            coordinator.load(source, 1, SourceQuery.Search("once", FilterList()))
            val intent = DesktopSourceRecoveryIntent.OpenLogin("https://example.com", coordinator.state!!.request)
            val result = DesktopSourceLoginController(
                DesktopSourceLoginSessionFactory(committer, opener),
                coordinator,
                timeout,
            ).login(source, intent)
            assertEquals(listOf("once"), source.queries)
            return result
        }

        assertEquals(SourceLoginState.BrowserUnavailable, outcome(DesktopBrowserOpener { _, _ -> false }))
        assertEquals(SourceLoginState.Cancelled, outcome(DesktopBrowserOpener { _, ticket -> ticket.cancel(); true }))
        assertEquals(SourceLoginState.TimedOut, outcome(DesktopBrowserOpener { _, _ -> true }, timeout = 1))
        assertInstanceOf(
            SourceLoginState.InvalidCookies::class.java,
            outcome(DesktopBrowserOpener { _, ticket ->
                ticket.complete(session("session", "secret").copy(cookies = session("session", "secret").cookies.map { it.copy(domain = "evil.test") }))
                true
            }),
        )
        assertEquals(
            SourceLoginState.CommitFailed,
            outcome(
                DesktopBrowserOpener { _, ticket -> ticket.complete(session("session", "secret")); true },
                AuthenticatedSessionCommitter { _, _ -> error("commit failed") },
            ),
        )
    }

    private fun session(name: String, value: String) = AuthenticatedSession(
        listOf(
            AuthenticatedCookie(
                name,
                value,
                "example.com",
                true,
                "/",
                null,
                secure = true,
                httpOnly = false,
            ),
        ),
    )

    @Test
    fun `screen recovery never retries a newer request than its intent`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val actions = DesktopSourceRecoveryActionAdapter { _, _ -> true }
        val controller = SourceBrowseRecoveryController(coordinator, actions)
        val screen = SourceBrowseScreen(source.id)

        coordinator.load(source, 1, SourceQuery.Search("old", FilterList()))
        val oldIntent = coordinator.recoveryIntent(source)
        coordinator.load(source, 1, SourceQuery.Search("new", FilterList()))
        screen.recover(controller, source, oldIntent)

        assertEquals(listOf("old", "new"), source.queries)
    }

    @Test
    fun `inline state collector cannot hold the coordinator lock against a reentrant load`() = runBlocking {
        val source = NamedSource(4, "Concurrent")
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val screen = SourceBrowseScreen(source.id)
        val collectorEntered = CountDownLatch(1)
        val reentrantLoadCompleted = CountDownLatch(1)
        val progressedWhileCollectorBlocked = AtomicBoolean(false)

        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            screen.queryStates(coordinator).filterNotNull().collect { state ->
                if ((state.request.query as? SourceQuery.Search)?.query == "old" && state.isLoading) {
                    collectorEntered.countDown()
                    progressedWhileCollectorBlocked.set(reentrantLoadCompleted.await(2, TimeUnit.SECONDS))
                }
            }
        }

        val oldLoad = async(Dispatchers.Default) {
            coordinator.load(source, 1, SourceQuery.Search("old", FilterList()))
        }
        assertTrue(collectorEntered.await(5, TimeUnit.SECONDS))
        val reentrantLoad = async(Dispatchers.Default) {
            coordinator.load(source, 1, SourceQuery.Search("new", FilterList()))
            reentrantLoadCompleted.countDown()
        }
        oldLoad.await()
        reentrantLoad.await()
        collector.cancelAndJoin()

        assertTrue(progressedWhileCollectorBlocked.get())
        assertEquals("new", (coordinator.state!!.request.query as SourceQuery.Search).query)
    }

    @Test
    fun `screen state flow never observes an old result after generation two`() = runBlocking {
        val source = InterleavingSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val screen = SourceBrowseScreen(source.id)
        val observedGenerations = Collections.synchronizedList(mutableListOf<Long>())
        val generationTwoObserved = CountDownLatch(1)
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            screen.queryStates(coordinator).filterNotNull().collect { state ->
                observedGenerations += state.request.generation
                if (state.request.generation == 2L) generationTwoObserved.countDown()
            }
        }

        val oldLoad = async(Dispatchers.Default) {
            coordinator.load(source, 1, SourceQuery.Search("old", FilterList()))
        }
        assertTrue(source.oldRequestStarted.await(5, TimeUnit.SECONDS))
        coordinator.load(source, 1, SourceQuery.Search("new", FilterList()))
        assertTrue(generationTwoObserved.await(5, TimeUnit.SECONDS))
        source.releaseOldResult.countDown()
        oldLoad.await()
        collector.cancelAndJoin()

        assertEquals(2L, coordinator.state!!.request.generation)
        assertEquals(listOf("/new"), coordinator.state!!.items.map(SManga::url))
        val generationTwoIndex = observedGenerations.indexOfFirst { it == 2L }
        assertTrue(generationTwoIndex >= 0)
        assertTrue(observedGenerations.drop(generationTwoIndex).all { it == 2L })
    }

    @Test
    fun `stamped publisher rejects an older publication that arrives late`() {
        val publisher = SourceQueryStatePublisher()
        val old = SourceQueryState.Loading(SourcePageRequest(4, 1, 1, SourceQuery.Popular))
        val current = SourceQueryState.Loading(SourcePageRequest(4, 1, 2, SourceQuery.Latest))

        publisher.publish(StampedSourceQueryState(2, current))
        publisher.publish(StampedSourceQueryState(1, old))

        assertEquals(current, publisher.current.state)
    }

    private class PagingSource : NamedSource(1, "Paging") {
        val pages = mutableListOf<Int>()
        override suspend fun getPopularManga(page: Int): MangasPage {
            pages += page
            return when {
                page == 1 -> MangasPage(listOf(manga("/kept")), true)
                pages.count { it == 2 } == 1 -> throw HttpException(500)
                else -> MangasPage(listOf(manga("/next")), false)
            }
        }
    }

    private class FirstPageRetrySource : NamedSource(2, "First page") {
        private var attempts = 0
        override suspend fun getPopularManga(page: Int): MangasPage =
            if (attempts++ == 0) throw HttpException(500) else MangasPage(listOf(manga("/retry")), false)
    }

    private class QueryFailureSource : NamedSource(3, "Queries") {
        val queries = mutableListOf<String>()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            queries += query
            throw HttpException(500)
        }
    }

    private class InterleavingSource : NamedSource(4, "Interleaving") {
        val oldRequestStarted = CountDownLatch(1)
        val releaseOldResult = CountDownLatch(1)

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (query == "old") {
                oldRequestStarted.countDown()
                releaseOldResult.await(5, TimeUnit.SECONDS)
            }
            return MangasPage(listOf(manga("/$query")), false)
        }
    }

    private class CookieQuerySource(val jar: DesktopCookieJar) : NamedSource(5, "Cookies") {
        val url = "https://example.com/path".toHttpUrl()
        val requests = mutableListOf<SourcePageRequest>()
        val cookies = mutableListOf<Set<String>>()
        val filters = mutableListOf<FilterList>()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val names = jar.get(url).mapTo(sortedSetOf()) { it.name }
            cookies += names
            requests += SourcePageRequest(id, page, 1, SourceQuery.Search(query, filters))
            this.filters += filters
            if (names.isEmpty()) throw HttpException(403)
            return MangasPage(listOf(manga("/authenticated")), false)
        }
    }

    private open class NamedSource(override val id: Long, override val name: String) : CatalogueSource {
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun manga(url: String) = SManga.create().apply {
            this.url = url
            title = url
            initialized = true
        }
    }
}
