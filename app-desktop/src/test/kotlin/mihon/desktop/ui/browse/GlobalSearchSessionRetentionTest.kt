package mihon.desktop.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.ListedMangaForDetails
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.library.LocalMangaDetailScreenModelFactory
import mihon.desktop.ui.library.MangaDetailScreenModel
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchSessionRetentionTest {

    private class DetailScreen : Screen {
        @Composable
        override fun Content() {
            Box(Modifier.fillMaxSize()) { Text("detail screen") }
        }
    }

    @Test
    fun `returning from a manga detail restores the global search results without searching again`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(GlobalSearchScreen("retained"), coroutineContext)
        try {
            awaitText(scene, "Retained source (1)")
            assertEquals(1, fixture.searchCalls.get())

            // Opening a manga pushes a detail screen on the same navigator.
            requireNotNull(fixture.navigator).push(DetailScreen())
            scene.render()
            awaitText(scene, "detail screen")
            assertEquals(1, fixture.searchCalls.get())

            // Going back must restore the previous results without searching the sources again.
            requireNotNull(fixture.navigator).pop()
            withTimeout(2_000) {
                while (!text(scene).contains("Retained source (1)")) {
                    scene.render()
                    yield()
                }
            }
            assertEquals(1, fixture.searchCalls.get(), "returning must not search the sources again")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `opening a manga prepares the detail entry even after the global search screen was left`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(GlobalSearchScreen("retained"), coroutineContext)
        try {
            awaitText(scene, "Retained source (1)")
            clickMangaCardIn(scene)

            // The search screen is disposed by the navigation, but the detail entry must still be
            // prepared, otherwise the detail screen has to load it from the source again.
            withTimeout(5_000) {
                while (fixture.preparedCalls.get() == 0) {
                    scene.render()
                    yield()
                }
            }
            assertEquals(1, fixture.preparedCalls.get())
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a global search session keeps the results of the last search`() {
        val fixture = Fixture()
        val session = DesktopGlobalSearchSession(initialQuery = "retained", initialSearchMode = GlobalSearchMode.MANGA)

        assertEquals("retained", session.query.value)
        assertEquals(GlobalSearchMode.MANGA, session.searchMode.value)
        assertTrue(session.sourcesByGeneration.value.isEmpty())
    }

    private class Fixture {
        val searchCalls = AtomicInteger()
        val preparedCalls = AtomicInteger()
        val source: Source = mockk {
            every { id } returns 93L
            every { name } returns "Retained source"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } answers {
                searchCalls.incrementAndGet()
                MangasPage(listOf(manga()), false)
            }
        }
        private val preferences = mockk<DesktopAppPreferences> {
            every { enabledLanguages } returns preference(setOf("en"))
            every { disabledSources } returns preference(emptySet())
            every { pinnedSources } returns preference(setOf(source.id.toString()))
            every { incognitoMode } returns sourceBrowseIncognitoPreference()
            every { incognitoExtensions } returns sourceBrowseExtensionIncognitoPreference()
            every { lastUsedSource } returns sourceBrowseLastUsedPreference()
            every { globalSearchFilterState } returns mockk {
                every { get() } returns false
                every { changes() } returns flowOf(false)
            }
            every { browseRecentSearches } returns sourceBrowseRecentSearchesPreference()
        }
        private val saver = mockk<SaveSourceMangaForDetails>(relaxed = true) {
            coEvery { awaitSearchResults(any(), any()) } answers {
                firstArg<List<SManga>>().map { it.toDomainManga(secondArg()) }
            }
            coEvery { awaitListedForDetails(any(), any()) } coAnswers {
                delay(200)
                preparedCalls.incrementAndGet()
                ListedMangaForDetails(
                    manga = firstArg<SManga>().toDomainManga(secondArg()),
                    needsRefresh = false,
                )
            }
        }
        private val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns preferences
            every { extensionManager } returns sourceBrowseExtensionManager()
            every { sourceExtensionLookup } returns sourceBrowseExtensionManager()
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns saver
            every { getManga } returns mockk<GetManga> {
                every { subscribe(any<String>(), any<Long>()) } returns flowOf(null)
            }
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
            every { creatorDiscoverySourcePort } returns null
        }
        var navigator: Navigator? = null

        fun mount(screen: Screen, coroutineContext: CoroutineContext, width: Int = 900) =
            ImageComposeScene(width, 700, coroutineContext = coroutineContext) {}.also { scene ->
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides dependencies,
                        LocalMangaDetailScreenModelFactory provides { mangaId ->
                            MangaDetailScreenModel(mangaId = mangaId)
                        },
                    ) {
                        Navigator(screen) {
                            navigator = LocalNavigator.currentOrThrow
                            CurrentScreen()
                        }
                    }
                }
            }

        private fun preference(value: Set<String>): Preference<Set<String>> = mockk { every { get() } returns value }

        private fun manga() = SManga.create().apply {
            url = "/retained"
            title = "retained title"
        }
    }

    private suspend fun awaitText(scene: ImageComposeScene, expected: String) = withTimeout(2_000) {
        while (!text(scene).contains(expected)) {
            scene.render()
            yield()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.rootSemanticsNode)
    }

    private fun text(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }

    private fun clickMangaCardIn(scene: ImageComposeScene) {
        val clickable = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("retained title")
        }
        requireNotNull(clickable.config[SemanticsActions.OnClick].action).invoke()
    }
}
