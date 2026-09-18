package mihon.desktop.ui.extension

import mihon.domain.extension.service.ExtensionInstallArbiter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.error.AppError
import mihon.domain.extension.model.*
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import mihon.domain.extension.suggestion.SuggestionIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
class ExtensionSuggestionActionTest {
    @Test
    fun `batch replacements respect current content setting without falling back to an older version`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runTest {
        val original = ExtensionArtifact("Reader", "pkg.content", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(71, "ja", "Source", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repo", "key"), "https://repo.example/a.jar", "", null)
        val current = original.copy(versionCode = 2, versionName = "1.6.2", isNsfw = true)
        val alternative = original.copy(repository = RepositoryIdentity("https://alternative.example", "Alternative", "other-key"))
        val catalog = ExtensionCatalogResult(listOf(original, current, alternative).map {
            ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible)
        }, emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        coEvery { api.refreshCatalog() } returns catalog
        val service = mockk<mihon.desktop.extension.DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory.toFile()
        }
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, service,
            inventory = flowOf(ExtensionInventory(true))), backgroundScope, ExtensionPresentationOptions(false, setOf("en")))
        try {
            model.refresh().join(); runCurrent()
            assertEquals(listOf(alternative), model.batchReplacementCandidates(original))
            assertFalse(model.isCurrentBatchArtifact(current))
            assertFalse(model.isCurrentBatchArtifact(original))
            model.setOptions(ExtensionPresentationOptions(true, setOf("en")))
            assertEquals(setOf(current, alternative), model.batchReplacementCandidates(original).toSet())
            assertEquals(listOf(current), model.updatedBatchSnapshot(listOf(original)))
            model.setOptions(ExtensionPresentationOptions(false, setOf("en")))
            assertEquals(listOf(alternative), model.batchReplacementCandidates(original))
            io.mockk.verify(exactly = 0) { service.installExtensionStates(any(), any()) }
        } finally {
            model.closeAndJoin()
        }
    }

    @Test
    fun `confirmed suggestion batch uses production API reservation and normal action cannot replace it`() = runTest {
        val artifact = ExtensionArtifact("Reader", "pkg.batch", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(71, "ja", "Source", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repo", "key"), "https://repo.example/a.jar", "", null)
        val catalog = ExtensionCatalogResult(listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        coEvery { api.refreshCatalog() } returns catalog
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val directory = java.nio.file.Files.createTempDirectory("batch-action").toFile()
        val service = mockk<mihon.desktop.extension.DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory
            every { installExtensionStates(any(), any()) } answers {
                val selected = firstArg<ExtensionArtifact>()
                val guard = secondArg<(() -> Unit)?>()
                flow {
                    entered.complete(Unit)
                    emit(ExtensionInstallState.Preparing)
                    gate.await()
                    guard?.invoke()
                    emit(ExtensionInstallState.Installed(selected))
                }
            }
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(Source(71, "ja", "Source", false, true) to 1L))
        }
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, service,
            inventory = flowOf(ExtensionInventory(true))), backgroundScope, ExtensionPresentationOptions(true, setOf("ja")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())))
        try {
            model.refresh().join(); runCurrent()
            val snapshot = model.suggestionSnapshot()
            assertEquals(listOf(artifact), snapshot)
            assertTrue(model.confirmSuggestionBatch(snapshot))
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    model.suggestionBatch.state.first { !it.running || it.items.any { item -> item.progress != null } }
                }
            }
            assertTrue(entered.isCompleted)
            assertFalse(model.confirmSuggestionBatch(snapshot))
            val ordinary = model.install(api.availableExtensions(catalog).single().item())
            runCurrent()
            io.mockk.verify(exactly = 1) { service.installExtensionStates(any(), any()) }
            assertTrue(model.suggestionBatch.state.value.running)
            gate.complete(Unit)
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { model.suggestionBatch.state.first { !it.running } }
            }
            assertEquals(mihon.domain.extension.suggestion.SuggestionBatchResult.Installed,
                model.suggestionBatch.state.value.items.single().result)
        } finally {
            model.closeAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `suggestion and normal list share one active operation and failed suggestion retries exact origin`() = runTest {
        val artifact = ExtensionArtifact("Reader", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(71, "ja", "Source", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repo", "key"), "https://repo.example/a.apk", "", null)
        val available = DesktopAvailableExtension(artifact.name, artifact.packageName, artifact.versionName,
            artifact.versionCode, lang = artifact.language, isNsfw = false, jarUrl = artifact.downloadUrl,
            iconUrl = "", repoUrl = artifact.repository.baseUrl, repoName = "Repo", repoFingerprint = "key",
            sources = listOf(DesktopAvailableSource(71, "ja", "Source", "https://source.example")))
        val gate = CompletableDeferred<Unit>()
        val api = mockk<DesktopExtensionApi> {
            io.mockk.every { installArbiter } returns ExtensionInstallArbiter()
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList())
            every { availableExtensions(any()) } returns listOf(available)
            coEvery { beginInstall(available, any()) } returns DesktopExtensionInstallStart.Started(flow {
                emit(ExtensionInstallState.Committing)
                gate.await()
                emit(ExtensionInstallState.Failed(AppError.Network()))
            })
        }
        val sourceRepository = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(Source(71, "ja", "Source", false, true) to 1L))
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList()),
                inventory = flowOf(ExtensionInventory(true))), backgroundScope,
            ExtensionPresentationOptions(true, setOf("ja")),
            suggestionObserver = ObserveExtensionSuggestions(sourceRepository, FakeDesktopSourceManager(emptyList())))
        try {
            model.refresh().join(); runCurrent()
            val identity = SuggestionIdentity.of(artifact)
            val first = model.installSuggestion(identity)
            runCurrent()
            assertNotNull(first)
            assertEquals(ExtensionPresentationInstallStep.Installing, model.state.value.suggestionPanel.rows.single().step)
            model.installSuggestion(identity)
            assertSame(first, model.install(available.item()))
            runCurrent()
            coVerify(exactly = 1) { api.beginInstall(available, any()) }
            model.suggestionPanel.ignore(identity); runCurrent()
            assertEquals(1, model.state.value.suggestionPanel.total)
            gate.complete(Unit)
            first!!.join(); runCurrent()
            assertEquals(ExtensionPresentationInstallStep.Error, model.state.value.suggestionPanel.rows.single().step)
            model.installSuggestion(identity)?.join(); runCurrent()
            coVerify(exactly = 2) { api.beginInstall(available, any()) }
            model.suggestionPanel.ignore(identity); runCurrent()
            assertTrue(model.state.value.suggestionPanel.rows.isEmpty())
            assertEquals("pkg.reader", model.state.value.projection!!.available.single().operationPackageName)
        } finally { model.closeAndJoin() }
    }
}
