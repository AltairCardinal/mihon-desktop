package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchSourceFilterWiringTest {

    @Test
    fun `global search defaults to pinned and all chip incrementally includes unpinned sources`() = runBlocking {
        val pinnedCalls = AtomicInteger()
        val unpinnedCalls = AtomicInteger()
        val pinned = source(1, "Pinned source", pinnedCalls)
        val unpinned = source(2, "Unpinned source", unpinnedCalls)
        val preferences = mockk<DesktopAppPreferences> {
            every { enabledLanguages } returns preference(setOf("en"))
            every { disabledSources } returns preference(emptySet())
            every { pinnedSources } returns preference(setOf(pinned.id.toString()))
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(pinned, unpinned))
            every { appPreferences } returns preferences
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        var coordinator: DesktopGlobalSearchCoordinator? = null
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides dependencies,
                LocalGlobalSearchCoordinatorFactory provides { service ->
                    DesktopGlobalSearchCoordinator(service).also { coordinator = it }
                },
            ) { Navigator(GlobalSearchScreen("same query")) { CurrentScreen() } }
        }
        scene.render()
        withTimeout(2_000) { requireNotNull(coordinator).states.first { it.generation == 1L && !it.isSearching } }
        scene.render()

        val pinnedLabel = MR.strings.pinned_sources.localized()
        val allLabel = MR.strings.all.localized()
        assertTrue(selected(scene, pinnedLabel))
        assertFalse(selected(scene, allLabel))
        assertEquals(1, pinnedCalls.get())
        assertEquals(0, unpinnedCalls.get())

        click(scene, allLabel)
        withTimeout(2_000) { requireNotNull(coordinator).states.first { it.generation == 2L && !it.isSearching } }
        scene.render()

        assertFalse(selected(scene, pinnedLabel))
        assertTrue(selected(scene, allLabel))
        assertEquals(1, pinnedCalls.get(), "same-query intersection must be reused")
        assertEquals(1, unpinnedCalls.get(), "only the newly included source should load")
        scene.close()
    }

    private fun source(id: Long, name: String, calls: AtomicInteger): CatalogueSource = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { lang } returns "en"
        every { getFilterList() } returns FilterList()
        coEvery { getSearchManga(1, any(), any()) } answers {
            calls.incrementAndGet()
            MangasPage(emptyList(), false)
        }
    }

    private fun preference(value: Set<String>): Preference<Set<String>> = mockk { every { get() } returns value }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun selected(scene: ImageComposeScene, label: String) = nodes(scene).any {
        it.config.toString().contains(label) && it.config.contains(SemanticsProperties.Selected) && it.config[SemanticsProperties.Selected]
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.toString().contains(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
}
