package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.DesktopCookieJar
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import dev.icerock.moko.resources.StringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.mockk.every
import io.mockk.mockk
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopAuthenticatedSessionCommitter
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.error.AppError
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceLoginState
import tachiyomi.i18n.MR
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.File

class SourceSharedStateWiringTest {

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `browse tab extensions action renders extension list screen`() = runBlocking {
        val extensionApi = mockk<mihon.desktop.extension.DesktopExtensionApi>()
        val extensionManager = mockk<mihon.desktop.extension.DesktopExtensionManager> {
            every { getInstalledExtensions() } returns emptyList()
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(emptyList())
            every { this@mockk.extensionApi } returns extensionApi
            every { this@mockk.extensionManager } returns extensionManager
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                TabNavigator(BrowseTab) {
                    CurrentTab()
                }
            }
        }
        scene.render()

        val extensionsAction = requireNotNull(
            scene.semanticsOwners
                .flatMap { flatten(it.rootSemanticsNode) }
                .firstOrNull {
                    it.config.contains(SemanticsActions.OnClick) &&
                        it.config.toString().contains(MR.strings.label_extensions.localized())
                },
        )

        assertTrue(requireNotNull(extensionsAction.config[SemanticsActions.OnClick].action).invoke())
        scene.render()

        assertTrue(
            scene.semanticsOwners
                .flatMap { flatten(it.rootSemanticsNode) }
                .any { it.config.toString().contains("Reload installed") },
        )
        scene.close()
    }

    @Test
    fun `source login copy uses MR key identity and maps terminal feedback`() {
        val resources = listOf(
            MR.strings.login,
            MR.strings.desktop_source_login_description,
            MR.strings.desktop_source_login_cookie_header,
            MR.strings.desktop_source_login_cookie_placeholder,
            MR.strings.desktop_source_login_invalid_header,
            MR.strings.desktop_source_login_browser_unavailable,
            MR.strings.desktop_source_login_timed_out,
            MR.strings.desktop_source_login_invalid_cookies,
            MR.strings.desktop_source_login_commit_failed,
            MR.strings.action_ok,
            MR.strings.action_cancel,
            MR.strings.action_close,
        )
        val tokens = resources.mapIndexed { index, resource -> resource to "token-$index" }.toMap()
        val copy = desktopSourceLoginCopy { resource: StringResource -> tokens.getValue(resource) }

        assertEquals(
            tokens.values.toList(),
            listOf(
                copy.title, copy.description, copy.cookieHeaderLabel, copy.cookieHeaderPlaceholder,
                copy.invalidHeader, copy.browserUnavailable, copy.timedOut, copy.invalidCookies,
                copy.commitFailed, copy.submit, copy.cancel, copy.close,
            ),
        )
        DesktopSourceLoginFeedback.entries.forEachIndexed { index, feedback ->
            assertEquals("token-${index + 4}", copy.feedback(feedback))
        }
    }

    @Test
    fun `stale source login events cannot replace or close the current attempt`() {
        val attempt = DesktopSourceLoginAttempt()
        val newer = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "new.test")
        var state: DesktopSourceLoginUiState? = DesktopSourceLoginUiState(attempt, "old.test")
        val actions = DesktopSourceLoginUiActions(
            { _, _ -> error("stale submit") },
            { error("stale cancel") },
        )
        val stale = sourceLoginDialogEvents(requireNotNull(state), { state }, actions) { state = it }
        val staleTerminal = sourceLoginDialogEvents(requireNotNull(state).copy(terminal = true), { state }, actions) { state = it }
        state = newer
        stale.edit("stale=secret")
        stale.submit()
        stale.dismiss()
        staleTerminal.dismiss()

        assertSame(newer, state)
        var acceptsCancel = false
        var cancelCalls = 0
        val currentActions = DesktopSourceLoginUiActions({ _, _ -> false }) { acceptsCancel.also { cancelCalls++ } }
        val current = sourceLoginDialogEvents(newer, { state }, currentActions) { state = it }
        current.edit("session=secret")
        assertEquals("session=secret", state?.cookieHeader)
        current.submit()
        assertEquals(DesktopSourceLoginFeedback.InvalidHeader, state?.feedback)
        val rejected = requireNotNull(state)
        current.dismiss()
        assertSame(rejected, state)
        acceptsCancel = true
        current.dismiss()
        assertEquals(null, state)
        state = newer.copy(terminal = true)
        sourceLoginDialogEvents(requireNotNull(state), { state }, currentActions) { state = it }.dismiss()
        assertEquals(null, state)
        assertEquals(2, cancelCalls)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `source browse content mounts the real active and terminal login dialog`() = runBlocking {
        val copy = desktopSourceLoginCopy { it.localized() }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(emptyList())
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { sourceLoginSessionFactory } returns DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, _ -> false },
            )
        }
        val active = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "source.test")
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun render(state: DesktopSourceLoginUiState): List<String> {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalSourceLoginDialogInitialState provides state,
                ) { Navigator(SourceBrowseScreen(404)) { CurrentScreen() } }
            }
            scene.render()
            return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.map { it.config.toString() }
        }

        fun List<String>.hasClick(text: String) = any { it.contains(text) && it.contains("OnClick") }
        val activeNodes = render(active)
        val activeSemantics = activeNodes.joinToString()
        listOf(copy.title, active.host, copy.description, copy.cookieHeaderLabel, "IsEditable : true")
            .forEach { assertTrue(activeSemantics.contains(it), "missing active dialog text: $it") }
        assertTrue(activeNodes.hasClick(copy.submit))
        assertTrue(activeNodes.hasClick(copy.cancel))
        val terminalNodes = render(active.copy(feedback = DesktopSourceLoginFeedback.TimedOut, terminal = true))
        val terminalSemantics = terminalNodes.joinToString()
        assertTrue(terminalSemantics.contains(copy.timedOut))
        assertTrue(terminalNodes.hasClick(copy.close))
        listOf(copy.cookieHeaderLabel, copy.cancel).forEach { assertFalse(terminalSemantics.contains(it)) }
        scene.close()
    }

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
    fun `global projector preserves partial content failure actions and true empty`() {
        val content = NamedSource(40, "Content")
        val failure = NamedSource(41, "Failure")
        val empty = NamedSource(42, "Empty")
        val contentRequest = SourcePageRequest(content.id, 1, 3, SourceQuery.Popular)
        val failureRequest = SourcePageRequest(failure.id, 1, 3, SourceQuery.Latest)
        val ui = GlobalSearchStateProjector.project(
            listOf(content, failure, empty),
            DesktopGlobalSearchState(
                generation = 3,
                isSearching = true,
                queryStates = mapOf(
                    content.id to SourceQueryState.Content(contentRequest, listOf(manga("/kept")), false),
                    failure.id to SourceQueryState.Failure(failureRequest, AppError.Server(500), tachiyomi.domain.source.service.SourceRecoveryAction.Retry),
                    empty.id to SourceQueryState.Empty(SourcePageRequest(empty.id, 1, 3, SourceQuery.Popular)),
                ),
            ),
        )

        assertTrue(ui.loading)
        assertFalse(ui.empty)
        assertEquals(listOf("Content", "Failure"), ui.results.map { it.source.name })
        assertEquals(listOf("/kept"), ui.results.first().results.map(SManga::url))
        assertInstanceOf(AppError.Server::class.java, ui.results.last().error?.error)
        assertEquals(DesktopSourceRecoveryIntent.Retry(failureRequest), ui.results.last().recoveryIntent)
        assertTrue(
            GlobalSearchStateProjector.project(
                listOf(empty),
                DesktopGlobalSearchState(4, false, mapOf(empty.id to SourceQueryState.Empty(SourcePageRequest(empty.id, 1, 4, SourceQuery.Popular)))),
            ).empty,
        )
    }
    @Test
    fun `global retry reuses the session child and exact failed request`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        coordinator.search(listOf(source), "same-query")
        val child = requireNotNull(coordinator.coordinatorFor(source.id))
        val failed = requireNotNull(GlobalSearchStateProjector.project(listOf(source), coordinator.state).results.single().recoveryIntent)
        val factory = DesktopSourceLoginSessionFactory(AuthenticatedSessionCommitter { _, _ -> }, DesktopBrowserOpener { _, _ -> false })
        GlobalSearchScreen().retry(coordinator, source, failed, factory)

        assertSame(child, coordinator.coordinatorFor(source.id))
        assertEquals((failed as DesktopSourceRecoveryIntent.Retry).request, child.state?.request)
        assertEquals(listOf("same-query", "same-query"), source.queries)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `global login action uses the shared dialog and retries its failed child request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 403))
            server.enqueue(MockResponse(code = 200))
            server.start()
            val cookieJar = DesktopCookieJar()
            val source = RoutedHttpSource(server.url("/").toString().removeSuffix("/"), OkHttpClient.Builder().cookieJar(cookieJar).build())
            val dependencies = mockk<DesktopUiDependencies> {
                every { sourceManager } returns MutableSourceManager(listOf(source))
                every { sourceMangaSearchService } returns SourceMangaSearchService()
                every { saveSourceMangaForDetails } returns mockk(relaxed = true)
                every { sourceLoginSessionFactory } returns DesktopSourceLoginSessionFactory(
                    DesktopAuthenticatedSessionCommitter(cookieJar),
                    DesktopBrowserOpener { _, _ -> true },
                )
            }
            var coordinator: DesktopGlobalSearchCoordinator? = null
            val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            fun click(text: String): Boolean {
                val node = requireNotNull(nodes().firstOrNull {
                    it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(text)
                })
                return requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            }
            val copy = desktopSourceLoginCopy { it.localized() }

            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalGlobalSearchCoordinatorFactory provides { service -> DesktopGlobalSearchCoordinator(service).also { coordinator = it } },
                ) { Navigator(GlobalSearchScreen("captured")) { CurrentScreen() } }
            }
            scene.render()
            withTimeout(2_000) { requireNotNull(coordinator).states.first { !it.isSearching } }
            val child = requireNotNull(coordinator).coordinatorFor(source.id)
            val failed = requireNotNull(coordinator).state.queryStates.getValue(source.id).request
            scene.render()

            assertTrue(click(copy.title))
            scene.render()
            val cookieHeader = nodes().filter { it.config.contains(SemanticsActions.SetText) }.last()
            assertTrue(requireNotNull(cookieHeader.config[SemanticsActions.SetText].action).invoke(AnnotatedString("invalid")))
            assertTrue(click(copy.submit))
            scene.render()
            assertTrue(nodes().joinToString { it.config.toString() }.contains(copy.invalidHeader))
            assertTrue(requireNotNull(cookieHeader.config[SemanticsActions.SetText].action).invoke(AnnotatedString("session=secret")))
            assertTrue(click(copy.submit))

            withTimeout(2_000) {
                requireNotNull(coordinator).states.first { it.queryStates[source.id] is SourceQueryState.Content }
            }
            scene.render()
            assertSame(child, requireNotNull(coordinator).coordinatorFor(source.id))
            assertEquals(failed, requireNotNull(coordinator).state.queryStates.getValue(source.id).request)
            assertEquals(null, server.takeRequest().headers["Cookie"])
            assertEquals("session=secret", server.takeRequest().headers["Cookie"])
            assertTrue(nodes().joinToString { it.config.toString() }.contains("Routed (1)"))
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `global search content collects state and closes its coordinator on disposal`() = runBlocking {
        val firstCompleted = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = object : NamedSource(52, "First source") {
            val queries = mutableListOf<String>()
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                queries += query
                firstCompleted.complete(Unit)
                return MangasPage(listOf(manga("/first")), false)
            }
        }
        val second = object : NamedSource(53, "Second source") {
            val queries = mutableListOf<String>()
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                queries += query
                started.complete(Unit)
                release.await()
                return MangasPage(listOf(manga("/second")), false)
            }
        }
        val dynamicSourceManager = MutableSourceManager(listOf(first))
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns dynamicSourceManager
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { sourceLoginSessionFactory } returns DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, _ -> false },
            )
        }
        var coordinator: DesktopGlobalSearchCoordinator? = null
        val factory: (SourceMangaSearchService) -> DesktopGlobalSearchCoordinator = { service ->
            DesktopGlobalSearchCoordinator(service).also { coordinator = it }
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun semantics(): String = nodes().joinToString { it.config.toString() }

        scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides dependencies,
                LocalGlobalSearchCoordinatorFactory provides factory,
            ) { Navigator(GlobalSearchScreen("visible")) { CurrentScreen() } }
        }
        scene.render()
        withTimeout(2_000) { firstCompleted.await() }
        withTimeout(2_000) { requireNotNull(coordinator).states.first { it.generation == 1L && !it.isSearching } }
        scene.render()
        dynamicSourceManager.sources = listOf(second)
        val input = nodes().first { it.config.contains(SemanticsActions.SetText) }
        assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString("second")))
        scene.render()
        val button = nodes().first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("Search") }
        assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
        withTimeout(2_000) { started.await() }
        scene.render()
        assertTrue(semantics().contains("Searching"))

        release.complete(Unit)
        withTimeout(2_000) { requireNotNull(coordinator).states.first { it.generation == 2L && !it.isSearching } }
        scene.render()
        assertEquals(listOf("visible"), first.queries)
        assertEquals(listOf("second"), second.queries)
        assertTrue(semantics().contains("Second source (1)"))

        scene.close()
        assertEquals(null, requireNotNull(coordinator).coordinatorFor(second.id))
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
    fun `global coordinator stateflow aggregates partial source outcomes and exact child identity`() = runBlocking {
        val success = object : NamedSource(20, "Success") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                delay(25)
                return MangasPage(listOf(manga("/success")), false)
            }
        }
        val failure = QueryFailureSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val observed = mutableListOf<DesktopGlobalSearchState>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            coordinator.states.collect(observed::add)
        }

        assertTrue(coordinator.states.value.queryStates.isEmpty())
        coordinator.search(listOf(success, failure), "shared")
        collector.cancelAndJoin()

        val state = coordinator.states.value
        assertInstanceOf(SourceQueryState.Content::class.java, state.queryStates[success.id])
        assertInstanceOf(SourceQueryState.Failure::class.java, state.queryStates[failure.id])
        val child = requireNotNull(coordinator.coordinatorFor(success.id))
        assertSame(child, coordinator.coordinatorFor(success.id))
        assertEquals(child.state?.request, state.queryStates.getValue(success.id).request)
        assertTrue(observed.any { it.isSearching && it.queryStates.values.any(SourceQueryState::isLoading) })
    }

    @Test
    fun `completed global search keeps current child recovery states flowing until replacement`() = runBlocking {
        var originalAttempts = 0
        val retryStarted = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        val source = object : NamedSource(29, "Recovery") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = when (query) {
                "original" -> if (originalAttempts++ == 0) throw HttpException(500) else {
                    retryStarted.complete(Unit)
                    finishRetry.await()
                    MangasPage(listOf(manga("/retried")), false)
                }
                "fail" -> throw HttpException(500)
                else -> MangasPage(listOf(manga("/$query")), false)
            }
        }
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())

        coordinator.search(listOf(source), "original")
        val oldChild = requireNotNull(coordinator.coordinatorFor(source.id))
        val retry = async { oldChild.retry(source) }
        retryStarted.await()
        withTimeout(2_000) { coordinator.states.first { it.queryStates[source.id] is SourceQueryState.Loading } }
        finishRetry.complete(Unit)
        retry.await()
        withTimeout(2_000) { coordinator.states.first { (it.queryStates[source.id] as? SourceQueryState.Content)?.items?.singleOrNull()?.url == "/retried" } }

        oldChild.load(source, 1, SourceQuery.Search("new", FilterList()))
        withTimeout(2_000) { coordinator.states.first { (it.queryStates[source.id]?.request?.query as? SourceQuery.Search)?.query == "new" } }
        oldChild.load(source, 1, SourceQuery.Search("fail", FilterList()))
        withTimeout(2_000) { coordinator.states.first { it.queryStates[source.id] is SourceQueryState.Failure } }
        coordinator.search(listOf(source), "replacement")
        oldChild.load(source, 1, SourceQuery.Search("stale", FilterList()))
        val current = coordinator.states.value.queryStates.getValue(source.id)
        assertEquals("replacement", (current.request.query as SourceQuery.Search).query)
        val currentChild = requireNotNull(coordinator.coordinatorFor(source.id))
        coordinator.close()
        withTimeout(2_000) { while (currentChild.subscriberCount != 0) delay(1) }
        currentChild.load(source, 1, SourceQuery.Search("closed", FilterList()))
        assertEquals(null, coordinator.coordinatorFor(source.id))
        assertEquals(current, coordinator.states.value.queryStates[source.id])
    }

    @Test
    fun `completed global search detaches compat callback from later exact recovery`() = runBlocking {
        listOf(CancellationException("late callback"), AssertionError("late callback")).forEachIndexed { index, lateError ->
            val attempts = mutableMapOf<String, Int>()
            val source = object : NamedSource(30L + index, "Detached callback") {
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                    if (attempts.put(query, attempts.getOrDefault(query, 0) + 1) == null) throw HttpException(500)
                    delay(25)
                    return MangasPage(listOf(manga("/$query")), false)
                }
            }
            val lateFailure = AtomicReference<Throwable?>()
            val callbacks = Collections.synchronizedList(mutableListOf<Unit>())
            val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
            withTimeout(2_000) {
                coordinator.search(listOf(source), "first") {
                    callbacks += Unit
                    lateFailure.get()?.let { throw it }
                }
            }
            val child = requireNotNull(coordinator.coordinatorFor(source.id))
            val callbackCount = callbacks.size.also { lateFailure.set(lateError) }
            val firstRetry = async(start = CoroutineStart.UNDISPATCHED) { child.retry(source) }
            withTimeout(2_000) { coordinator.states.first { it.queryStates[source.id] is SourceQueryState.Loading } }
            firstRetry.await()
            withTimeout(2_000) { coordinator.states.first { it.queryStates[source.id] is SourceQueryState.Content } }
            child.load(source, 1, SourceQuery.Search("second", FilterList()))
            withTimeout(2_000) { coordinator.states.first { it.queryStates[source.id] is SourceQueryState.Failure } }
            val secondRetry = async(start = CoroutineStart.UNDISPATCHED) { child.retry(source) }
            withTimeout(2_000) { coordinator.states.first { it.queryStates[source.id] is SourceQueryState.Loading } }
            secondRetry.await()
            withTimeout(2_000) { coordinator.states.first { (it.queryStates[source.id] as? SourceQueryState.Content)?.items?.singleOrNull()?.url == "/second" } }
            assertEquals(callbackCount, callbacks.size)
            assertTrue(child.subscriberCount > 0)
            coordinator.close()
        }
    }

    @Test
    fun `global callback reports each authoritative query transition exactly once`() = runBlocking {
        val source = QueryFailureSource()
        val callbacks = Collections.synchronizedList(mutableListOf<DesktopGlobalSearchState>())
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        coordinator.search(listOf(source), "sequence", callbacks::add)
        val queryStates = callbacks.filter { it.isSearching }.mapNotNull { it.queryStates[source.id] }
        assertEquals(listOf(SourceQueryState.Loading::class, SourceQueryState.Failure::class), queryStates.map { it::class })
        assertEquals(1, callbacks.count { !it.isSearching })
    }

    @Test
    fun `replaced global callback never observes the replacement generation`() = runBlocking {
        val (callbackEntered, releaseCallback) = List(2) { CountDownLatch(1) }
        val finishOldQuery = CountDownLatch(1)
        val oldSource = object : NamedSource(33, "Old callback") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = try {
                awaitCancellation()
            } finally {
                finishOldQuery.await(5, TimeUnit.SECONDS)
            }
        }
        val callbacks = Collections.synchronizedList(mutableListOf<DesktopGlobalSearchState>())
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val old = async(Dispatchers.Default) {
            coordinator.search(listOf(oldSource), "old") { state ->
                callbacks += state
                if (state.queryStates[oldSource.id] is SourceQueryState.Loading) {
                    callbackEntered.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        coordinator.search(listOf(NamedSource(34, "Replacement")), "new")
        releaseCallback.countDown()
        delay(50)
        finishOldQuery.countDown()
        old.await()
        assertTrue(callbacks.all { it.generation == 1L && it.queryStates.keys.none { id -> id == 34L } })
    }

    @Test
    fun `global coordinator rejects late generation state and retires its child coordinator`() = runBlocking {
        val source = InterleavingSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val old = async(Dispatchers.Default) { coordinator.search(listOf(source), "old") }
        assertTrue(source.oldRequestStarted.await(5, TimeUnit.SECONDS))
        val oldChild = requireNotNull(coordinator.coordinatorFor(source.id))

        coordinator.search(listOf(source), "new")
        val currentChild = requireNotNull(coordinator.coordinatorFor(source.id))
        source.releaseOldResult.countDown()
        old.await()

        assertFalse(oldChild === currentChild)
        assertSame(currentChild, coordinator.coordinatorFor(source.id))
        val result = assertInstanceOf(SourceQueryState.Content::class.java, coordinator.states.value.queryStates[source.id])
        assertEquals("new", (result.request.query as SourceQuery.Search).query)
        assertEquals(listOf("/new"), result.items.map(SManga::url))
    }

    @Test
    fun `new global search cancels old source jobs without letting old cleanup clear current state`() = runBlocking {
        val source = CancellableGlobalSearchSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val old = async { runCatching { coordinator.search(listOf(source), "old") }.exceptionOrNull() }
        source.oldStarted.await()
        coordinator.search(listOf(source), "new")
        assertEquals(null, withTimeout(2_000) { old.await() })
        withTimeout(2_000) { source.oldCancelled.await() }
        val result = assertInstanceOf(SourceQueryState.Content::class.java, coordinator.states.value.queryStates[source.id])
        assertEquals("new", (result.request.query as SourceQuery.Search).query)
        assertFalse(coordinator.states.value.isSearching)
        assertEquals(result.request, coordinator.coordinatorFor(source.id)?.state?.request)
    }
    @Test
    fun `cancelling current global search retires its child and clears searching`() = runBlocking {
        val source = CancellableGlobalSearchSource()
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val running = launch { coordinator.search(listOf(source), "old") }
        source.oldStarted.await()
        running.cancelAndJoin()
        assertTrue(running.isCancelled)
        assertFalse(coordinator.states.value.isSearching)
        assertEquals(null, coordinator.coordinatorFor(source.id))
        withTimeout(2_000) { source.oldCancelled.await() }
    }
    @Test
    fun `compat global callback failure cannot interrupt authoritative aggregation or cleanup`() = runBlocking {
        val source = NamedSource(21, "Callback")
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        coordinator.search(listOf(source), "safe") { error("compat callback") }
        assertInstanceOf(SourceQueryState.Empty::class.java, coordinator.states.value.queryStates[source.id])
        assertFalse(coordinator.states.value.isSearching)
        assertEquals(coordinator.states.value.queryStates[source.id]?.request, coordinator.coordinatorFor(source.id)?.state?.request)
    }

    @Test
    fun `current global callback cancellation propagates after cleanup`() = runBlocking {
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val result = runCatching {
            coordinator.search(listOf(NamedSource(25, "Cancel callback")), "cancel") { throw CancellationException("callback") }
        }.exceptionOrNull()
        assertInstanceOf(CancellationException::class.java, result)
        assertFalse(coordinator.states.value.isSearching)
        assertEquals(null, coordinator.coordinatorFor(25))
    }

    @Test
    fun `global callback error propagates after cleanup`() = runBlocking {
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val result = runCatching {
            coordinator.search(listOf(NamedSource(26, "Error callback")), "error") { throw AssertionError("callback") }
        }.exceptionOrNull()
        assertInstanceOf(AssertionError::class.java, result)
        assertFalse(coordinator.states.value.isSearching)
        assertEquals(null, coordinator.coordinatorFor(26))
    }

    @Test
    fun `concurrent global publication keeps the newest ordinal and rejects loser callback`() {
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val release = CountDownLatch(1)
        val publications = listOf(
            DesktopGlobalSearchState(generation = 1, publicationOrdinal = 1),
            DesktopGlobalSearchState(generation = 2, publicationOrdinal = 2),
        ).map { candidate ->
            CompletableFuture.runAsync { release.await(); coordinator.publishCandidate(candidate) }
        }
        release.countDown()
        CompletableFuture.allOf(*publications.toTypedArray()).get(2, TimeUnit.SECONDS)
        assertEquals(2, coordinator.states.value.generation)
        val loserCallbacks = mutableListOf<DesktopGlobalSearchState>()
        coordinator.publishCandidate(DesktopGlobalSearchState(generation = 1, publicationOrdinal = 1), loserCallbacks::add)
        assertTrue(loserCallbacks.isEmpty())
    }

    @Test
    fun `global publication resumes reentrant collectors outside the coordinator lock`() = runBlocking {
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val reentered = CompletableDeferred<Boolean>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            coordinator.states.first { state ->
                if (state.generation == 0L) return@first false
                val crossThread = CompletableFuture.supplyAsync { coordinator.coordinatorFor(-1) }
                reentered.complete(runCatching { crossThread.get(250, TimeUnit.MILLISECONDS) }.isSuccess)
                true
            }
        }
        val callbacks = mutableListOf<DesktopGlobalSearchState>()
        coordinator.search(emptyList(), "reentrant", callbacks::add)
        collector.join()

        assertTrue(reentered.await(), "StateFlow resumed its collector while the global monitor was held")
        assertEquals(listOf(1L, 1L), callbacks.map { it.generation })
    }

    @Test
    fun `global aggregation independently rejects an old generation and an old child`() = runBlocking {
        val source = NamedSource(23, "Identity")
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        coordinator.search(listOf(source), "same")
        val current = coordinator.states.value
        val candidate = current.queryStates.getValue(source.id)
        assertEquals(null, coordinator.aggregateCandidate(current.generation - 1, source.id, requireNotNull(coordinator.coordinatorFor(source.id)), candidate))
        assertEquals(null, coordinator.aggregateCandidate(current.generation, source.id, SourceBrowseQueryCoordinator(SourceMangaSearchService()), candidate))
    }

    @Test
    fun `compat callback runs outside the coordinator lock and can replace its search`() = runBlocking {
        val source = NamedSource(24, "Reentrant")
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        var reentered = false
        val result = runCatching {
            coordinator.search(listOf(source), "outer") {
                CompletableFuture.supplyAsync { coordinator.coordinatorFor(source.id) }.get(1, TimeUnit.SECONDS)
                if (!reentered) runBlocking { reentered = true; coordinator.search(emptyList(), "inner") }
                throw CancellationException("outer callback")
            }
        }.exceptionOrNull()
        assertInstanceOf(CancellationException::class.java, result)
        assertEquals(2, coordinator.states.value.generation)
        assertFalse(coordinator.states.value.isSearching)
    }

    @Test
    fun `late registered source job uses the same retirement cause after callback replacement`() = runBlocking {
        val invoked = AtomicBoolean()
        val source = object : NamedSource(27, "Late register") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
                MangasPage(emptyList(), false).also { invoked.set(true) }
        }
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        var reentered = false
        val result = runCatching {
            coordinator.search(listOf(source), "outer") {
                if (!reentered) runBlocking { reentered = true; coordinator.search(emptyList(), "inner") }
            }
        }.exceptionOrNull()
        assertEquals(null, result)
        assertFalse(invoked.get())
        assertEquals(2, coordinator.states.value.generation)
    }

    @Test
    fun `cyclic callback cancellation cause propagates and cleans current search in bounded time`() {
        val first = CancellationException("first")
        val second = CancellationException("second")
        first.initCause(second)
        second.initCause(first)
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val result = CompletableFuture.supplyAsync {
            runBlocking {
                runCatching {
                    coordinator.search(listOf(NamedSource(28, "Cycle")), "cycle") { throw first }
                }.exceptionOrNull()
            }
        }.get(2, TimeUnit.SECONDS)
        assertSame(first, result)
        assertFalse(coordinator.states.value.isSearching)
        assertEquals(null, coordinator.coordinatorFor(28))
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
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 403))
            server.enqueue(MockResponse(code = 200))
            server.start()
            val jar = DesktopCookieJar()
            val source = CookieQuerySource(server.url("/source"), OkHttpClient.Builder().cookieJar(jar).build())
            val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
            coordinator.load(source, 1, SourceQuery.Search("captured", FilterList()))
            val captured = coordinator.state!!.request
            val controller = DesktopSourceLoginController(
                DesktopSourceLoginSessionFactory(
                    DesktopAuthenticatedSessionCommitter(jar),
                    DesktopBrowserOpener { _, _ -> true },
                ),
                coordinator,
            )
            val attempt = controller.newAttempt()
            val login = async(start = CoroutineStart.UNDISPATCHED) {
                controller.login(source, DesktopSourceRecoveryIntent.OpenLogin(source.url.toString(), captured), attempt)
            }

            assertTrue(controller.submitCookies(attempt, "session=secret; auth_token=token"))

            assertEquals(SourceLoginState.Authenticated(setOf("auth_token", "session"), 2), login.await())
            assertEquals(null, server.takeRequest().headers["Cookie"])
            assertEquals("auth_token=token; session=secret", server.takeRequest().headers["Cookie"])
            assertEquals(listOf(1, 1), source.requests.map { it.page })
            assertEquals(listOf("captured", "captured"), source.requests.map { (it.query as SourceQuery.Search).query })
            assertSame((captured.query as SourceQuery.Search).filters, source.filters.last())
            assertEquals(captured, coordinator.state!!.request)
        }
    }

    @Test
    fun `cookie header parser rejects unsafe input without exposing values`() {
        val url = "https://example.com/path".toHttpUrl()
        listOf(
            "", "missing", "=secret", "session=", "session=one; session=two", "bad name=secret",
            "session=bad value", "session=bad,comma", "session=bad\\slash", "session=line\r\nbreak",
            "session=\u007f", "session=é",
        )
            .forEach { assertEquals(null, DesktopSourceCookieHeaderParser.parse(it, url)) }
        val parsed = requireNotNull(DesktopSourceCookieHeaderParser.parse("session=secret", url))
        assertFalse(parsed.toString().contains("secret"))
    }

    @Test
    fun `login controller rejects concurrency and cancellation isolates late input`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, 1, SourceQuery.Search("once", FilterList()))
        val intent = DesktopSourceRecoveryIntent.OpenLogin("https://example.com", coordinator.state!!.request)
        lateinit var firstTicket: mihon.desktop.network.DesktopBrowserLoginTicket
        val controller = DesktopSourceLoginController(
            DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, ticket -> firstTicket = ticket; true },
            ),
            coordinator,
        )
        val first = controller.newAttempt()
        val running = async(start = CoroutineStart.UNDISPATCHED) { controller.login(source, intent, first) }
        val second = controller.newAttempt()

        assertEquals(SourceLoginState.BrowserUnavailable, controller.login(source, intent, second))
        assertTrue(controller.cancel(first))
        controller.cancel(first)
        assertEquals(SourceLoginState.Cancelled, running.await())
        assertFalse(controller.submitCookies(first, "session=secret"))
        assertFalse(firstTicket.complete(session("session", "secret")))
        assertFalse(controller.toString().contains("secret"))
    }

    @Test
    fun `timed out attempt is cleaned before a fresh login and rejects late submission`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        coordinator.load(source, 1, SourceQuery.Search("once", FilterList()))
        val intent = DesktopSourceRecoveryIntent.OpenLogin("https://example.com", coordinator.state!!.request)
        val controller = DesktopSourceLoginController(
            DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, _ -> true },
            ),
            coordinator,
            timeoutMillis = 1,
        )
        val timedOut = controller.newAttempt()
        assertEquals(SourceLoginState.TimedOut, controller.login(source, intent, timedOut))
        assertFalse(controller.submitCookies(timedOut, "session=secret"))

        val fresh = controller.newAttempt()
        val running = async(start = CoroutineStart.UNDISPATCHED) { controller.login(source, intent, fresh) }
        assertFalse(controller.submitCookies(timedOut, "session=late-secret"))
        assertTrue(controller.submitCookies(fresh, "session=secret"))
        assertEquals(SourceLoginState.Authenticated(setOf("session"), 1), running.await())
    }

    @Test
    fun `source login UI actions preserve attempt identity and report invalid header`() {
        val attempt = DesktopSourceLoginAttempt()
        var submittedAttempt: DesktopSourceLoginAttempt? = null
        var submittedHeader: String? = null
        var cancelledAttempt: DesktopSourceLoginAttempt? = null
        var acceptsHeader = false
        var acceptsCancel = false
        val actions = DesktopSourceLoginUiActions(
            submitCookies = { actualAttempt, header ->
                submittedAttempt = actualAttempt
                submittedHeader = header
                acceptsHeader
            },
            cancel = { actualAttempt -> acceptsCancel.also { cancelledAttempt = actualAttempt } },
        )
        val opened = actions.open(attempt, "https://reader.example.com/login")
        val edited = actions.editHeader(opened, "session=secret")

        val rejected = actions.submit(edited)
        assertSame(attempt, rejected.attempt)
        assertSame(attempt, submittedAttempt)
        assertEquals("reader.example.com", rejected.host)
        assertEquals("session=secret", submittedHeader)
        assertEquals(DesktopSourceLoginFeedback.InvalidHeader, rejected.feedback)
        assertFalse(rejected.toString().contains("secret"))

        acceptsHeader = true
        assertEquals(null, actions.submit(rejected).feedback)
        assertSame(rejected, actions.cancel(rejected))
        acceptsCancel = true
        assertEquals(null, actions.cancel(rejected))
        assertSame(attempt, cancelledAttempt)

        val invalid = actions.open(DesktopSourceLoginAttempt(), "http://[invalid")
        assertEquals("", invalid.host)
        assertFalse(invalid.toString().contains("[invalid"))
    }

    @Test
    fun `source login terminal mapping closes success and cancellation but retains failures`() {
        val actions = DesktopSourceLoginUiActions({ _, _ -> true }, { true })
        val active = actions.open(DesktopSourceLoginAttempt(), "https://example.com")
        val failures = mapOf(
            SourceLoginState.BrowserUnavailable to DesktopSourceLoginFeedback.BrowserUnavailable,
            SourceLoginState.TimedOut to DesktopSourceLoginFeedback.TimedOut,
            SourceLoginState.InvalidCookies(emptySet(), setOf("session")) to DesktopSourceLoginFeedback.InvalidCookies,
            SourceLoginState.CommitFailed to DesktopSourceLoginFeedback.CommitFailed,
        )

        failures.forEach { (result, expected) ->
            val terminal = requireNotNull(actions.complete(active, active.attempt, result))
            assertTrue(terminal.terminal)
            assertEquals(expected, terminal.feedback)
        }
        assertEquals(null, actions.complete(active, active.attempt, SourceLoginState.Authenticated(setOf("session"), 1)))
        assertEquals(null, actions.complete(active, active.attempt, SourceLoginState.Cancelled))
    }

    @Test
    fun `late completion from another attempt preserves the current login for every outcome`() {
        val actions = DesktopSourceLoginUiActions({ _, _ -> true }, { true })
        val stale = DesktopSourceLoginAttempt()
        val current = actions.open(DesktopSourceLoginAttempt(), "https://current.example.com")
        val staleResults = listOf(
            SourceLoginState.Authenticated(setOf("session"), 1),
            SourceLoginState.Cancelled,
            SourceLoginState.BrowserUnavailable,
            SourceLoginState.TimedOut,
            SourceLoginState.InvalidCookies(emptySet(), setOf("session")),
            SourceLoginState.CommitFailed,
        )

        staleResults.forEach { result ->
            assertSame(current, actions.complete(current, stale, result))
        }
    }

    @Test
    fun `rejected login cannot replace accepted UI and late completion cannot close its successor`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val intent = DesktopSourceRecoveryIntent.OpenLogin(
            "https://example.com",
            SourcePageRequest(source.id, 1, 1, SourceQuery.Popular),
        )
        val recovery = SourceBrowseRecoveryController(
            coordinator,
            DesktopSourceLoginController(
                DesktopSourceLoginSessionFactory(
                    AuthenticatedSessionCommitter { _, _ -> },
                    DesktopBrowserOpener { _, _ -> true },
                ),
                coordinator,
            ),
        )
        val actions = DesktopSourceLoginUiActions(recovery::submitCookies, recovery::cancel)
        var ui: DesktopSourceLoginUiState? = null
        lateinit var firstAttempt: DesktopSourceLoginAttempt
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.recover(source, intent) { accepted ->
                firstAttempt = accepted
                ui = actions.open(accepted, intent.url)
            }
        }
        val firstUi = requireNotNull(ui)
        var rejectedPublished = false

        assertEquals(SourceLoginState.BrowserUnavailable, recovery.recover(source, intent) { rejectedPublished = true })
        assertFalse(rejectedPublished)
        assertSame(firstAttempt, ui?.attempt)

        assertTrue(recovery.cancel(firstAttempt))
        val lateFirstResult = first.await()
        lateinit var secondAttempt: DesktopSourceLoginAttempt
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.recover(source, intent) { accepted ->
                secondAttempt = accepted
                ui = actions.open(accepted, intent.url)
            }
        }
        val secondUi = requireNotNull(ui)
        assertFalse(firstUi.attempt === secondUi.attempt)

        ui = actions.complete(secondUi, firstAttempt, lateFirstResult)
        assertSame(secondAttempt, ui?.attempt)
        assertEquals(null, actions.cancel(requireNotNull(ui)))
        assertEquals(SourceLoginState.Cancelled, second.await())
    }

    @Test
    fun `accepted callback failure releases the claimed login attempt`() = runBlocking {
        val source = QueryFailureSource()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
        val intent = DesktopSourceRecoveryIntent.OpenLogin(
            "https://example.com",
            SourcePageRequest(source.id, 1, 1, SourceQuery.Popular),
        )
        val controller = DesktopSourceLoginController(
            DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, _ -> true },
            ),
            coordinator,
        )

        assertTrue(runCatching { controller.login(source, intent, controller.newAttempt()) { error("UI failed") } }.isFailure)
        val fresh = controller.newAttempt()
        val running = async(start = CoroutineStart.UNDISPATCHED) { controller.login(source, intent, fresh) }
        assertTrue(controller.cancel(fresh))
        assertEquals(SourceLoginState.Cancelled, running.await())
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
        val controller = SourceBrowseRecoveryController(
            coordinator,
            DesktopSourceLoginController(
                DesktopSourceLoginSessionFactory(AuthenticatedSessionCommitter { _, _ -> }, DesktopBrowserOpener { _, _ -> true }),
                coordinator,
            ),
        )
        val screen = SourceBrowseScreen(source.id)

        coordinator.load(source, 1, SourceQuery.Search("old", FilterList()))
        val oldIntent = coordinator.recoveryIntent(source)
        coordinator.load(source, 1, SourceQuery.Search("new", FilterList()))
        screen.recover(controller, source, oldIntent)

        assertEquals(listOf("old", "new"), source.queries)
    }

    @Test
    fun `generic authentication and real Cloudflare responses use exclusive production routes`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val manager = CloudflareChallengeManager()
        DesktopNetworkHelper(
            cacheDir = tempDir.resolve("cache"),
            cookieStorageFile = tempDir.resolve("cookies.json"),
            challengeManager = manager,
        ).use { helper ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(code = 403, body = "authentication required"))
                val source = RoutedHttpSource(server.url("/").toString().removeSuffix("/"), helper.client)
                val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService())
                coordinator.load(source, 1, SourceQuery.Popular)
                assertEquals(null, manager.tryReceive())

                val tickets = mutableListOf<mihon.desktop.network.DesktopBrowserLoginTicket>()
                val recovery = SourceBrowseRecoveryController(
                    coordinator,
                    DesktopSourceLoginController(
                        DesktopSourceLoginSessionFactory(
                            DesktopAuthenticatedSessionCommitter(helper.cookieJar),
                            DesktopBrowserOpener { _, ticket -> tickets += ticket; true },
                        ),
                        coordinator,
                    ),
                )
                lateinit var attempt: DesktopSourceLoginAttempt
                val generic = async(start = CoroutineStart.UNDISPATCHED) {
                    recovery.recover(source, coordinator.recoveryIntent(source)) { attempt = it }
                }
                assertEquals(1, tickets.size)
                assertEquals(null, manager.tryReceive())
                assertTrue(recovery.cancel(attempt))
                assertEquals(SourceLoginState.Cancelled, generic.await())

                server.enqueue(
                    MockResponse(
                        code = 403,
                        headers = Headers.headersOf("Server", "cloudflare"),
                        body = "<html><div id=\"challenge-error-title\">challenge</div></html>",
                    ),
                )
                val cloudflareCall = async(Dispatchers.IO) {
                    runCatching { helper.client.newCall(Request.Builder().url(server.url("/cf")).build()).execute().close() }
                }
                var challenge = manager.tryReceive()
                repeat(100) {
                    if (challenge != null) return@repeat
                    delay(10)
                    challenge = manager.tryReceive()
                }
                val published = requireNotNull(challenge)
                assertEquals(1, tickets.size)
                manager.recover(published, ChallengeRecoveryIntent.Cancel)
                cloudflareCall.await()
            }
        }
        Unit
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

    private class CancellableGlobalSearchSource : NamedSource(22, "Cancellable") {
        val oldStarted = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (query != "old") return MangasPage(listOf(manga("/new")), false)
            oldStarted.complete(Unit)
            try { awaitCancellation() } finally { oldCancelled.complete(Unit) }
        }
    }

    private class CookieQuerySource(
        val url: okhttp3.HttpUrl,
        private val client: OkHttpClient,
    ) : NamedSource(5, "Cookies") {
        val requests = mutableListOf<SourcePageRequest>()
        val filters = mutableListOf<FilterList>()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            requests += SourcePageRequest(id, page, 1, SourceQuery.Search(query, filters))
            this.filters += filters
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(response.code)
            }
            return MangasPage(listOf(manga("/authenticated")), false)
        }
    }

    private class RoutedHttpSource(
        override val baseUrl: String,
        override val client: OkHttpClient,
    ) : HttpSource() {
        override val id = 6L
        override val name = "Routed"
        override val lang = "en"
        override val supportsLatest = false
        override fun popularMangaRequest(page: Int) = Request.Builder().url("$baseUrl/popular").build()
        override fun popularMangaParse(response: okhttp3.Response) = MangasPage(listOf(manga("/authenticated")), false)
        override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
        override fun latestUpdatesParse(response: okhttp3.Response) = popularMangaParse(response)
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)
        override fun searchMangaParse(response: okhttp3.Response) = popularMangaParse(response)
        override fun mangaDetailsParse(response: okhttp3.Response) = SManga.create()
        override fun chapterListParse(response: okhttp3.Response) = emptyList<SChapter>()
        override fun chapterPageParse(response: okhttp3.Response) = SChapter.create()
        override fun pageListParse(response: okhttp3.Response) = emptyList<Page>()
        override fun imageUrlParse(response: okhttp3.Response) = ""
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

    private class MutableSourceManager(var sources: List<CatalogueSource>) : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources get() = flowOf(sources)
        override fun getCatalogueSources() = sources
        override fun getOnlineSources() = sources.filterIsInstance<HttpSource>()
        override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
        override fun get(sourceKey: Long) = sources.find { it.id == sourceKey }
        override fun getOrStub(sourceKey: Long): eu.kanade.tachiyomi.source.Source =
            sources.find { it.id == sourceKey } ?: tachiyomi.domain.source.model.StubSource(sourceKey, "", "")
    }

    private companion object {
        fun manga(url: String) = SManga.create().apply {
            this.url = url
            title = url
            initialized = true
        }
    }
}
