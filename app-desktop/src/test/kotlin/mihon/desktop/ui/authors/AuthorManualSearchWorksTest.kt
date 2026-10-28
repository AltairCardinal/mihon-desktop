package mihon.desktop.ui.authors

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
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.browse.GlobalSearchScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences

/**
 * The author page exposes a manual work search that mirrors the original app behaviour of
 * searching every source with the author name, and the author page must stay on the back stack.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AuthorManualSearchWorksTest {

    @TempDir lateinit var directory: Path

    @Test
    fun `manual search searches every source with the author name and keeps the author page`() = runBlocking {
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
            every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
            coEvery { getPresentationExclusions(any()) } returns emptySet()
            coEvery { getManualCreatorAliases(7L) } returns listOf("J. Doe")
            every { observeSourceCheckpoints(7L) } returns flowOf(emptyList())
            every { observeCreatorWorkArchive(7L) } returns flowOf(
                CreatorWorkArchive(emptyList(), emptyList(), emptyList()),
            )
        }
        val searchedQueries = mutableListOf<String>()
        val source = mockk<CatalogueSource> {
            every { id } returns 42L
            every { lang } returns "en"
            every { name } returns "Recording Source"
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } answers {
                searchedQueries += secondArg<String>()
                MangasPage(emptyList(), false)
            }
        }
        val sourceManagerMock = object : SourceManager {
            override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
            override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(listOf(source))
            override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long): Source = source
            override fun getOnlineSources(): List<HttpSource> = emptyList()
            override fun getCatalogueSources(): List<CatalogueSource> = listOf(source)
            override fun getStubSources(): List<StubSource> = emptyList()
        }
        val appPreferences = DesktopAppPreferences(
            store = InMemoryPreferenceStore(),
            legacy = Preferences.userRoot().node("/mihon-tests/author-manual-search-${UUID.randomUUID()}"),
        )
        appPreferences.pinnedSources.set(setOf("42"))
        val dependencies = mockk<DesktopUiDependencies> {
            every { customCoverStore } returns DesktopCustomCoverStore(directory.resolve("covers").toFile())
            every { getCreators } returns GetCreators(creatorRepository)
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { setCreatorFollow } returns SetCreatorFollow(creatorRepository)
            every { discoverCreatorWorks } returns mockk()
            every { sourceManager } returns sourceManagerMock
            every { saveSourceMangaForDetails } returns mockk()
            every { creatorArchiveRepository } returns archiveRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
            every { creatorDiscoveryScheduler } returns null
            every { libraryPreferences } returns null
            every { this@mockk.appPreferences } returns appPreferences
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { getManga } returns mockk(relaxed = true)
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
            every { creatorDiscoverySourcePort } returns null
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        var navigator: Navigator? = null
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creatorId = 7L)) {
                        navigator = it
                        CurrentScreen()
                    }
                }
            }
            withTimeout(5_000) {
                while (MR.strings.desktop_ui_check_new_works.localized() !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }

            val check = clickableTextNode(scene, MR.strings.desktop_ui_check_new_works.localized())
            val manualSearch = clickableTextNode(scene, MR.strings.desktop_ui_manual_search_works.localized())
            assertEquals(check.boundsInRoot.top, manualSearch.boundsInRoot.top, 1f)

            val currentNavigator = checkNotNull(navigator)
            manualSearch.config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while (searchedQueries.isEmpty()) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }

            val pushed = currentNavigator.items.last()
            assertTrue(pushed is GlobalSearchScreen, "expected the global search screen but was $pushed")
            assertEquals("Jane Doe", (pushed as GlobalSearchScreen).initialQuery)
            assertEquals(
                listOf("Jane Doe"),
                searchedQueries,
                "the search screen must query the source with the author name, screen texts: ${texts(scene)}",
            )
            assertTrue(
                MR.strings.has_results.localized() in texts(scene),
                "the global search must open in the manga mode",
            )
            assertFalse(
                MR.strings.desktop_ui_verified_author_results.localized() in texts(scene),
                "the global search must not open in the author mode",
            )

            currentNavigator.pop()
            scene.render()
            assertTrue(currentNavigator.items.last() is AuthorDetailScreen)
        } finally {
            scene.close()
        }
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

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner ->
            flatten(owner.rootSemanticsNode)
        }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
