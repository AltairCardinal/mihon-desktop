package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionLoader
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.LoadedExtension
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.DesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.util.prefs.Preferences

class SourceMembershipReactiveWiringTest {

    @TempDir
    lateinit var extensionsDirectory: File

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `mounted browse list reacts to installed reloaded and uninstalled extension sources`() = runBlocking {
        val preferenceRoot = Preferences.userRoot().node("/mihon/source-membership/${System.nanoTime()}")
        val builtin = FakeSource(101, "en", "Builtin authority source")
        val installed = FakeSource(102, "en", "Installed authority source")
        val reloaded = FakeSource(installed.id, "en", "Reloaded authority source")
        val extensionJar = extensionsDirectory.resolve("reactive-source.jar").apply { createNewFile() }
        val loader = SnapshotLoader(extensionsDirectory)
        val extensionManager = DesktopExtensionManager(loader)
        val preferences = DesktopAppPreferences(DesktopPreferenceStore(preferenceRoot)).apply {
            enabledLanguages.set(setOf("en"))
        }
        val sourceManager = DesktopSourceManager(extensionManager, preferences, listOf(builtin))
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.sourceManager } returns sourceManager
            every { appPreferences } returns preferences
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(BrowseSourceListScreen()) { CurrentScreen() }
                }
            }
            awaitRendered(scene, builtin.name)
            assertTrue(rendered(scene).contains("Local source"))

            loader.snapshot = listOf(extensionJar to installed)
            extensionManager.reloadAll()
            awaitRendered(scene, installed.name)
            assertTrue(rendered(scene).contains(builtin.name))

            loader.snapshot = listOf(extensionJar to reloaded)
            extensionManager.reloadAll()
            awaitRendered(scene, reloaded.name)
            assertFalse(rendered(scene).contains(installed.name))

            val extension = extensionManager.installedExtensions.value.single()
            assertTrue(extensionManager.removeExtension(extension))
            awaitMissing(scene, reloaded.name)
            assertTrue(rendered(scene).contains(builtin.name))
            assertTrue(rendered(scene).contains("Local source"))
        } finally {
            scene.close()
            extensionManager.close()
            preferenceRoot.removeNode()
        }
    }

    private class SnapshotLoader(extensionsDirectory: File) : DesktopExtensionLoader(extensionsDirectory) {
        var snapshot = emptyList<Pair<File, FakeSource>>()

        override fun loadExtensions(): List<LoadedExtension> = snapshot.map { (jar, source) ->
            LoadedExtension(source, jar, ClassLoader.getPlatformClassLoader())
        }
    }

    private suspend fun awaitRendered(scene: ImageComposeScene, expected: String) = withTimeout(2_000) {
        while (!rendered(scene).contains(expected)) {
            scene.render()
            delay(10)
        }
    }

    private suspend fun awaitMissing(scene: ImageComposeScene, unexpected: String) = withTimeout(2_000) {
        while (rendered(scene).contains(unexpected)) {
            scene.render()
            delay(10)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun rendered(scene: ImageComposeScene): String = scene.semanticsOwners
        .flatMap { flatten(it.rootSemanticsNode) }
        .joinToString { it.config.toString() }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
