package mihon.desktop.ui.extension

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository

class DesktopExtensionSuggestionRefreshTest {
    @Test
    fun `late catalog from a removed repository never becomes an install suggestion`() = runTest {
        val oldRepo = ExtensionRepo("https://old.example", "Old", null, "", "old")
        val newRepo = oldRepo.copy(baseUrl = "https://new.example", signingKeyFingerprint = "new")
        val repositories = MutableStateFlow(listOf(oldRepo))
        val oldResponse = CompletableDeferred<ExtensionCatalogResult>()
        val newResponse = CompletableDeferred<ExtensionCatalogResult>()
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers {
                if (++calls == 1) oldResponse.await() else {
                    secondStarted.complete(Unit)
                    newResponse.await()
                }
            }
            every { availableExtensions(any()) } returns emptyList()
        }
        val repository = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(Source(71, "en", "Source", false, true) to 1L))
        }
        val observer = ObserveExtensionSuggestions(repository, FakeDesktopSourceManager(emptyList()))
        val port = DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList()),
            configuredRepositories = repositories, inventory = flowOf(ExtensionInventory(true)))
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val model = ExtensionsScreenModel(port, scope, ExtensionPresentationOptions(true, emptySet()), suggestionObserver = observer)
        val seen = mutableListOf<String>()
        val collection = scope.launch {
            model.state.collect { state -> seen += state.suggestions.suggestions.map { it.artifact.packageName } }
        }
        try {
            repositories.value = listOf(newRepo)
            val artifact = ExtensionArtifact("Old", "pkg.old", "1.6.1", 1, "en", false,
                listOf(ExtensionSourceDescriptor(71, "en", "Source", "https://source.example")), oldRepo.toIdentity(),
                "https://old.example/pkg.old.apk", "", null)
            oldResponse.complete(ExtensionCatalogResult(listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)),
                emptyList(), listOf(oldRepo.toIdentity())))
            secondStarted.await()
            assertTrue(seen.isEmpty(), "Removed repository was briefly suggested: $seen")
            newResponse.complete(ExtensionCatalogResult(emptyList(), emptyList(), listOf(newRepo.toIdentity())))
        } finally {
            collection.cancel()
            model.closeAndJoin()
        }
    }
}
