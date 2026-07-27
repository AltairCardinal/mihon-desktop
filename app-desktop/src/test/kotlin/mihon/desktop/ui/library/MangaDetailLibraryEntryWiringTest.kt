package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

@OptIn(ExperimentalComposeUiApi::class)
class MangaDetailLibraryEntryWiringTest {

    @Test
    fun `real MangaDetailScreen add action mounts category dialog and commits selection`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(id = 43L, title = "Screen fixture", favorite = false)
        mangaRepository.seed(manga)
        val chapterRepository = FakeChapterRepository()
        val categoryRepository = FakeCategoryRepository().apply {
            insert(Category(id = 11L, name = "Screen selected", order = 0L, flags = 0L))
            insert(Category(id = 12L, name = "Screen unselected", order = 1L, flags = 0L))
        }
        val excludedScanlators = mockk<GetExcludedScanlators> {
            every { subscribe(manga.id) } returns flowOf(emptySet())
        }
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = excludedScanlators,
            getCategories = GetCategories(categoryRepository),
            setMangaCategories = SetMangaCategories(mangaRepository),
            downloadQueue = MutableStateFlow(emptyList()),
            updateLibraryMembership = UpdateLibraryMembership(mangaRepository),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
        }
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() }
                        }
                    }
                }
            }
            renderUntil(scene) { nodes(scene).any { it.hasText("Add to library") } }

            click(scene, "Add to library")

            renderUntil(scene) { nodes(scene).any { it.hasText("Screen selected") } }
            click(scene, "Screen selected")
            click(scene, "OK")
            withTimeout(5_000) {
                while (!mangaRepository.get(manga.id)!!.favorite) delay(10)
            }
            assertEquals(listOf(11L), mangaRepository.getMangaCategoryIds(manga.id))
        } finally {
            scene.close()
        }
    }

    @Test
    fun `add to library dialog passes selected category ids through the production caller`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(id = 42L, title = "Fixture manga", favorite = false)
        mangaRepository.seed(manga)
        val categoryRepository = FakeCategoryRepository().apply {
            insert(Category(id = 7L, name = "Selected category", order = 0L, flags = 0L))
            insert(Category(id = 9L, name = "Unselected category", order = 1L, flags = 0L))
        }
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getCategories = GetCategories(categoryRepository),
            updateLibraryMembership = UpdateLibraryMembership(mangaRepository),
        )
        val scene = ImageComposeScene(700, 500, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                MangaDetailLibraryCategoryDialog(
                    manga = manga,
                    mode = MangaCategoryDialogMode.ADD_TO_LIBRARY,
                    model = model,
                    onDismiss = {},
                )
            }
            renderUntil(scene) { nodes(scene).any { node -> node.hasText("Selected category") } }
            click(scene, "Selected category")
            click(scene, "OK")

            withTimeout(5_000) {
                while (!mangaRepository.get(manga.id)!!.favorite) delay(10)
            }
            assertTrue(mangaRepository.get(manga.id)!!.favorite)
            assertEquals(listOf(7L), mangaRepository.getMangaCategoryIds(manga.id))
        } finally {
            scene.close()
        }
    }

    private suspend fun renderUntil(scene: ImageComposeScene, predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) {
                scene.render()
                delay(10)
            }
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(SemanticsActions.OnClick) &&
                flatten(candidate).any { it.hasText(label) }
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        scene.render()
    }

    private fun SemanticsNode.hasText(text: String): Boolean {
        val values = if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text]
        } else {
            emptyList()
        }
        return values.any { it.text == text }
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private object EmptySourceManager : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(emptyList())

        override fun get(sourceKey: Long): Source? = null

        override fun getOrStub(sourceKey: Long): Source = error("No source for $sourceKey")

        override fun getOnlineSources(): List<HttpSource> = emptyList()

        override fun getCatalogueSources(): List<CatalogueSource> = emptyList()

        override fun getStubSources(): List<StubSource> = emptyList()
    }
}
