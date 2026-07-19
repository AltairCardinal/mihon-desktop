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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceMangaSearchService
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
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            val loginLabel = desktopSourceRecoveryActionLabel(tachiyomi.domain.source.service.SourceRecoveryAction.OpenLogin)!!
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SourceBrowseScreen(source.id)) { CurrentScreen() }
                }
            }
            withTimeout(2_000) {
                while (nodes().none { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(loginLabel) }) {
                    scene.render()
                    delay(10)
                }
            }
            val recovery = nodes().first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(loginLabel) }
            assertTrue(requireNotNull(recovery.config[SemanticsActions.OnClick].action).invoke())
            withTimeout(2_000) { while (ticket.get() == null) { scene.render(); delay(10) } }
            val port = requireNotNull(SourceBrowseTestModeBridge.port)
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
            assertNotNull(port.snapshot().login)
            assertTrue(port.cancel(requireNotNull(active.login).attemptToken).success)
            assertFalse(requireNotNull(ticket.get()).cancel())
            assertNull(port.snapshot().login)
            scene.render()
            assertTrue(nodes().none { it.config.contains(SemanticsActions.SetText) })
            scene.close()
            assertNull(SourceBrowseTestModeBridge.port)

            var login: DesktopSourceLoginUiState? = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "fixture")
            var cancelCalls = 0
            val rejectedActions = DesktopSourceLoginUiActions({ _, _ -> false }) { cancelCalls++; false }
            val validationPort = SourceBrowseTestModeObservationPort(
                source.id,
                SourceBrowseQueryCoordinator(SourceMangaSearchService()),
                this,
                { login },
                { login = it },
                rejectedActions,
            )
            val stale = requireNotNull(validationPort.snapshot().login).attemptToken
            login = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "replacement")
            val current = requireNotNull(validationPort.snapshot().login).attemptToken
            assertNotEquals(stale, current)
            assertEquals(SourceBrowseTestFailureCode.ATTEMPT_MISMATCH, validationPort.cancel(stale).failureCode)
            login = requireNotNull(login).copy(feedback = DesktopSourceLoginFeedback.TimedOut, terminal = true)
            assertEquals(SourceBrowseTestLoginFeedback.TIMED_OUT, validationPort.snapshot().login?.feedback)
            assertTrue(validationPort.snapshot().login?.terminal == true)
            assertEquals(SourceBrowseTestFailureCode.TERMINAL, validationPort.cancel(current).failureCode)
            login = null
            assertEquals(SourceBrowseTestFailureCode.NO_ACTIVE_LOGIN, validationPort.cancel(current).failureCode)
            login = DesktopSourceLoginUiState(DesktopSourceLoginAttempt(), "rejected")
            val rejectedToken = requireNotNull(validationPort.snapshot().login).attemptToken
            assertEquals(SourceBrowseTestFailureCode.OPERATION_REJECTED, validationPort.cancel(rejectedToken).failureCode)
            assertEquals(1, cancelCalls)
            SourceBrowseTestModeBridge.install(port)
            SourceBrowseTestModeBridge.install(validationPort)
            assertFalse(SourceBrowseTestModeBridge.clear(port))
            assertSame(validationPort, SourceBrowseTestModeBridge.port)
            assertTrue(SourceBrowseTestModeBridge.clear(validationPort))
            validationPort.close()
        }
    }

    private fun mockkDependencies(source: HttpSource, ticket: AtomicReference<DesktopBrowserLoginTicket?>) =
        io.mockk.mockk<DesktopUiDependencies> {
            io.mockk.every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            io.mockk.every { sourceMangaSearchService } returns SourceMangaSearchService()
            io.mockk.every { saveSourceMangaForDetails } returns io.mockk.mockk<SaveSourceMangaForDetails>(relaxed = true)
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
