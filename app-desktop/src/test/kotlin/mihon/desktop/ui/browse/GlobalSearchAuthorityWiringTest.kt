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
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.i18n.MR
import java.util.UUID
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchAuthorityWiringTest {

    @Test
    fun `rows progress and persisted has-results filter are wired through production UI`() = runBlocking {
        val root = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = DesktopAppPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))
        val release = CompletableDeferred<Unit>()
        val content = source(1, "Content source") { MangasPage(listOf(manga("/content")), false) }
        val empty = source(2, "Empty source") { MangasPage(emptyList(), false) }
        val failure = source(3, "Failure source") { error("broken payload") }
        val loading = source(4, "Loading source") {
            release.await()
            MangasPage(listOf(manga("/loaded")), false)
        }
        val sources = listOf(content, empty, failure, loading)
        preferences.enabledLanguages.set(setOf("en"))
        preferences.pinnedSources.set(sources.map { it.id.toString() }.toSet())
        var activePreferences = preferences
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(sources)
            every { appPreferences } answers { activePreferences }
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk<SaveSourceMangaForDetails>(relaxed = true) {
                coEvery { awaitSearchResults(any(), any()) } answers {
                    firstArg<List<SManga>>().distinctBy(SManga::url).map { it.toDomainManga(secondArg()) }
                }
            }
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        var coordinator: DesktopGlobalSearchCoordinator? = null
        var scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        fun mount() {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalGlobalSearchCoordinatorFactory provides { service ->
                        DesktopGlobalSearchCoordinator(service).also { coordinator = it }
                    },
                ) { Navigator(GlobalSearchScreen("authority")) { CurrentScreen() } }
            }
        }
        try {
            mount()
            scene.render()
            withTimeout(2_000) {
                requireNotNull(coordinator).states.first { state ->
                    state.queryStates.size == 4 && state.queryStates.values.count { it !is SourceQueryState.Loading } == 3
                }
            }
            scene.render()
            scene.render()
            val partial = semantics(scene)
            listOf("Content source (1)", "Empty source (0)", "Failure source (0)", "Loading source (0)", "3 / 4", "Searching").forEach {
                assertTrue(partial.contains(it), "missing partial-search feedback: $it")
            }
            assertTrue(partial.contains(MR.strings.loading.localized()))
            assertTrue(partial.contains(MR.strings.no_results_found.localized()))
            assertTrue(partial.contains(MR.strings.unknown_error.localized()))
            assertTrue(partial.contains(MR.strings.action_retry.localized()))

            release.complete(Unit)
            withTimeout(2_000) { requireNotNull(coordinator).states.first { !it.isSearching } }
            scene.render()
            scene.render()
            val hasResults = MR.strings.has_results.localized()
            assertFalse(selected(scene, hasResults))
            assertTrue(selected(scene, MR.strings.pinned_sources.localized()))
            assertFalse(selected(scene, MR.strings.all.localized()))
            assertTrue(semantics(scene).contains("4 / 4"))

            click(scene, hasResults)
            scene.render()
            val filtered = semantics(scene)
            assertTrue(selected(scene, hasResults))
            assertTrue(filtered.contains("Content source (1)"))
            assertTrue(filtered.contains("Loading source (1)"))
            assertFalse(filtered.contains("Empty source (0)"))
            assertFalse(filtered.contains("Failure source (0)"))
            assertTrue(preferences.globalSearchFilterState.get())
            scene.close()
            activePreferences = DesktopAppPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))
            coordinator = null
            scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
            mount()
            scene.render()
            withTimeout(2_000) {
                requireNotNull(coordinator).states.first { state ->
                    state.generation > 0 && !state.isSearching && state.queryStates.size == sources.size
                }
            }
            scene.render()
            scene.render()
            val restored = semantics(scene)
            assertTrue(selected(scene, hasResults))
            assertTrue(restored.contains("Content source (1)"))
            assertTrue(restored.contains("Loading source (1)"))
            assertFalse(restored.contains("Empty source (0)"))
            assertFalse(restored.contains("Failure source (0)"))
        } finally {
            scene.close()
            root.removeNode()
        }
    }

    private fun source(id: Long, name: String, response: suspend () -> MangasPage): CatalogueSource = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { lang } returns "en"
        every { getFilterList() } returns FilterList()
        coEvery { getSearchManga(1, any(), any()) } coAnswers { response() }
    }

    private fun manga(url: String) = SManga.create().apply {
        this.url = url
        title = url
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun semantics(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
    private fun selected(scene: ImageComposeScene, label: String) = nodes(scene).any {
        it.config.toString().contains(label) && it.config.contains(SemanticsProperties.Selected) && it.config[SemanticsProperties.Selected]
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.toString().contains(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
}
