package mihon.desktop.ui.authors

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

@OptIn(ExperimentalComposeUiApi::class)
class AuthorsProductionWiringTest {

    @Test
    fun `mounted author detail confirms manual alias removal through typed repository`() = runBlocking {
        val creator = Creator(
            id = 7L,
            displayName = "Jane Doe",
            normalizedName = "jane doe",
            sortName = null,
            aliases = listOf("J. Doe"),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val creatorRepository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(listOf(creator))
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7L) } returns emptyList()
        }
        val archiveRepository = mockk<CreatorArchiveRepository> {
            coEvery { getManualCreatorAliases(7L) } returnsMany listOf(
                listOf("J. Doe"),
                emptyList(),
            )
            coEvery { removeManualCreatorAlias(7L, "J. Doe") } returns Unit
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(creatorRepository)
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { setCreatorFollow } returns SetCreatorFollow(creatorRepository)
            every { discoverCreatorWorks } returns mockk()
            every { sourceManager } returns mockk()
            every { saveSourceMangaForDetails } returns mockk()
            every { creatorArchiveRepository } returns archiveRepository
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
        }
        val removeLabel = MR.strings.desktop_ui_remove_named_author_alias.localized(Locale.getDefault(), "J. Doe")
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creatorId = 7L))
                }
            }
            withTimeout(5_000) {
                while (removeLabel !in texts(scene)) {
                    scene.render()
                }
            }

            clickableTextNode(scene, removeLabel).config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
            assertTrue(
                MR.strings.desktop_ui_remove_author_alias_summary.localized(Locale.getDefault(), "J. Doe") in
                    texts(scene),
            )

            clickableTextNode(scene, MR.strings.action_remove.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            coVerify(timeout = 5_000, exactly = 1) {
                archiveRepository.removeManualCreatorAlias(7L, "J. Doe")
            }
            withTimeout(5_000) {
                while (removeLabel in texts(scene)) {
                    scene.render()
                }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted authors root renders production failure and retry reaches empty library`() = runBlocking {
        val source = FailsOnceLibrarySource()
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = NoopCreatorLibraryIndexWriter,
            extractCreators = ExtractCreatorsFromManga(),
        )
        val repository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(emptyList())
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { creatorLibraryIndexer } returns indexer
        }
        indexer.start(this)
        withTimeout(5_000) { indexer.state.filterIsInstance<CreatorLibraryIndexState.Failed>().first() }

        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorsRootScreen())
                }
            }
            scene.render()
            assertTrue(
                MR.strings.desktop_ui_author_index_failed.localized(Locale.getDefault(), "disk full") in texts(scene),
            )

            retryNode(scene).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) { indexer.state.filterIsInstance<CreatorLibraryIndexState.Empty>().first() }
            scene.render()

            assertTrue(MR.strings.desktop_ui_author_index_empty_library.localized() in texts(scene))
            assertTrue(source.countAttempts == 2)
        } finally {
            scene.close()
            indexer.stop()
        }
    }

    private fun retryNode(scene: ImageComposeScene): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == MR.strings.action_retry.localized() }
    }

    private fun clickableTextNode(scene: ImageComposeScene, text: String): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == text }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        flatten(owner.rootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class FailsOnceLibrarySource : CreatorLibraryMangaSource {
        var countAttempts = 0
            private set

        override suspend fun countLibraryMangaForCreatorIndex(): Long {
            countAttempts += 1
            check(countAttempts > 1) { "disk full" }
            return 0L
        }

        override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> = emptyList()
    }
}
