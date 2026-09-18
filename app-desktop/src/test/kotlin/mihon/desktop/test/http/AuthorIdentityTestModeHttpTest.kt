package mihon.desktop.test.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.DesktopTestDIContext
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class AuthorIdentityTestModeHttpTest {
    @Test
    fun `release test mode author commands use the real identity and preference graph`(@TempDir folder: File) = runBlocking {
        val previousDownloadPreferences = runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull()
        val previousInjekt = Injekt
        val node = Preferences.userRoot().node("mihon-author-runtime-" + UUID.randomUUID())
        val store = DesktopPreferenceStore(node)
        var context: DesktopTestDIContext? = null
        Injekt = InjektScope(DefaultRegistrar())
        try {
            context = initDesktopDIForTest(folder, store)
            val port = ServerSocket(0).use { it.localPort }
            val base = "http://127.0.0.1:$port/test/action/"
            val creators = Injekt.get<CreatorRepository>()
            val archive = Injekt.get<CreatorArchiveRepository>()
            val target = creators.upsertCreator("冈本伦")
            val alias = creators.upsertCreator("Okamoto Lynn")
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/acceptance-author", title = "Acceptance", author = "冈本伦")),
            ).single()
            TestMode.start(TestArguments(testMode = true, httpPort = port, headless = true))
            awaitReady(base)
            val resolved = post(base + "author_resolve", """{"mangaId":${manga.id},"name":"冈本伦"}""")
            assertEquals(200, resolved.statusCode(), resolved.body())
            assertEquals(target.id.toString(), authors(resolved).getValue("resolvedCreatorId").jsonPrimitive.content)
            assertEquals(404, post(base + "author_resolve", """{"mangaId":${manga.id},"name":"Not a credit"}""").statusCode())
            assertEquals(409, post(base + "author_sync_fixture", """{"step":"add"}""").statusCode())
            val targetState = archive.getIdentitySnapshot(target.id)
            val aliasState = archive.getIdentitySnapshot(alias.id)
            val merged = post(
                base + "author_add_aliases",
                """{"creatorId":${target.id},"revision":${targetState.revision},"selectedRevisions":{"${alias.id}":${aliasState.revision}},"idempotencyKey":"runtime-merge"}""",
            )
            assertEquals(200, merged.statusCode(), merged.body())
            val identity = identities(merged).single().jsonObject
            assertEquals(listOf("Okamoto Lynn", "冈本伦"), (identity.getValue("names") as JsonArray).map { it.jsonPrimitive.content }.sorted())
            val revision = identity.getValue("revision").jsonPrimitive.content
            val renamed = post(
                base + "author_set_display_name",
                """{"creatorId":${target.id},"revision":$revision,"name":"Okamoto Lynn","idempotencyKey":"runtime-name"}""",
            )
            assertEquals(200, renamed.statusCode(), renamed.body())
            assertEquals("Okamoto Lynn", identities(renamed).single().jsonObject.getValue("displayName").jsonPrimitive.content)
            val stale = post(
                base + "author_set_display_name",
                """{"creatorId":${target.id},"revision":$revision,"name":"冈本伦","idempotencyKey":"runtime-stale"}""",
            )
            assertEquals(409, stale.statusCode())
            assertEquals(400, post(base + "author_set_frequency", """{"frequency":"yearly"}""").statusCode())
            assertEquals(200, post(base + "author_set_frequency", """{"frequency":"monthly"}""").statusCode())
            assertEquals("monthly", Injekt.get<CreatorDiscoveryPreferences>().frequency().get())
            TestMode.stop()
            context.closeAndJoin()
            context = initDesktopDIForTest(folder, store)
            TestMode.start(TestArguments(testMode = true, httpPort = port, headless = true))
            awaitReady(base)
            val state = post(base + "authors_state", "{}")
            assertEquals("monthly", authors(state).getValue("frequency").jsonPrimitive.content)
            assertEquals("Okamoto Lynn", identities(state).single().jsonObject.getValue("displayName").jsonPrimitive.content)
            assertEquals(target.id, Injekt.get<CreatorArchiveRepository>().getIdentitySnapshot(alias.id).id)
        } finally {
            try {
                TestMode.stop()
                context?.closeAndJoin()
            } finally {
                Injekt = previousInjekt
                node.removeNode()
            }
        }
        assertSame(previousDownloadPreferences, runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull())
    }

    private suspend fun awaitReady(base: String) = withTimeout(10_000) {
        while (runCatching { post(base + "authors_state", "{}").statusCode() }.getOrNull() != 200) delay(25)
    }

    private fun authors(response: HttpResponse<String>): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("authors").jsonObject

    private fun identities(response: HttpResponse<String>): JsonArray = authors(response).getValue("identities") as JsonArray

    private fun post(url: String, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
