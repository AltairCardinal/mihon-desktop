package mihon.desktop.extension

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.DesktopSourceManager
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

class ExtensionIncognitoPreferenceWiringTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `extension details disables current extension from latest incognito preferences`() = runBlocking {
        verifyToggle(
            initialExtensions = setOf("extension.hidden", "extension.other"),
            expectedExtensions = setOf("extension.other", "extension.concurrent"),
        )
    }

    @Test
    fun `extension details enables current extension from latest incognito preferences`() = runBlocking {
        verifyToggle(
            initialExtensions = setOf("extension.other"),
            expectedExtensions = setOf("extension.other", "extension.concurrent", "extension.hidden"),
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun CoroutineScope.verifyToggle(
        initialExtensions: Set<String>,
        expectedExtensions: Set<String>,
    ) {
        val jar = tempDir.resolve("extension.hidden.jar").also { it.createNewFile() }
        val manager = DesktopExtensionManager(
            object : DesktopExtensionLoader(tempDir) {
                override fun loadExtensions() = listOf(LoadedExtension(StubSource, jar, javaClass.classLoader))
            },
        ).also { it.loadAll() }
        val preferences = DesktopAppPreferences(
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        ).apply {
            incognitoExtensions.set(initialExtensions)
        }
        val api = mockk<DesktopExtensionApi> {
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionManager } returns manager
            every { appPreferences } returns preferences
            every { sourceManager } returns DesktopSourceManager(manager, preferences, emptyList())
            every { extensionApi } returns api
            every { networkHelper } returns mockk<DesktopNetworkHelper>(relaxed = true)
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}

        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(ExtensionDetailsScreen(jar.absolutePath)) { CurrentScreen() }
            }
        }
        scene.render()

        val toggle = nodes(scene).single {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.toString().contains("Incognito mode for extension.hidden")
        }
        assertFalse(preferences.incognitoMode.get())
        assertEquals(initialExtensions, preferences.incognitoExtensions.get())

        preferences.incognitoExtensions.set(
            preferences.incognitoExtensions.get() + "extension.concurrent",
        )
        assertTrue(requireNotNull(toggle.config[SemanticsActions.OnClick].action).invoke())
        scene.render()

        assertFalse(preferences.incognitoMode.get())
        assertEquals(expectedExtensions, preferences.incognitoExtensions.get())
        scene.close()
        manager.close()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private object StubSource : Source {
        override val id = 42L
        override val name = "Hidden source"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }
}
