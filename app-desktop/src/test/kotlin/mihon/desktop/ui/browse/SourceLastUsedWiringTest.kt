package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.util.prefs.Preferences
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class)
class SourceLastUsedWiringTest {

    @Test
    fun `real navigation records last used outside incognito and the same mounted list reorders reactively`() = runBlocking {
        val root = Preferences.userRoot().node("/mihon/source-last-used/${System.nanoTime()}")
        val store = DesktopPreferenceStore(root)
        val preferences = DesktopAppPreferences(store).apply { enabledLanguages.set(setOf("en")) }
        val lastUsed = store.getLong(Preference.appStateKey("last_catalogue_source"), -1L)
        val alpha = FakeSource(201, "en", "Alpha projection source")
        val zeta = FakeSource(202, "en", "Zeta projection source")
        val scene = mountedScene(preferences, listOf(alpha, zeta), coroutineContext)

        try {
            assertEquals(-1L, lastUsed.get())
            awaitRows(scene, listOf(alpha.name, zeta.name))

            clickSource(scene, zeta.name)
            awaitPreference(scene, lastUsed, zeta.id)
            click(scene, "Back")
            awaitRows(scene, listOf(zeta.name, alpha.name, zeta.name))
            assertTrue(rendered(scene).contains(MR.strings.last_used_source.localized()))

            lastUsed.set(alpha.id)
            awaitRows(scene, listOf(alpha.name, alpha.name, zeta.name))
        } finally {
            scene.close()
            root.removeNode()
        }
    }

    @Test
    fun `real navigation does not replace last used while global incognito is enabled`() = runBlocking {
        val root = Preferences.userRoot().node("/mihon/source-last-used-incognito/${System.nanoTime()}")
        val store = DesktopPreferenceStore(root)
        val preferences = DesktopAppPreferences(store).apply {
            enabledLanguages.set(setOf("en"))
            incognitoMode.set(true)
        }
        val lastUsed = store.getLong(Preference.appStateKey("last_catalogue_source"), -1L).apply { set(301L) }
        val source = FakeSource(302, "en", "Incognito projection source")
        val scene = mountedScene(preferences, listOf(source), coroutineContext)

        try {
            clickSource(scene, source.name)
            withTimeout(2_000) {
                while (!rendered(scene).contains(source.name)) {
                    scene.render()
                    delay(10)
                }
            }
            repeat(5) {
                scene.render()
                delay(10)
            }
            assertEquals(301L, lastUsed.get())
        } finally {
            scene.close()
            root.removeNode()
        }
    }

    private fun mountedScene(
        preferences: DesktopAppPreferences,
        sources: List<FakeSource>,
        coroutineContext: CoroutineContext,
    ): ImageComposeScene {
        val sourceManager = FakeDesktopSourceManager(sources)
        val saver = mockk<SaveSourceMangaForDetails> {
            coEvery { awaitSearchResults(any(), any()) } returns emptyList()
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.sourceManager } returns sourceManager
            every { appPreferences } returns preferences
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns saver
            every { getManga } returns mockk<GetManga> {
                every { subscribe(any<String>(), any<Long>()) } returns flowOf(null)
            }
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        return ImageComposeScene(900, 1_200, coroutineContext = coroutineContext) {}.also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(BrowseSourceListScreen()) { CurrentScreen() }
                }
            }
            scene.render()
        }
    }

    private suspend fun awaitPreference(
        scene: ImageComposeScene,
        preference: tachiyomi.core.common.preference.Preference<Long>,
        expected: Long,
    ) = withTimeout(2_000) {
        while (preference.get() != expected) {
            scene.render()
            delay(10)
        }
    }

    private suspend fun awaitRows(scene: ImageComposeScene, expected: List<String>) = withTimeout(2_000) {
        while (sourceRows(scene, expected.toSet()) != expected) {
            scene.render()
            delay(10)
        }
    }

    private fun clickSource(scene: ImageComposeScene, name: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnLongClick) && it.config.toString().contains(name)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.toString().contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun sourceRows(scene: ImageComposeScene, names: Set<String>): List<String> = nodes(scene)
        .filter { it.config.contains(SemanticsActions.OnLongClick) }
        .mapNotNull { node -> names.firstOrNull { node.config.toString().contains(it) } }

    private fun rendered(scene: ImageComposeScene): String = nodes(scene).joinToString { it.config.toString() }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.rootSemanticsNode)
    }
}
