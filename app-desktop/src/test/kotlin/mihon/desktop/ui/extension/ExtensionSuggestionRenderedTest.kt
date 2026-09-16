package mihon.desktop.ui.extension

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.extension.model.*
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import java.util.Locale
import androidx.compose.runtime.CompositionLocalProvider
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import java.io.File
import java.util.prefs.Preferences
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.LocalExtensionScreenModel
import mihon.desktop.extension.DesktopExtensionPresentationService
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.browse.BrowseSourceListScreen
import mihon.desktop.ui.migration.MigrationMangaScreen
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelState
import org.junit.jupiter.api.Assertions
import tachiyomi.core.common.preference.DesktopPreferenceStore
@OptIn(ExperimentalComposeUiApi::class)
class ExtensionSuggestionRenderedTest {
    @Test
    fun `installed page renders keyboard actions collapse ignore and undo at narrow width`() = runBlocking {
        val artifact = ExtensionArtifact("Suggested Reader with a long extension name", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(71, "ja", "My source", "https://source.example"),
                ExtensionSourceDescriptor(72, "en", "Another long source name", "https://another.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"), "https://repo.example/a.apk", "", null)
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList())
            every { availableExtensions(any()) } returns emptyList()
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(
                Source(71, "ja", "My source", false, true) to 3L,
                Source(72, "en", "Another long source name", false, true) to 3L))
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList()),
                inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())),
        )
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 1 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Suggested Reader")
            assertTrue(texts(scene).any { it.contains("6") })
            System.getenv("EIS_PREVIEW_PATH")?.let { path ->
                val image = scene.render()
                File(path).writeBytes(requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)
            }
            click(scene, "Ignore")
            renderUntil(scene, "Undo")
            assertFalse(texts(scene).any { it.contains("Suggested Reader") })
            click(scene, "Undo")
            renderUntil(scene, "Suggested Reader")
            click(scene, "Suggested installations")
            withTimeout(5_000) {
                while (model.state.value.suggestionPanel.expanded) { scene.render(); yield() }
            }
            withTimeout(5_000) {
                while (texts(scene).any { it.contains("Suggested Reader") }) { scene.render(); yield() }
            }
            assertFalse(texts(scene).any { it.contains("Suggested Reader") })
            assertFalse(model.state.value.suggestionPanel.expanded)
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `candidate dialog distinguishes identical repository names and signing identities`() = runBlocking {
        val source = ExtensionSourceDescriptor(71, "ja", "Source", "https://source.example")
        fun candidate(key: String): ExtensionSuggestion {
            val artifact = ExtensionArtifact("Reader", "pkg.reader", "1.6.1", 1, "ja", false,
                listOf(source), RepositoryIdentity("https://repo.example", "Same repository", key),
                "https://repo.example/a.apk", "", null)
            return ExtensionSuggestion(
                SuggestionIdentity.of(artifact), artifact,
                listOf(SuggestedSource(source, 1)), true)
        }
        val candidates = listOf(candidate("first-signing-key"), candidate("second-signing-key"))
        val state = SuggestionPanelState(loading = false,
            total = 2, choices = mapOf(71L to candidates))
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { MaterialTheme {
                ExtensionSuggestionSection(state, null, {}, {}, {}, {}, {}, {})
            } }
            renderUntil(scene, "Choose")
            click(scene, "Choose")
            renderUntil(scene, "Same repository")
            assertTrue(texts(scene).any { it.contains("https://repo.example") })
            assertTrue(texts(scene).any { it.contains("first-signing-key") })
            assertTrue(texts(scene).any { it.contains("second-signing-key") })
        } finally { scene.close() }
    }

    @Test
    fun `actual Browse extension entry opens website repositories and migration and keeps diagnosis visible`() = runBlocking {
        val source = ExtensionSourceDescriptor(71, "ja", "My source", "https://source.example")
        val artifact = ExtensionArtifact("Suggested Reader", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(source), RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/a.apk", "", null)
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList())
            every { availableExtensions(any()) } returns emptyList()
        }
        val sourceManager = FakeDesktopSourceManager(emptyList())
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(
                Source(71, "ja", "My source", false, true) to 3L,
                Source(72, "en", "Unmatched source", false, true) to 2L))
        }
        val inventory = MutableStateFlow(ExtensionInventory(true))
        val service = mockk<DesktopExtensionPresentationService>(relaxed = true)
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, service,
            MutableStateFlow(emptyList()), inventory = inventory), this,
            ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, sourceManager))
        val preferenceRoot = Preferences.userRoot().node("/mihon/eis02-browse/${System.nanoTime()}")
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.sourceManager } returns sourceManager
            every { appPreferences } returns DesktopAppPreferences(
                DesktopPreferenceStore(preferenceRoot))
        }
        val screen = BrowseSourceListScreen()
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(600, 1000, coroutineContext = coroutineContext) {}
        io.mockk.mockkObject(DesktopUrlOpener)
        every { DesktopUrlOpener.open(any(), any()) } returns Result.success(Unit)
        try {
            model.refresh().join()
            scene.setContent { MaterialTheme {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalExtensionScreenModel provides { model },
                ) {
                    Navigator(screen) { nav ->
                        navigator = nav
                        screen.Content()
                    }
                }
            } }
            renderUntil(scene, "Extensions")
            click(scene, "Extensions")
            renderUntil(scene, "Suggested Reader")
            click(scene, "Open website")
            io.mockk.verify(exactly = 1) {
                DesktopUrlOpener.open("https://source.example", any())
            }
            click(scene, "Extension repos")
            assertTrue(navigator.lastItem is ExtensionRepoScreen)
            navigator.pop()
            scene.render()
            nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
                .config[SemanticsActions.ScrollBy].action!!.invoke(0f, 300f)
            renderUntil(scene, "Migrate")
            click(scene, "Migrate")
            val migration = navigator.lastItem
            if (navigator.items.size > 1) navigator.pop()
            inventory.value = ExtensionInventory(initialized = true, hasUnknownArtifacts = true)
            renderUntil(scene, "Review installed")
            click(scene, "Review installed")
            renderUntil(scene, "Installed extensions checked.")
            Assertions.assertAll(
                { assertTrue(migration is MigrationMangaScreen) },
                { assertFalse(migration is Tab) },
                { assertTrue(model.state.value.suggestionPanel.expanded) },
                { assertTrue(texts(scene).any { it.contains("could not be verified") }) },
            )
            io.mockk.coVerify(exactly = 1) { service.reloadAll() }
        } finally {
            scene.close()
            model.closeAndJoin()
            io.mockk.unmockkObject(DesktopUrlOpener)
            preferenceRoot.removeNode()
        }
    }

    private suspend fun renderUntil(scene: ImageComposeScene, text: String) = withTimeout(5_000) {
        while (texts(scene).none { it.contains(text) }) { scene.render(); yield() }
    }
    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains(text) }
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
