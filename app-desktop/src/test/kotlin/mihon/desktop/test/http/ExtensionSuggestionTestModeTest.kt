package mihon.desktop.test.http

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mihon.desktop.extension.*
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.extension.*
import mihon.domain.extension.model.*
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.suggestion.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository

class ExtensionSuggestionTestModeTest {
    @Test
    fun `snapshot exposes associated source counts and review candidates without accepting a URL parameter`() = runTest {
        val fixture = fixture(backgroundScope)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            val row = json(controller).getValue("suggestions").jsonObject.getValue("rows").jsonArray.single().jsonObject
            val source = row.getValue("sources").jsonArray.single().jsonObject
            assertEquals(71L, source.getValue("id").jsonPrimitive.long)
            assertEquals(1L, source.getValue("count").jsonPrimitive.long)
            controller.execute("extension_suggestion_batch_request")
            val confirmation = json(controller).getValue("confirmation").jsonObject
            assertEquals("START", confirmation.getValue("mode").jsonPrimitive.content)
            assertTrue(confirmation.getValue("unavailablePackages").jsonArray.isEmpty())
            assertFalse(confirmation.getValue("hasSourceConflict").jsonPrimitive.boolean)
            assertFalse(controller.execute("extension_suggestion_website", mapOf("url" to "http://127.0.0.1:12345/arbitrary")).success)
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `suggestion actions use identities and mutate the production local panel`() = runTest {
        val fixture = fixture(backgroundScope)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            val rows = json(controller).getValue("suggestions").jsonObject.getValue("rows").jsonArray
            assertEquals(1, rows.size)
            val identity = rows.single().jsonObject.getValue("identity").jsonPrimitive.content
            assertFalse(controller.execute("extension_suggestion_ignore").success)
            assertFalse(controller.execute("extension_suggestion_ignore", mapOf("identity" to "unknown")).success)
            assertTrue(controller.execute("extension_suggestion_ignore", mapOf("identity" to identity)).success)
            runCurrent()
            assertEquals(0, fixture.model.suggestionPanel.state.value.total)
            assertTrue(controller.execute("extension_suggestion_undo").success)
            runCurrent()
            assertEquals(1, fixture.model.suggestionPanel.state.value.total)
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `batch confirmation freezes artifacts rejects stale identity and cannot submit twice`() = runTest {
        val fixture = fixture(backgroundScope)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            assertTrue(controller.execute("extension_suggestion_batch_request").success)
            val confirmation = json(controller).getValue("confirmation").jsonObject
            val id = confirmation.getValue("id").jsonPrimitive.content
            assertEquals("pkg.testmode", confirmation.getValue("artifacts").jsonArray.single().jsonObject.getValue("packageName").jsonPrimitive.content)
            assertFalse(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to "stale")).success)
            assertFalse(fixture.model.suggestionBatch.state.value.running)
            assertTrue(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to id)).success)
            assertFalse(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to id)).success)
            runCurrent()
            assertEquals(listOf(fixture.artifact), fixture.model.suggestionBatch.state.value.items.map { it.artifact })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { fixture.model.suggestionBatch.state.first { !it.running } }
            }
            assertEquals("Installed", json(controller).getValue("batch").jsonObject.getValue("items").jsonArray.single().jsonObject.getValue("result").jsonPrimitive.content)
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `website and single installation actions use only current suggestion source identities`() = runTest {
        val fixture = fixture(backgroundScope)
        val opener = mihon.desktop.platform.DesktopUrlOpener
        io.mockk.mockkObject(opener)
        every { opener.open(any()) } returns Result.success(Unit)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            val identity = suggestionIdentityKey(SuggestionIdentity.of(fixture.artifact))
            assertFalse(controller.execute("extension_suggestion_website", mapOf("identity" to identity, "sourceId" to "999")).success)
            assertTrue(controller.execute("extension_suggestion_website", mapOf("identity" to identity, "sourceId" to "71")).success)
            io.mockk.verify(exactly = 1) { opener.open("https://source.example") }
            assertTrue(controller.execute("extension_suggestion_install", mapOf("identity" to identity)).success)
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { fixture.started.await() }
            }
            io.mockk.verify(exactly = 1) { fixture.service.installExtensionStates(any(), any()) }
        } finally { fixture.model.closeAndJoin(); io.mockk.unmockkObject(opener) }
    }

    @Test
    fun `failed batch requests retry confirmation preserves batch history`() = runTest {
        val fixture = fixture(backgroundScope, failFirst = true)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            fun confirmationId() = json(controller).getValue("confirmation").jsonObject.getValue("id").jsonPrimitive.content
            assertTrue(controller.execute("extension_suggestion_batch_request").success)
            assertTrue(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to confirmationId())).success)
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { fixture.model.suggestionBatch.state.first { !it.running } }
            }
            assertTrue(fixture.model.suggestionBatch.state.value.items.single().result is SuggestionBatchResult.Failed)
            val batchId = fixture.model.suggestionBatch.state.value.id
            assertTrue(controller.execute("extension_suggestion_batch_retry_request").success)
            assertTrue(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to confirmationId())).success)
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { fixture.model.suggestionBatch.state.first { !it.running } }
            }
            assertEquals(batchId, fixture.model.suggestionBatch.state.value.id)
            assertEquals(SuggestionBatchResult.Installed, fixture.model.suggestionBatch.state.value.items.single().result)
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `dismiss consumes only the matching confirmation`() = runTest {
        val fixture = fixture(backgroundScope)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            controller.execute("extension_suggestion_batch_request")
            val id = json(controller).getValue("confirmation").jsonObject.getValue("id").jsonPrimitive.content
            assertFalse(controller.execute("extension_suggestion_batch_dismiss", mapOf("confirmationId" to "old")).success)
            assertTrue(controller.execute("extension_suggestion_batch_dismiss", mapOf("confirmationId" to id)).success)
            assertFalse(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to id)).success)
            assertFalse(fixture.model.suggestionBatch.state.value.running)
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `paused batch requires explicit resume confirmation and stop cancels only remaining`() = runTest {
        val fixture = fixture(backgroundScope, pauseFirst = true)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            fun id() = json(controller).getValue("confirmation").jsonObject.getValue("id").jsonPrimitive.content
            controller.execute("extension_suggestion_batch_request")
            controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to id()))
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { fixture.model.suggestionBatch.state.first { !it.running } }
            }
            assertEquals(1, fixture.model.suggestionBatch.state.value.remaining.size)
            assertTrue(controller.execute("extension_suggestion_batch_resume_request").success)
            val pending = id()
            assertTrue(controller.execute("extension_suggestion_batch_stop").success)
            assertFalse(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to pending)).success)
            assertTrue(fixture.model.suggestionBatch.state.value.remaining.isEmpty())
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `confirmation from before another UI batch cannot start a new transaction`() = runTest {
        val fixture = fixture(backgroundScope)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            controller.execute("extension_suggestion_batch_request")
            val id = json(controller).getValue("confirmation").jsonObject.getValue("id").jsonPrimitive.content
            assertTrue(fixture.model.confirmSuggestionBatch(fixture.model.suggestionSnapshot()))
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { fixture.model.suggestionBatch.state.first { !it.running } }
            }
            val actualBatch = fixture.model.suggestionBatch.state.value.id
            assertFalse(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to id)).success)
            assertEquals(actualBatch, fixture.model.suggestionBatch.state.value.id)
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `repository changes require explicit current replacement and source ambiguity requires selection`() = runTest {
        val fixture = fixture(backgroundScope)
        try {
            fixture.model.refresh().join(); runCurrent()
            val controller = SourceExtensionTestModeController(fixture.model)
            controller.execute("extension_suggestion_batch_request")
            val id = json(controller).getValue("confirmation").jsonObject.getValue("id").jsonPrimitive.content
            val replacement = fixture.artifact.copy(repository = RepositoryIdentity("https://new.example", "New", "new-key"))
            fixture.catalog.value = ExtensionCatalogResult(listOf(ExtensionCatalogEntry(replacement, ExtensionCompatibility.Compatible)), emptyList())
            fixture.model.refresh().join(); runCurrent()
            assertFalse(controller.execute("extension_suggestion_batch_confirm", mapOf("confirmationId" to id)).success)
            assertTrue(controller.execute("extension_suggestion_batch_replace", mapOf("confirmationId" to id,
                "identity" to suggestionIdentityKey(SuggestionIdentity.of(replacement)))).success)
            assertEquals("https://new.example", json(controller).getValue("confirmation").jsonObject.getValue("artifacts").jsonArray.single().jsonObject.getValue("repositoryUrl").jsonPrimitive.content)
            controller.execute("extension_suggestion_batch_dismiss", mapOf("confirmationId" to id))
            fixture.catalog.value = ExtensionCatalogResult(listOf(fixture.artifact, replacement).map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) }, emptyList())
            fixture.model.refresh().join(); runCurrent()
            assertTrue(fixture.model.suggestionPanel.state.value.rows.none { it.canInstall })
            assertTrue(controller.execute("extension_suggestion_choose", mapOf("sourceId" to "71",
                "identity" to suggestionIdentityKey(SuggestionIdentity.of(replacement)))).success)
            runCurrent()
            assertEquals(listOf(replacement), fixture.model.suggestionSnapshot())
        } finally { fixture.model.closeAndJoin() }
    }

    @Test
    fun `HTTP website acceptance uses only the private header and production target`(
        @org.junit.jupiter.api.io.TempDir root: java.nio.file.Path,
    ) = runTest {
        val fixture = fixture(backgroundScope, website = "http://127.0.0.1:12345/eis/unique")
        val desktop = mockk<java.awt.Desktop>(relaxed = true)
        io.mockk.mockkStatic(java.awt.Desktop::class)
        every { java.awt.Desktop.isDesktopSupported() } returns true
        every { java.awt.Desktop.getDesktop() } returns desktop
        every { desktop.isSupported(java.awt.Desktop.Action.BROWSE) } returns true
        val token = "b".repeat(64)
        val acceptance = DesktopPlatformAcceptanceController(token, mockk(), root)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            testHttpServer(platformAcceptanceController = acceptance)
        }.start()
        val controller = SourceExtensionTestModeController(fixture.model)
        SourceExtensionTestModeBridge.install(controller)
        try {
            fixture.model.refresh().join(); runCurrent()
            val base = "http://127.0.0.1:${server.resolvedConnectors().single().port}"
            val client = java.net.http.HttpClient.newHttpClient()
            val params = mapOf("identity" to suggestionIdentityKey(SuggestionIdentity.of(fixture.artifact)), "sourceId" to "71")
            fun call(header: String?): JsonObject {
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI("$base/test/action/extension_suggestion_website"))
                    .header("Content-Type", "application/json")
                if (header != null) request.header(PLATFORM_ACCEPTANCE_TOKEN_HEADER, header)
                val response = client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.encodeToString(params))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                assertFalse(response.body().contains(token))
                return Json.parseToJsonElement(response.body()).jsonObject
            }
            assertFalse(call(null).getValue("success").jsonPrimitive.boolean)
            assertTrue(call(token).getValue("success").jsonPrimitive.boolean)
            assertFalse(call(token).getValue("success").jsonPrimitive.boolean)
            io.mockk.verify(exactly = 1) { desktop.browse(java.net.URI("http://127.0.0.1:12345/eis/unique")) }
        } finally {
            SourceExtensionTestModeBridge.clear(controller)
            server.stop(0, 0)
            fixture.model.closeAndJoin()
            io.mockk.unmockkStatic(java.awt.Desktop::class)
        }
    }

    private fun json(controller: SourceExtensionTestModeController) = Json.parseToJsonElement(Json.encodeToString(controller.snapshot())).jsonObject
    private data class Fixture(val model: ExtensionsScreenModel, val artifact: ExtensionArtifact, val service: DesktopExtensionPresentationService, val catalog: MutableStateFlow<ExtensionCatalogResult>, val started: kotlinx.coroutines.CompletableDeferred<Unit>)
    private fun fixture(scope: kotlinx.coroutines.CoroutineScope, failFirst: Boolean = false, pauseFirst: Boolean = false,
        website: String = "https://source.example"): Fixture {
        var pauseNext = pauseFirst
        var failNext = failFirst
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val artifact = ExtensionArtifact("Reader", "pkg.testmode", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(71, "ja", "Source", website)),
            RepositoryIdentity("https://repo.example", "Repo", "key"), "https://repo.example/a.jar", "", null)
        val catalog = MutableStateFlow(ExtensionCatalogResult(listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList()))
        val api = spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), Json, mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        coEvery { api.refreshCatalog() } answers { catalog.value }
        val service = mockk<DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns java.io.File(System.getProperty("java.io.tmpdir"), "eis05-testmode-empty")
            every { installExtensionStates(any(), any()) } answers {
                started.complete(Unit)
                val selected = firstArg<ExtensionArtifact>()
                val guard = secondArg<(() -> Unit)?>()
                flow {
                    emit(ExtensionInstallState.Preparing); guard?.invoke()
                    if (pauseNext) {
                        pauseNext = false
                        emit(ExtensionInstallState.Failed(mihon.domain.error.AppError.Unknown(
                            mihon.domain.extension.service.ExtensionInstallInvalidated(mihon.domain.extension.service.ExtensionInstallInvalidation.CATALOG_CHANGED))))
                    } else if (failNext) {
                        failNext = false
                        emit(ExtensionInstallState.Failed(mihon.domain.error.AppError.Network(IllegalStateException("fixture offline"))))
                    } else emit(ExtensionInstallState.Installed(selected))
                }
            }
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(Source(71, "ja", "Source", false, true) to 1L))
        }
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, service, inventory = flowOf(ExtensionInventory(true))),
            scope, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())))
        return Fixture(model, artifact, service, catalog, started)
    }
}
