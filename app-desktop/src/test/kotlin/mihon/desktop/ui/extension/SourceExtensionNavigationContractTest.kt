package mihon.desktop.ui.extension

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.source.ConfigurableSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.TestScreenNavigator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class SourceExtensionNavigationContractTest {
    @Test
    fun `source and extension destinations are regular Screens with preserved parameters`() {
        val details = extensionDetailsDestination("C:/extensions/example.jar")
        val preferences = sourcePreferencesDestination(42L, "Example Source")
        val browse = sourceBrowseDestination(42L)
        Assertions.assertEquals("C:/extensions/example.jar", details.jarPath)
        Assertions.assertEquals(42L, preferences.sourceId)
        Assertions.assertEquals("Example Source", preferences.sourceName)
        val expectedInitialQuery: String? = null
        Assertions.assertEquals(expectedInitialQuery, browse.initialQuery)
    }

    @Test
    fun `production clicks and mounted More automation push regular Screen destinations`() = runBlocking {
        val more = MoreRootScreen()
        val downloadManager = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.downloadManager } returns downloadManager
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        TestScreenNavigator.clear()
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(more) {
                        navigator = LocalNavigator.currentOrThrow
                        more.Content()
                    }
                }
            }
            scene.render()

            click(scene, MR.strings.label_extensions.localized())
            Assertions.assertTrue(navigator.lastItem is ExtensionListScreen)
            navigator.pop()

            TestScreenNavigator.navigateTo("open_extensions")
            withTimeout(1_000) {
                while (TestScreenNavigator.pendingScreen.value != null) {
                    scene.render()
                    yield()
                }
            }
            Assertions.assertTrue(navigator.lastItem is ExtensionListScreen)
            Assertions.assertNull(TestScreenNavigator.pendingScreen.value)

            ExtensionDetailsScreen("C:/extensions/example.jar").onSettings(navigator, 43L, "Other Source")
            ExtensionDetailsScreen("C:/extensions/example.jar").onBrowse(navigator, 44L)
            Assertions.assertEquals(listOf(43L), navigator.items.filterIsInstance<SourcePreferencesScreen>().map { it.sourceId })
            Assertions.assertEquals(listOf(44L), navigator.items.filterIsInstance<SourceBrowseScreen>().map { it.sourceId })
            Assertions.assertFalse(navigator.items.any { it is Tab })
        } finally {
            TestScreenNavigator.clear()
            scene.close()
        }
    }

    @Test
    fun `real Extension list clicks preserve details and source settings parameters`() = runBlocking {
        val source = mockk<ConfigurableSource> {
            every { id } returns 42L
            every { name } returns "Example Source"
            every { lang } returns "en"
        }
        val extension = InstalledExtension(
            File("C:/extensions/example.jar"),
            listOf(source),
            displayName = "Example Extension",
            language = "en",
        )
        val installed = MutableStateFlow(listOf(extension))
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installed),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionApi } returns api
            every { extensionManager } returns manager
        }
        val screen = ExtensionListScreen()
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previous = Injekt
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) {
                        navigator = LocalNavigator.currentOrThrow
                        screen.Content()
                    }
                }
            }
            withTimeout(5_000) { model.state.first { it.presentation?.installed?.singleOrNull()?.installed === extension } }
            withTimeout(5_000) {
                while (nodes(scene).none { it.config.contains(SemanticsProperties.Text) &&
                        it.config[SemanticsProperties.Text].any { text -> text.text == extension.name }
                    }
                ) {
                    scene.render()
                    yield()
                }
            }

            click(scene, extension.name)
            Assertions.assertEquals(extension.jarFile.absolutePath, (navigator.lastItem as ExtensionDetailsScreen).jarPath)
            navigator.pop()
            scene.render()
            clickDescription(scene, MR.strings.desktop_extension_source_settings.localized(Locale.getDefault(), source.name))
            val settings = navigator.lastItem as SourcePreferencesScreen
            Assertions.assertEquals(source.id, settings.sourceId)
            Assertions.assertEquals(source.name, settings.sourceName)
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previous
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val allNodes = nodes(scene)
        val node = allNodes.singleOrNull { candidate -> candidate.config.contains(SemanticsActions.OnClick) &&
            flatten(candidate).any { it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } } }
            ?: error("Missing clickable '$label': ${allNodes.joinToString { it.config.toString() }}")
        Assertions.assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun clickDescription(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) &&
            it.config.contains(SemanticsProperties.ContentDescription) && label in it.config[SemanticsProperties.ContentDescription] }
        Assertions.assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
