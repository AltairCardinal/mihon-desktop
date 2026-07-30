package mihon.desktop.extension

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.DesktopSourceManager
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale
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
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, manager.installedExtensions),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        model.refresh().join()
        val network = mockk<DesktopNetworkHelper>(relaxed = true)
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionManager } returns manager
            every { appPreferences } returns preferences
            every { sourceManager } returns DesktopSourceManager(manager, preferences, emptyList())
            every { extensionApi } returns api
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previous = Injekt
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(ExtensionDetailsScreen(jar.absolutePath)) { CurrentScreen() }
                }
            }
            val description = MR.strings.desktop_extension_incognito_for.localized(Locale.getDefault(), "extension.hidden")
            val toggle = withTimeout(5_000) {
                var match: SemanticsNode?
                do {
                    scene.render()
                    match = nodes(scene).singleOrNull {
                        it.config.contains(SemanticsActions.OnClick) &&
                            it.config.contains(SemanticsProperties.ContentDescription) &&
                            it.config[SemanticsProperties.ContentDescription].contains(description)
                    }
                    if (match == null) yield()
                } while (match == null)
                requireNotNull(match)
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
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previous
            manager.close()
        }
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
