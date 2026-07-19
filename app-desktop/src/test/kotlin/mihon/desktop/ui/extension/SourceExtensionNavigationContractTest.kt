package mihon.desktop.ui.extension

import androidx.compose.foundation.layout.Box
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.TestScreenNavigator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.File

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
        val extension = InstalledExtension(File("C:/extensions/example.jar"), emptyList(), displayName = "Example Extension")
        val extensionList = ExtensionListScreen()
        val more = MoreRootScreen()
        val downloadManager = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.downloadManager } returns downloadManager
            every { extensionApi } returns mockk<DesktopExtensionApi>(relaxed = true).also {
                io.mockk.coEvery { it.loadExtensionIcon(any()) } returns null
            }
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        TestScreenNavigator.clear()
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(more) {
                        navigator = LocalNavigator.currentOrThrow
                        Box {
                            more.Content()
                            ExtensionCard(extension, {}, { extensionList.onOpen(navigator, extension) }) { id, name ->
                                extensionList.onSettings(navigator, id, name)
                            }
                        }
                    }
                }
            }
            scene.render()

            click(scene, "Extensions")
            Assertions.assertTrue(navigator.lastItem is ExtensionListScreen)
            navigator.pop()
            click(scene, extension.name)
            Assertions.assertEquals(extension.jarFile.absolutePath, (navigator.lastItem as ExtensionDetailsScreen).jarPath)
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

            extensionList.onSettings(navigator, 42L, "Example Source")
            ExtensionDetailsScreen(extension.jarFile.path).onSettings(navigator, 43L, "Other Source")
            ExtensionDetailsScreen(extension.jarFile.path).onBrowse(navigator, 44L)
            Assertions.assertEquals(listOf(42L, 43L), navigator.items.filterIsInstance<SourcePreferencesScreen>().map { it.sourceId })
            Assertions.assertEquals(listOf(44L), navigator.items.filterIsInstance<SourceBrowseScreen>().map { it.sourceId })
            Assertions.assertFalse(navigator.items.any { it is Tab })
        } finally {
            TestScreenNavigator.clear()
            scene.close()
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).single { candidate -> candidate.config.contains(SemanticsActions.OnClick) &&
            flatten(candidate).any { it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } } }
        Assertions.assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
