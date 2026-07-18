package mihon.desktop.ui.extension

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File

class ExtensionPresentationUiTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `production content renders loading empty data with failure and retries`() = runBlocking {
        val candidate = extension(
            "Update extension", "pkg.visible",
            listOf(source(1, "en", "English source"), source(2, "fr", "French source")),
        )
        val available = extension(
            "Available extension", "pkg.available",
            listOf(source(4, "en", "Alpha available source"), source(5, "fr", "Beta available source"), source(6, "es", "Filtered available source")),
        )
        val installed = InstalledExtension(
            File("pkg.visible.jar"),
            listOf(mockk { every { id } returns 3; every { name } returns "German source"; every { lang } returns "de" }),
            versionCode = 1, versionName = "1.5.0", displayName = candidate.name, language = "ja",
        )
        val failure = RepositoryCatalogFailure(RepositoryIdentity("https://failed", "Failed repository", "key"), AppError.Network())
        val partial = ExtensionCatalogResult(emptyList(), listOf(failure))
        val catalogs = Channel<ExtensionCatalogResult>(Channel.UNLIMITED)
        var refreshCalls = 0
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers { refreshCalls++; catalogs.receive() }
            every { availableExtensions(any()) } answers {
                if (firstArg<ExtensionCatalogResult>() === partial) listOf(candidate, available) else emptyList()
            }
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val installedFlow = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installedFlow), this, ExtensionPresentationOptions(false, setOf("en", "fr")),
        )
        val dependencies = mockk<DesktopUiDependencies> { every { extensionApi } returns api; every { extensionManager } returns manager }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previous = Injekt
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) { Navigator(ExtensionListScreen()) { CurrentScreen() } }
            }
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains(extensionListCopy().loading) })
            catalogs.send(ExtensionCatalogResult(emptyList(), emptyList()))
            withTimeout(5_000) { model.state.first { it.projection != null } }
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains(extensionListCopy().emptyInstalled) })
            installedFlow.value = listOf(installed)
            val partialRefresh = model.refresh()
            catalogs.send(partial)
            partialRefresh.join()
            scene.render()
            click(scene, extensionListCopy().available)
            scene.render()
            val rendered = nodes(scene).joinToString { it.config.toString() }
            assertSame(failure, model.state.value.projection?.failures?.single())
            listOf(candidate.name, "Alpha available source", "Beta available source", "Update All (1)", "Update", "Failed repository").forEach {
                assertTrue(rendered.contains(it), "missing production extension UI: $it")
            }
            click(scene, "Filter by language")
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains("French (fr)") })
            toggle(scene, 2)
            toggle(scene, 0)
            click(scene, "Apply")
            assertEquals(setOf("en", "es", "fr"), model.state.value.options.enabledLanguages)
            assertTrue(model.state.value.options.showNsfw)
            click(scene, "Filter by language")
            scene.render()
            click(scene, "Clear")
            assertEquals(setOf("de", "en", "es", "fr", "ja"), model.state.value.options.enabledLanguages)
            click(scene, MR.strings.action_retry.localized())
            catalogs.send(partial)
            scene.render()
            withTimeout(5_000) { while (refreshCalls < 3) yield() }
            assertEquals(3, refreshCalls)
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previous
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).single {
            it.config.contains(SemanticsActions.OnClick) &&
                (it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } ||
                    it.config.contains(SemanticsProperties.ContentDescription) && it.config[SemanticsProperties.ContentDescription].contains(label))
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun toggle(scene: ImageComposeScene, index: Int) {
        val node = nodes(scene).filter { it.config.contains(SemanticsProperties.ToggleableState) }[index]
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun source(id: Long, lang: String, name: String) = DesktopAvailableSource(id, lang, name, "https://$id")
    private fun extension(name: String, pkg: String, sources: List<DesktopAvailableSource>) = DesktopAvailableExtension(
        name, pkg, "1.4.0", 2, 1.5, "en", false, "https://repo/$pkg.jar", "", "https://repo", sources = sources,
    )
}
