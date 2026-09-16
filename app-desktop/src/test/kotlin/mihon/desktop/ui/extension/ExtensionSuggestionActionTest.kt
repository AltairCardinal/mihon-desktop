package mihon.desktop.ui.extension

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
