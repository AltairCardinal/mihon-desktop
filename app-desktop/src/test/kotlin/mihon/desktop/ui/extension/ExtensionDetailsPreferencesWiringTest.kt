package mihon.desktop.ui.extension

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

class ExtensionDetailsPreferencesWiringTest {
    @TempDir
    lateinit var tempDir: File

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `details waits for authoritative removal and keeps desktop capabilities`() = runBlocking {
        val jar = tempDir.resolve("pkg.details.jar").also { it.createNewFile() }
        val installed = InstalledExtension(
            jarFile = jar,
            sources = emptyList(),
            displayName = "Details extension",
            versionName = "1.0.0",
        )
        val installedFlow = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers {
                refreshEntered.complete(Unit)
                releaseRefresh.await()
                catalog
            }
            every { availableExtensions(catalog) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager> {
            every { removeExtensionWithMeta(installed) } returnsMany listOf(false, true)
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installedFlow),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val preferences = DesktopAppPreferences(
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionApi } returns api
            every { extensionManager } returns manager
            every { appPreferences } returns preferences
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previous = Injekt
        var navigator: Navigator? = null
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(DetailsRoot) { current ->
                        navigator = current
                        LaunchedEffect(Unit) { current.push(ExtensionDetailsScreen(jar.absolutePath)) }
                        CurrentScreen()
                    }
                }
            }
            renderUntil(scene) { refreshEntered.isCompleted }
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            assertTrue(nodes(scene).any { it.config.toString().contains(MR.strings.loading.localized()) })
            releaseRefresh.complete(Unit)
            renderUntil(scene) { navigator?.lastItem === DetailsRoot }
            installedFlow.value = listOf(installed)
            renderUntil(scene) { model.state.value.projection?.installed?.singleOrNull()?.installed === installed }
            navigator?.push(ExtensionDetailsScreen(jar.absolutePath))
            renderUntil(scene) {
                navigator?.lastItem is ExtensionDetailsScreen &&
                    nodes(scene).any { it.config.toString().contains("Details extension") }
            }
            val rendered = nodes(scene).joinToString { it.config.toString() }
            listOf("Details extension", jar.absolutePath, "SHA-256", "Repository fingerprint", "Open folder", "Incognito mode", "Clear extension cookies")
                .forEach { assertTrue(rendered.contains(it), "missing Desktop capability: $it") }

            uninstall(scene)
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            renderUntil(scene) {
                nodes(scene).any { it.config.toString().contains(MR.strings.desktop_extension_uninstall_failed.localized()) }
            }

            uninstall(scene)
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            installedFlow.value = emptyList()
            renderUntil(scene) { navigator?.lastItem === DetailsRoot }
            verify(exactly = 2) { manager.removeExtensionWithMeta(installed) }
            assertSame(DetailsRoot, navigator?.lastItem)
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previous
        }
    }

    private suspend fun uninstall(scene: ImageComposeScene) {
        click(scene, MR.strings.ext_uninstall.localized())
        scene.render()
        click(scene, MR.strings.ext_uninstall.localized(), last = true)
        scene.render()
    }

    private suspend fun renderUntil(scene: ImageComposeScene, condition: () -> Boolean) {
        repeat(50) {
            scene.render()
            if (condition()) return
            yield()
        }
        assertTrue(condition())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun click(scene: ImageComposeScene, label: String, last: Boolean = false) {
        val matches = nodes(scene).filter {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.contains(SemanticsProperties.Text) &&
                it.config[SemanticsProperties.Text].any { text -> text.text == label }
        }
        val node = if (last) matches.last() else matches.first()
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data object DetailsRoot : Screen {
        @Composable
        override fun Content() = Text("Details root")
    }
}
