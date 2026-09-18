package mihon.domain.extension

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.ExtensionSuggestions
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager

class ObserveExtensionSuggestionsTest {
    @Test
    fun `real subscription gates registration and reacts to library inventory and content changes`() = runTest {
        val source = Source(71, "en", "Source", false, true)
        val counts = MutableStateFlow(listOf(source to 4L))
        val initialized = MutableStateFlow(false)
        val sources = MutableStateFlow(emptyList<eu.kanade.tachiyomi.source.Source>())
        val manager = object : SourceManager {
            override val isInitialized = initialized
            override val querySources = sources
            override val catalogueSources = sources.map { it.filterIsInstance<CatalogueSource>() }
            override fun get(sourceKey: Long) = sources.value.find { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long) =
                get(sourceKey) ?: StubSource(sourceKey, "", "")
            override fun getOnlineSources() = sources.value.filterIsInstance<HttpSource>()
            override fun getCatalogueSources() = sources.value.filterIsInstance<CatalogueSource>()
            override fun getStubSources() = emptyList<StubSource>()
        }
        val repository = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns counts
        }
        val artifact = ExtensionArtifact(
            "Name", "pkg.one", "1.6.1", 1, "en", true,
            listOf(ExtensionSourceDescriptor(71, "en", "Name", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repo", "key"), "https://repo.example/a.apk", "", null,
        )
        val catalog = MutableStateFlow<ExtensionCatalogResult?>(
            ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)),
                emptyList(),
            ),
        )
        val inventory = MutableStateFlow(ExtensionInventory(true))
        val nsfw = MutableStateFlow(true)
        var latest = ExtensionSuggestions()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveExtensionSuggestions(repository, manager).subscribe(catalog, inventory, nsfw).collect { latest = it }
        }
        assertTrue(latest.isLoading)
        initialized.value = true
        assertEquals(4L, latest.suggestions.single().mangaCount)
        counts.value = listOf(source to 1L)
        assertEquals(1L, latest.suggestions.single().mangaCount)
        inventory.value = ExtensionInventory(true, mapOf("pkg.one" to ExtensionPresence.LOAD_FAILED))
        assertTrue(latest.suggestions.isEmpty())
        inventory.value = ExtensionInventory(true)
        assertEquals(1, latest.suggestions.size)
        nsfw.value = false
        assertTrue(latest.suggestions.isEmpty())
        nsfw.value = true
        counts.value = emptyList()
        assertTrue(latest.suggestions.isEmpty())
        assertFalse(latest.isLoading)
    }
}
