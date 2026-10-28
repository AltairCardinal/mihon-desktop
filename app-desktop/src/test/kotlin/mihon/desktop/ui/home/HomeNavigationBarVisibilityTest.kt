package mihon.desktop.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.library.ProvideLibraryNavigationHost
import mihon.desktop.ui.library.VoyagerLibraryNavigationHost
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.theme.DesktopTheme
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

/**
 * The root navigation bar must follow the visible page in the very same frame.
 *
 * Android keeps the bar inside the home scaffold, so the bar and the page swap atomically when a
 * detail page is closed. The desktop hides the bar while a tab owns a child page, and the bar may
 * only disappear or come back together with that page.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HomeNavigationBarVisibilityTest {

    @Test
    fun `root navigation bar returns in the same frame as the restored page`(@TempDir root: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/nav-bar-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        val scene = ImageComposeScene(420, 800, coroutineContext = coroutineContext) {}
        lateinit var navigator: Navigator
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    MaterialTheme {
                        HomeNavigationHost(
                            current = MoreTab,
                            onSelect = {},
                            showNavigation = true,
                            badgeCount = 0,
                            tabs = listOf(MoreTab),
                        ) {
                            Navigator(RootPage()) { nav ->
                                navigator = nav
                                ObserveHomeNavigationStack(nav)
                                CurrentScreen()
                            }
                        }
                    }
                }
            }

            scene.render()
            assertTrue(rootBarVisible(scene), "the compact layout must show the root navigation bar")

            navigator.push(ChildPage())
            scene.render()
            assertFalse(rootBarVisible(scene), "a child page must hide the root navigation bar")

            navigator.pop()
            scene.render()
            assertTrue(
                rootBarVisible(scene),
                "returning to the root page must restore the root navigation bar in the same frame",
            )
        } finally {
            scene.close()
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `manga detail return restores the root navigation bar in the same frame`(@TempDir root: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/nav-bar-detail-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        val scene = ImageComposeScene(420, 800, coroutineContext = coroutineContext) {}
        try {
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 0L,
                        url = "/nav-bar",
                        title = "Nav work",
                        initialized = true,
                    ),
                ),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, emptyList())))
            val dependencies = DesktopUiDependencies.fromInjekt()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryNavigationHost(VoyagerLibraryNavigationHost()) {
                        DesktopTheme { Navigator(HomeScreen()) { CurrentScreen() } }
                    }
                }
            }

            withTimeout(30_000) {
                while (!hasText(scene, manga.title)) {
                    scene.render()
                    yield()
                }
            }
            assertTrue(rootBarVisible(scene), "the library page must show the root navigation bar")

            clickContaining(scene, manga.title, description = false)
            scene.render()
            assertFalse(
                rootBarVisible(scene),
                "the manga detail must hide the root navigation bar in the same frame",
            )

            withTimeout(30_000) {
                while (!hasDescription(scene, MR.strings.action_bar_up_description.localized())) {
                    scene.render()
                    yield()
                }
            }
            clickContaining(scene, MR.strings.action_bar_up_description.localized(), description = true)
            scene.render()
            assertTrue(
                rootBarVisible(scene),
                "returning from the manga detail must restore the root navigation bar in the same frame",
            )
        } finally {
            scene.close()
            context.closeAndJoin()
            node.removeNode()
        }
    }

    private fun rootBarVisible(scene: ImageComposeScene): Boolean =
        nodes(scene).any { it.tag("desktop-root-bar") }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun SemanticsNode.tag(value: String) =
        config.contains(SemanticsProperties.TestTag) && config[SemanticsProperties.TestTag] == value

    private fun SemanticsNode.texts(): List<String> =
        if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }

    private fun SemanticsNode.descriptions(): List<String> =
        if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }

    /** Clicks the control whose own subtree carries [label], the way a user hits the title. */
    private fun clickContaining(scene: ImageComposeScene, label: String, description: Boolean) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(SemanticsActions.OnClick) &&
                flatten(candidate).any { child ->
                    if (description) label in child.descriptions() else label in child.texts()
                }
        }
        check(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun hasText(scene: ImageComposeScene, label: String): Boolean =
        nodes(scene).any { label in it.texts() }

    private fun hasDescription(scene: ImageComposeScene, label: String): Boolean =
        nodes(scene).any { label in it.descriptions() }

    private class RootPage : Screen {
        @Composable
        override fun Content() {}
    }

    private class ChildPage : Screen {
        @Composable
        override fun Content() {}
    }
}
