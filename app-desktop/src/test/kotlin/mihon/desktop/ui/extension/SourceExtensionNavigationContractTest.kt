package mihon.desktop.ui.extension

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
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
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.desktop.ui.settings.DesktopSettingsAnchorHighlighted
import mihon.desktop.ui.settings.DesktopSettingsAnchorOwner
import mihon.desktop.ui.settings.DesktopSettingsCatalog
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.TestScreenNavigator
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class SourceExtensionNavigationContractTest {
    @Test
    fun `Extension repository catalog anchors empty and list branches then deletes exact repo`() = runBlocking {
        val repository = FakeExtensionRepoRepository()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { getExtensionRepo } returns GetExtensionRepo(repository)
            every { createExtensionRepo } returns mockk<CreateExtensionRepo>(relaxed = true)
            every { deleteExtensionRepo } returns DeleteExtensionRepo(repository)
            every { replaceExtensionRepo } returns ReplaceExtensionRepo(repository)
            every { updateExtensionRepo } returns mockk<UpdateExtensionRepo>(relaxed = true)
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(900, 500, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(EmptyScreen()) { nav -> navigator = nav; CurrentScreen() }
                }
            }
            render(scene)

            val addTitle = MR.strings.action_add_repo.localized()
            val addResult = DesktopSettingsCatalog.search(addTitle).single {
                it.route is ExtensionRepoScreen && it.anchorTitle == addTitle
            }
            DesktopSettingsAnchorOwner.publish(addResult.route, addResult.anchorTitle)
            navigator.replace(addResult.route)
            render(scene)
            val emptyHighlight = highlighted(scene)
            Assertions.assertTrue(descriptionCopy(emptyHighlight).contains(addTitle))
            Assertions.assertTrue(emptyHighlight.boundsInRoot.height > 0f)

            navigator.replace(EmptyScreen())
            render(scene)
            repository.insertRepo("https://first.example", "First Repo", "First", "https://first.example", "first-fp")
            repository.insertRepo("https://second.example", "Second Repo", "Second", "https://second.example", "second-fp")
            val deleteTitle = MR.strings.action_delete_repo.localized()
            val deleteResult = DesktopSettingsCatalog.search(deleteTitle).single {
                it.route is ExtensionRepoScreen && it.anchorTitle == deleteTitle
            }
            DesktopSettingsAnchorOwner.publish(deleteResult.route, deleteResult.anchorTitle)
            navigator.replace(deleteResult.route)
            render(scene)
            val listHighlight = highlighted(scene)
            Assertions.assertTrue(textCopy(listHighlight).contains("First Repo"))
            Assertions.assertTrue(listHighlight.boundsInRoot.height > 0f)
            val delete = flatten(listHighlight).single {
                it.config.contains(SemanticsActions.OnClick) && deleteTitle in descriptionCopy(it)
            }
            Assertions.assertTrue(requireNotNull(delete.config[SemanticsActions.OnClick].action).invoke())
            render(scene)
            click(scene, MR.strings.action_remove.localized())
            withTimeout(1_000) {
                repository.subscribeAll().first { repos -> repos.none { it.baseUrl == "https://first.example" } }
            }
            Assertions.assertEquals(listOf("https://second.example"), repository.getAll().map(ExtensionRepo::baseUrl))
            Assertions.assertEquals(
                listOf("GeneralSettingsScreen", "ExtensionRepoScreen", "AboutScreen"),
                DesktopSettingsCatalog.screens().drop(8).map { it.route::class.simpleName },
            )
        } finally {
            DesktopSettingsAnchorOwner.clear()
            scene.close()
        }
    }

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
            every { downloadQueuePort } returns downloadManager
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
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

    private suspend fun render(scene: ImageComposeScene) = repeat(12) {
        scene.render()
        kotlinx.coroutines.delay(16)
    }

    private fun highlighted(scene: ImageComposeScene) = nodes(scene, true).single {
        it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
    }

    private fun textCopy(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }

    private fun descriptionCopy(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.ContentDescription)) it.config[SemanticsProperties.ContentDescription] else emptyList()
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class EmptyScreen : Screen {
        @Composable
        override fun Content() = Unit
    }
}
