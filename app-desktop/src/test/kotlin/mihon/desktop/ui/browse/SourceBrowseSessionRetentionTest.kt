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
import androidx.compose.ui.semantics.SemanticsProperties
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
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.library.LocalMangaDetailScreenModelFactory
import mihon.desktop.ui.library.MangaDetailScreenModel
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQuery
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class)
class SourceBrowseSessionRetentionTest {

    private class DetailScreen : Screen {
        @Composable
        override fun Content() {
            Box(Modifier.fillMaxSize()) { Text("detail screen") }
        }
    }

    private class ReaderScreen : Screen {
        override val key: String get() = "ReaderScreen-1"

        @Composable
        override fun Content() {
            Box(Modifier.fillMaxSize()) { Text("reader screen") }
        }
    }

    @Test
    fun `returning through a detail and the reader restores the cached results`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(SourceBrowseScreen(fixture.source.id), coroutineContext)
        try {
            awaitText(scene, "retained title")
            assertEquals(1, fixture.popularCalls.get())

            requireNotNull(fixture.navigator).push(DetailScreen())
            requireNotNull(fixture.navigator).push(ReaderScreen())
            scene.render()
            awaitText(scene, "reader screen")

            // Back to the detail, then back to the browse screen.
            requireNotNull(fixture.navigator).pop()
            requireNotNull(fixture.navigator).pop()
            withTimeout(2_000) {
                while (!text(scene).contains("retained title")) {
                    scene.render()
                    yield()
                }
            }
            assertEquals(1, fixture.popularCalls.get(), "returning through the reader must not query the source again")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `returning from a manga detail restores the cached results without querying the source again`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(SourceBrowseScreen(fixture.source.id), coroutineContext)
        try {
            awaitText(scene, "retained title")
            assertEquals(1, fixture.popularCalls.get())

            // Opening a manga pushes a detail screen on the same navigator.
            requireNotNull(fixture.navigator).push(DetailScreen())
            scene.render()
            awaitText(scene, "detail screen")
            assertEquals(1, fixture.popularCalls.get())

            // Going back must restore the previous results without a new source request.
            requireNotNull(fixture.navigator).pop()
            withTimeout(2_000) {
                while (!text(scene).contains("retained title")) {
                    scene.render()
                    yield()
                }
            }
            assertEquals(1, fixture.popularCalls.get(), "returning must not query the source again")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `opening a manga prepares the detail entry even after the browse screen was left`() = runBlocking {
        val fixture = Fixture()
        val scene = fixture.mount(SourceBrowseScreen(fixture.source.id), coroutineContext)
        try {
            awaitText(scene, "retained title")
            clickMangaCardIn(scene)

            // The browse screen is disposed by the navigation, but the detail entry must still
            // be prepared, otherwise the detail screen has to load it from the source again.
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
    fun `a retained session reuses only the results of the listing that produced them`() = runBlocking {
        val fixture = Fixture()
        val session = DesktopSourceBrowseSession(sourceId = fixture.source.id, initialQuery = null)
        val listing = session.listingQueryState(FilterList())
        val coordinator = session.coordinator(SourceMangaSearchService())

        coordinator.load(fixture.source, 1, listing.value)
        assertTrue(session.holdsResultsOf(coordinator.state))

        listing.value = SourceQuery.Latest
        assertFalse(session.holdsResultsOf(coordinator.state), "a different listing must not reuse the cached results")
    }

    private class Fixture {
        val popularCalls = AtomicInteger()
        val preparedCalls = AtomicInteger()
        val source: Source = mockk {
            every { id } returns 92L
            every { name } returns "Retained source"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getPopularManga(1) } answers {
                popularCalls.incrementAndGet()
                MangasPage(listOf(manga()), false)
            }
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
            every { appPreferences } returns sourceBrowseHistoryPreferences()
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

        private fun manga() = SManga.create().apply {
            url = "/retained"
            title = "retained title"
            thumbnail_url = "/retained-cover"
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

    private fun clickMangaCardIn(scene: ImageComposeScene) {
        val clickable = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("retained title")
        }
        requireNotNull(clickable.config[SemanticsActions.OnClick].action).invoke()
    }

    private fun text(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
}
