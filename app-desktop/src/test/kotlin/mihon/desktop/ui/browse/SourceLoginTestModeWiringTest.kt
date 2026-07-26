package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.network.DesktopBrowserLoginTicket
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.source.FakeDesktopSourceManager
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.manga.interactor.GetManga
import java.util.concurrent.atomic.AtomicReference

class SourceLoginTestModeWiringTest {
    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `source browse installs observation after real open login recovery`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 403))
            server.start()
            val source = RoutedHttpSource(server.url("/").toString().removeSuffix("/"), OkHttpClient())
            val ticket = AtomicReference<DesktopBrowserLoginTicket?>()
            val dependencies = mockkDependencies(source, ticket)
            val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
            var activePort: SourceBrowseTestModeObservationPort? = null
            var validationPort: SourceBrowseTestModeObservationPort? = null
            var racePort: SourceBrowseTestModeObservationPort? = null
            try {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                val loginLabel =
                    desktopSourceRecoveryActionLabel(tachiyomi.domain.source.service.SourceRecoveryAction.OpenLogin)!!
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(SourceBrowseScreen(source.id)) { CurrentScreen() }
                    }
                }
                withTimeout(2_000) {
                    while (
                        nodes().none {
                            it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(loginLabel)
                        }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                val recovery = nodes().first {
                    it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(loginLabel)
                }
                assertTrue(requireNotNull(recovery.config[SemanticsActions.OnClick].action).invoke())
                withTimeout(2_000) { while (ticket.get() == null) { scene.render(); delay(10) } }
                val port = requireNotNull(SourceBrowseTestModeBridge.port).also { activePort = it }
                val active = port.snapshot()
                assertEquals(source.id, active.sourceId)
                assertEquals(SourceBrowseTestPhase.FAILURE, active.phase)
                assertEquals(source.id, active.request?.sourceId)
                assertEquals(1, active.request?.page)
                assertEquals(1, active.request?.generation)
                assertEquals(SourceBrowseTestQueryKind.POPULAR, active.request?.queryKind)
                assertEquals(0, active.itemCount)
                assertFalse(active.loading)
                assertNull(active.hasNextPage)
                assertEquals("Authentication", active.error?.type)
                assertEquals(SourceBrowseTestRecovery.OPEN_LOGIN, active.recovery)
                assertEquals(server.url("/").host, active.login?.host)
                assertNull(active.login?.feedback)
                assertFalse(active.login?.terminal ?: true)
                val runtimeJson = Json.encodeToString(SourceBrowseTestSnapshot.serializer(), active)
                assertFalse(runtimeJson.contains("cookieHeader"))
                assertFalse(runtimeJson.contains("FilterList"))

                assertEquals(SourceBrowseTestFailureCode.MISSING_TOKEN, port.cancel(null).failureCode)
                assertEquals(SourceBrowseTestFailureCode.ATTEMPT_MISMATCH, port.cancel("wrong").failureCode)
                assertEquals(active.login, port.snapshot().login)
                assertTrue(port.cancel(requireNotNull(active.login).attemptToken).success)
                assertFalse(requireNotNull(ticket.get()).cancel())
                assertNull(port.snapshot().login)
                scene.render()
                assertTrue(nodes().none { it.config.contains(SemanticsActions.SetText) })
                var login: DesktopSourceLoginUiState? =
                    DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "fixture")
                var cancelCalls = 0
                val rejectedActions = DesktopSourceLoginUiActions({ _, _ -> false }) { cancelCalls++; false }
                val validation = SourceBrowseTestModeObservationPort(
                    source.id,
                    SourceBrowseQueryCoordinator(SourceMangaSearchService()),
                    this,
                    { login },
                    { login = it },
                    rejectedActions,
                ).also { validationPort = it }
                val stale = requireNotNull(validation.snapshot().login).attemptToken
                login = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "replacement")
                val current = requireNotNull(validation.snapshot().login).attemptToken
                assertNotEquals(stale, current)
                assertEquals(SourceBrowseTestFailureCode.ATTEMPT_MISMATCH, validation.cancel(stale).failureCode)
                login = requireNotNull(login).copy(feedback = DesktopSourceLoginFeedback.TimedOut, terminal = true)
                assertEquals(SourceBrowseTestLoginFeedback.TIMED_OUT, validation.snapshot().login?.feedback)
                assertTrue(validation.snapshot().login?.terminal == true)
                assertEquals(SourceBrowseTestFailureCode.TERMINAL, validation.cancel(current).failureCode)
                login = null
                assertEquals(SourceBrowseTestFailureCode.NO_ACTIVE_LOGIN, validation.cancel(current).failureCode)
                login = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "rejected")
                val rejectedToken = requireNotNull(validation.snapshot().login).attemptToken
                assertEquals(
                    SourceBrowseTestFailureCode.OPERATION_REJECTED,
                    validation.cancel(rejectedToken).failureCode
                )
                assertEquals(1, cancelCalls)

                val raceLogin = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "race")
                val loginRead = java.util.concurrent.CountDownLatch(1)
                val releaseLogin = java.util.concurrent.CountDownLatch(1)
                val racing = SourceBrowseTestModeObservationPort(
                    source.id,
                    SourceBrowseQueryCoordinator(SourceMangaSearchService()),
                    this,
                    { raceLogin.also { loginRead.countDown(); releaseLogin.await() } },
                    {},
                    rejectedActions,
                ).also { racePort = it }
                val racedSnapshot = async(kotlinx.coroutines.Dispatchers.Default) { racing.snapshot() }
                assertTrue(loginRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
                racing.close()
                releaseLogin.countDown()
                assertNull(racedSnapshot.await().login)
                assertEquals(SourceBrowseTestFailureCode.PORT_CLOSED, racing.cancel("closed").failureCode)

                SourceBrowseTestModeBridge.install(port)
                SourceBrowseTestModeBridge.install(validation)
                assertFalse(SourceBrowseTestModeBridge.clear(port))
                assertSame(validation, SourceBrowseTestModeBridge.port)
                assertTrue(SourceBrowseTestModeBridge.clear(validation))
            } finally {
                listOf(racePort, validationPort, activePort).forEach { candidate ->
                    candidate?.close()
                    candidate?.let(SourceBrowseTestModeBridge::clear)
                }
                ticket.get()?.cancel()
                scene.close()
                assertNull(SourceBrowseTestModeBridge.port)
            }
        }
    }

    private fun mockkDependencies(source: HttpSource, ticket: AtomicReference<DesktopBrowserLoginTicket?>) =
        io.mockk.mockk<DesktopUiDependencies> {
            io.mockk.every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            io.mockk.every { appPreferences } returns sourceBrowseHistoryPreferences()
            val extensionManager = sourceBrowseExtensionManager()
            io.mockk.every { this@mockk.extensionManager } returns extensionManager
            io.mockk.every { sourceExtensionLookup } returns extensionManager
            io.mockk.every { sourceMangaSearchService } returns SourceMangaSearchService()
            io.mockk.every { saveSourceMangaForDetails } returns
                io.mockk.mockk<SaveSourceMangaForDetails>(relaxed = true)
            io.mockk.every { getManga } returns io.mockk.mockk<GetManga>(relaxed = true)
            io.mockk.every { sourceLoginSessionFactory } returns DesktopSourceLoginSessionFactory(
                AuthenticatedSessionCommitter { _, _ -> },
                DesktopBrowserOpener { _, completion -> ticket.set(completion); true },
            )
        }

    private class RoutedHttpSource(override val baseUrl: String, override val client: OkHttpClient) : HttpSource() {
        override val id = 6004L
        override val name = "Observed"
        override val lang = "en"
        override val supportsLatest = false
        override fun popularMangaRequest(page: Int) = Request.Builder().url("$baseUrl/popular").build()
        override fun popularMangaParse(response: okhttp3.Response) = MangasPage(emptyList(), false)
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
}
