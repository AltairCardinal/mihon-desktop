package mihon.desktop.test.http

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.browse.DesktopGlobalSearchCoordinator
import mihon.desktop.ui.browse.SourceBrowseTestModeBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.SourceMangaSearchService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowseSearchTestModeHttpTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `http exposes loading current results stale generation and detail navigation`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val currentStarted = CompletableDeferred<Unit>()
        val currentRelease = CompletableDeferred<Unit>()
        val source = mockk<CatalogueSource> {
            every { id } returns 71L
            every { name } returns "Search source"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(1, any(), any()) } coAnswers {
                when (secondArg<String>()) {
                    "old" -> {
                        oldStarted.complete(Unit)
                        oldRelease.await()
                        MangasPage(listOf(manga("/old", "Old result")), false)
                    }
                    else -> {
                        currentStarted.complete(Unit)
                        currentRelease.await()
                        MangasPage(listOf(manga("/current", "Current result")), false)
                    }
                }
            }
        }
        val save = mockk<SaveSourceMangaForDetails>()
        coEvery { save.awaitListed(any(), 71L) } returns Manga.create().copy(id = 991L)
        val controller = controller(source, save)
        BrowseSearchTestModeBridge.install(controller)
        TestNavigationController.reset()
        try {
            withServer { baseUrl ->
                val oldSearch = post(baseUrl, "/test/action/browse_search", """{"query":"old"}""")
                assertEquals(200, oldSearch.status)
                withTimeout(2_000) { oldStarted.await() }
                val oldGeneration = oldSearch.json.browse().generation()
                assertTrue(get(baseUrl, "/test/state").json.browse().searching())

                val currentSearch = post(baseUrl, "/test/action/browse_search", """{"query":"current"}""")
                assertEquals(200, currentSearch.status)
                withTimeout(2_000) { currentStarted.await() }
                val currentGeneration = currentSearch.json.browse().generation()
                assertTrue(currentGeneration > oldGeneration)

                oldRelease.complete(Unit)
                currentRelease.complete(Unit)
                val current = awaitBrowse(baseUrl) { browse ->
                    !browse.searching() && browse.rows().size == 1
                }
                assertEquals("current", current.getValue("query").jsonPrimitive.content)
                assertEquals("Current result", current.rows().single().jsonObject.getValue("title").jsonPrimitive.content)
                assertFalse(current.toString().contains("Old result"))

                val unavailableRecovery = post(
                    baseUrl,
                    "/test/action/source_login_start",
                    """{"generation":"$currentGeneration","sourceId":"71"}""",
                )
                assertEquals(409, unavailableRecovery.status)
                assertEquals("RECOVERY_UNAVAILABLE", unavailableRecovery.json.getValue("error").jsonPrimitive.content)

                val stale = post(
                    baseUrl,
                    "/test/action/browse_select",
                    """{"generation":"$oldGeneration","sourceId":"71","index":"0"}""",
                )
                assertEquals(409, stale.status)
                assertEquals("STALE_GENERATION", stale.json.getValue("error").jsonPrimitive.content)

                val selected = post(
                    baseUrl,
                    "/test/action/browse_select",
                    """{"generation":"$currentGeneration","sourceId":"71","index":"0"}""",
                )
                assertEquals(200, selected.status)
                assertEquals(991L, selected.json.browse().getValue("selectedMangaId").jsonPrimitive.content.toLong())
                assertEquals(991L, TestNavigationController.getPendingMangaId())

                coEvery { save.awaitListed(any(), 71L) } throws IllegalStateException("save rejected")
                val rejected = post(
                    baseUrl,
                    "/test/action/browse_select",
                    """{"generation":"$currentGeneration","sourceId":"71","index":"0"}""",
                )
                assertEquals(409, rejected.status)
                assertEquals("OPERATION_REJECTED", rejected.json.getValue("error").jsonPrimitive.content)
            }
        } finally {
            controller.closeAndJoin()
            TestNavigationController.reset()
        }
        assertSame(JsonNull, Json.parseToJsonElement(currentTestStateJson()).jsonObject["browse"])
        assertEquals(BrowseSearchTestFailureCode.OWNER_CLOSED, controller.execute("browse_search", mapOf("query" to "late")).failureCode)
    }

    @Test
    fun `http login completion reports rejection then retries captured search`() = runBlocking {
        val source = mockk<HttpSource> {
            every { id } returns 72L
            every { name } returns "Login source"
            every { baseUrl } returns "https://login.example"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(1, any(), any()) } throws HttpException(403) andThen
                MangasPage(listOf(manga("/authenticated", "Authenticated result")), false)
        }
        val save = mockk<SaveSourceMangaForDetails>()
        val loginFactory = DesktopSourceLoginSessionFactory(
            AuthenticatedSessionCommitter { _, _ -> },
            DesktopBrowserOpener { _, _ -> true },
        )
        val controller = controller(source, save, loginFactory)
        BrowseSearchTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val search = post(baseUrl, "/test/action/browse_search", """{"query":"protected"}""")
                val generation = search.json.browse().generation()
                val failed = awaitBrowse(baseUrl) { browse ->
                    browse.sources().singleOrNull()?.jsonObject?.get("recovery")?.jsonPrimitive?.content == "OpenLogin"
                }
                assertEquals("Authentication", failed.sources().single().jsonObject.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)

                val started = post(
                    baseUrl,
                    "/test/action/source_login_start",
                    """{"generation":"$generation","sourceId":"72"}""",
                )
                assertEquals(200, started.status)
                val sourceState = get(baseUrl, "/test/state").json.getValue("source").jsonObject
                val token = sourceState.getValue("login").jsonObject.getValue("attemptToken").jsonPrimitive.content

                val missingHeader = post(
                    baseUrl,
                    "/test/action/source_login_complete",
                    """{"attemptToken":"$token"}""",
                )
                assertEquals(400, missingHeader.status)
                assertEquals("MISSING_HEADER", missingHeader.json.getValue("error").jsonPrimitive.content)

                val rejected = post(
                    baseUrl,
                    "/test/action/source_login_complete",
                    """{"attemptToken":"$token","cookieHeader":"invalid"}""",
                )
                assertEquals(409, rejected.status)
                assertEquals("OPERATION_REJECTED", rejected.json.getValue("error").jsonPrimitive.content)
                assertEquals(
                    "INVALID_HEADER",
                    rejected.json.getValue("source").jsonObject
                        .getValue("login").jsonObject.getValue("feedback").jsonPrimitive.content,
                )

                val completed = post(
                    baseUrl,
                    "/test/action/source_login_complete",
                    """{"attemptToken":"$token","cookieHeader":"session=accepted"}""",
                )
                assertEquals(200, completed.status)
                val recovered = awaitBrowse(baseUrl) { browse ->
                    !browse.searching() && browse.rows().singleOrNull()?.jsonObject
                        ?.get("title")?.jsonPrimitive?.content == "Authenticated result"
                }
                assertEquals(1, recovered.rows().size)
                withTimeout(2_000) {
                    while (get(baseUrl, "/test/state").json.getValue("source").jsonObject["login"] != null) {
                        kotlinx.coroutines.yield()
                    }
                }

                val stale = post(
                    baseUrl,
                    "/test/action/source_login_complete",
                    """{"attemptToken":"$token","cookieHeader":"session=late"}""",
                )
                assertEquals(409, stale.status)
                assertEquals("NO_ACTIVE_LOGIN", stale.json.getValue("error").jsonPrimitive.content)
            }
        } finally {
            controller.closeAndJoin()
            SourceBrowseTestModeBridge.port?.let(SourceBrowseTestModeBridge::clear)
        }
    }

    @Test
    fun `new search retires login generation and concurrent start cannot publish its old port`() = runBlocking {
        val searchCalls = AtomicInteger()
        val commits = AtomicInteger()
        val source = mockk<HttpSource> {
            every { id } returns 73L
            every { name } returns "Generation source"
            every { baseUrl } returns "https://generation.example"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(1, any(), any()) } coAnswers {
                if (searchCalls.incrementAndGet() == 1) {
                    throw HttpException(403)
                }
                MangasPage(listOf(manga("/new", "New generation")), false)
            }
        }
        val save = mockk<SaveSourceMangaForDetails>()
        val loginFactory = DesktopSourceLoginSessionFactory(
            AuthenticatedSessionCommitter { _, _ -> commits.incrementAndGet() },
            DesktopBrowserOpener { _, _ -> true },
        )
        val controller = controller(source, save, loginFactory)
        BrowseSearchTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val first = post(baseUrl, "/test/action/browse_search", """{"query":"old"}""")
                val generation = first.json.browse().generation()
                awaitBrowse(baseUrl) { it.sources().singleOrNull()?.jsonObject?.get("recovery")?.jsonPrimitive?.content == "OpenLogin" }
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/source_login_start",
                        """{"generation":"$generation","sourceId":"73"}""",
                    ).status,
                )
                val token = get(baseUrl, "/test/state").json.getValue("source").jsonObject
                    .getValue("login").jsonObject.getValue("attemptToken").jsonPrimitive.content

                val next = post(baseUrl, "/test/action/browse_search", """{"query":"new"}""")
                assertTrue(next.json.browse().generation() > generation)
                val staleComplete = post(
                    baseUrl,
                    "/test/action/source_login_complete",
                    """{"attemptToken":"$token","cookieHeader":"session=must-not-commit"}""",
                )
                assertEquals(409, staleComplete.status)
                assertEquals("STALE_GENERATION", staleComplete.json.getValue("error").jsonPrimitive.content)
                val staleCancel = post(
                    baseUrl,
                    "/test/action/source_login_cancel",
                    """{"attemptToken":"$token"}""",
                )
                assertEquals(409, staleCancel.status)
                assertEquals("STALE_GENERATION", staleCancel.json.getValue("error").jsonPrimitive.content)
                assertEquals(0, commits.get())
                assertEquals(2, searchCalls.get(), "retired login must not retry its captured coordinator")
                assertSame(JsonNull, get(baseUrl, "/test/state").json["source"])
            }
        } finally {
            controller.closeAndJoin()
        }

        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val nextSearchEntered = CountDownLatch(1)
        val releaseNextSearch = CountDownLatch(1)
        val providerCalls = AtomicInteger()
        val racingSource = mockk<HttpSource> {
            every { id } returns 74L
            every { name } returns "Racing source"
            every { baseUrl } returns "https://racing.example"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(1, any(), any()) } coAnswers {
                if (secondArg<String>() == "old") throw HttpException(403)
                MangasPage(emptyList(), false)
            }
        }
        val racingController = BrowseSearchTestModeController(
            coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService()),
            sourcesProvider = {
                if (providerCalls.incrementAndGet() == 2) {
                    nextSearchEntered.countDown()
                    assertTrue(releaseNextSearch.await(2, TimeUnit.SECONDS))
                }
                listOf(racingSource)
            },
            saveSourceMangaForDetails = mockk(),
            loginSessionFactory = loginFactory,
        )
        BrowseSearchTestModeBridge.install(racingController)
        mockkObject(SourceBrowseTestModeBridge)
        every { SourceBrowseTestModeBridge.install(any()) } answers {
            publishEntered.countDown()
            assertTrue(releasePublish.await(2, TimeUnit.SECONDS))
            callOriginal()
        }
        try {
            withServer { baseUrl ->
                val initial = post(baseUrl, "/test/action/browse_search", """{"query":"old"}""")
                val generation = initial.json.browse().generation()
                awaitBrowse(baseUrl) { it.sources().singleOrNull()?.jsonObject?.get("recovery")?.jsonPrimitive?.content == "OpenLogin" }
                val login = async(Dispatchers.IO) {
                    post(
                        baseUrl,
                        "/test/action/source_login_start",
                        """{"generation":"$generation","sourceId":"74"}""",
                    )
                }
                assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
                val nextSearch = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/browse_search", """{"query":"new"}""")
                }
                assertTrue(nextSearchEntered.await(2, TimeUnit.SECONDS))
                releasePublish.countDown()
                releaseNextSearch.countDown()
                login.await()
                assertTrue(nextSearch.await().json.browse().generation() > generation)
                assertSame(JsonNull, get(baseUrl, "/test/state").json["source"])
            }
        } finally {
            releasePublish.countDown()
            releaseNextSearch.countDown()
            racingController.closeAndJoin()
            unmockkObject(SourceBrowseTestModeBridge)
        }
    }

    private fun controller(
        source: CatalogueSource,
        save: SaveSourceMangaForDetails,
        loginFactory: DesktopSourceLoginSessionFactory = DesktopSourceLoginSessionFactory(
            AuthenticatedSessionCommitter { _, _ -> },
            DesktopBrowserOpener { _, _ -> false },
        ),
    ) = BrowseSearchTestModeController(
        coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService()),
        sourcesProvider = { listOf(source) },
        saveSourceMangaForDetails = save,
        loginSessionFactory = loginFactory,
    )

    private fun manga(url: String, title: String) = SManga.create().also {
        it.url = url
        it.title = title
    }

    private suspend fun awaitBrowse(baseUrl: String, predicate: (JsonObject) -> Boolean): JsonObject =
        withTimeout(3_000) {
            while (true) {
                val browse = get(baseUrl, "/test/state").json.browse()
                if (predicate(browse)) return@withTimeout browse
                kotlinx.coroutines.yield()
            }
            error("unreachable")
        }

    private fun JsonObject.browse() = getValue("browse").jsonObject
    private fun JsonObject.generation() = getValue("generation").jsonPrimitive.content.toLong()
    private fun JsonObject.searching() = getValue("searching").jsonPrimitive.content.toBoolean()
    private fun JsonObject.rows() = getValue("rows").jsonArray
    private fun JsonObject.sources() = getValue("sources").jsonArray

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun get(base: String, path: String) =
        request(HttpRequest.newBuilder(URI.create(base + path)).GET().build())

    private fun post(base: String, path: String, body: String) = request(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
    )

    private fun request(request: HttpRequest): Response {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), Json.parseToJsonElement(response.body()).jsonObject)
    }

    private data class Response(val status: Int, val json: JsonObject)
}
