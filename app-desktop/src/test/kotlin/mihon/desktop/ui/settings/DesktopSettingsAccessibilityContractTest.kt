package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.model.ThemeMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.network.DesktopConnectionTestResult
import mihon.desktop.platform.DesktopLocaleAdapter
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
class DesktopSettingsAccessibilityContractTest {
    @Test
    fun `More entries expose one labeled button action without decorative icon semantics`() = runBlocking {
        withScene(MoreRootScreen(), height = 2_000) { scene ->
            val labels = listOf(
                MR.strings.label_download_queue.localized(Locale.US),
                MR.strings.label_extensions.localized(Locale.US),
                MR.strings.label_migration.localized(Locale.US),
                MR.strings.label_stats.localized(Locale.US),
                MR.strings.label_settings.localized(Locale.US),
                MR.strings.pref_category_about.localized(Locale.US),
            )
            labels.forEach { label ->
                val entry = semanticBranch(scene, label, Role.Button)
                assertEquals(1, flatten(entry).count { it.config.contains(SemanticsActions.OnClick) }, label)
            }
            val removedSettings = listOf(
                MR.strings.action_search_settings.localized(Locale.US),
                MR.strings.pref_category_general.localized(Locale.US),
                MR.strings.pref_category_appearance.localized(Locale.US),
                MR.strings.pref_category_advanced.localized(Locale.US),
            )
            removedSettings.forEach { label ->
                assertTrue(nodes(scene, true).none { label in subtreeText(it) }, label)
            }
            assertTrue(
                nodes(scene, true).none {
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                        it.config[SemanticsProperties.ContentDescription].isNotEmpty()
                },
            )
        }
    }

    @Test
    fun `General and Appearance rows expose one action role state and disabled semantics`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            themeMode.set(ThemeMode.LIGHT)
        }
        withScene(GeneralSettingsScreen(), preferences = preferences) { scene ->
            val incognito = semanticBranch(
                scene,
                MR.strings.pref_incognito_mode.localized(Locale.US),
                Role.Switch,
            )
            assertEquals(1, flatten(incognito).count { it.config.contains(SemanticsActions.OnClick) })
            assertEquals(ToggleableState.Off, incognito.config[SemanticsProperties.ToggleableState])
            assertEquals(MR.strings.off.localized(Locale.US), incognito.config[SemanticsProperties.StateDescription])
            assertFalse(incognito.config.contains(SemanticsProperties.Disabled))
        }
        withScene(AppearanceSettingsScreen(), preferences = preferences, height = 2_000) { scene ->
            val light = semanticBranch(scene, MR.strings.theme_light.localized(Locale.US), Role.RadioButton)
            assertEquals(1, flatten(light).count { it.config.contains(SemanticsActions.OnClick) })
            assertTrue(light.config[SemanticsProperties.Selected])
            assertEquals(MR.strings.selected.localized(Locale.US), light.config[SemanticsProperties.StateDescription])

            val amoled = semanticBranch(
                scene,
                MR.strings.pref_dark_theme_pure_black.localized(Locale.US),
                Role.Switch,
            )
            assertEquals(ToggleableState.Off, amoled.config[SemanticsProperties.ToggleableState])
            assertTrue(amoled.config.contains(SemanticsProperties.Disabled))
            assertEquals(MR.strings.off.localized(Locale.US), amoled.config[SemanticsProperties.StateDescription])
            assertEquals(1, flatten(amoled).count { it.config.contains(SemanticsActions.OnClick) })
            assertEquals(1, nodes(scene, true).count { it.config.contains(SemanticsActions.SetProgress) })
        }
    }

    @Test
    fun `connection test immediately reports progress and restores an explicit result`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val pendingResult = CompletableDeferred<DesktopConnectionTestResult>()
        val network = mockk<DesktopNetworkHelper> {
            every { routeObservations } returns MutableStateFlow(emptyList())
            every { activeGlobalMode } returns preferences.globalNetworkMode.get()
            every { activeGlobalProxy } returns preferences.proxyRuntimeConfig()
            coEvery { testConnection(any(), null) } coAnswers { pendingResult.await() }
        }

        withScene(GeneralSettingsScreen(), preferences, network, height = 2_000) { scene ->
            click(scene, MR.strings.desktop_network_test.localized(Locale.US))
            withTimeout(5_000) {
                while (MR.strings.desktop_network_testing.localized(Locale.US) !in nodes(scene, true).flatMap(::subtreeText)) {
                    scene.render()
                    yield()
                }
            }

            pendingResult.complete(
                DesktopConnectionTestResult(
                    host = "example.org",
                    statusCode = null,
                    route = null,
                    error = "timed out",
                ),
            )
            val expected = MR.strings.desktop_network_test_failed.localized(Locale.US, "timed out")
            withTimeout(5_000) {
                while (expected !in nodes(scene, true).flatMap(::subtreeText)) {
                    scene.render()
                    yield()
                }
            }
            assertTrue(nodes(scene, true).flatMap(::subtreeText).contains(expected))
        }
    }

    private suspend fun withScene(
        screen: cafe.adriel.voyager.core.screen.Screen,
        preferences: DesktopAppPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
        suppliedNetwork: DesktopNetworkHelper? = null,
        height: Int = 1_000,
        block: suspend (ImageComposeScene) -> Unit,
    ) {
        val downloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val network = suppliedNetwork ?: mockk<DesktopNetworkHelper> {
            every { routeObservations } returns MutableStateFlow(emptyList())
            every { activeGlobalMode } returns preferences.globalNetworkMode.get()
            every { activeGlobalProxy } returns preferences.proxyRuntimeConfig()
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
            every { localeAdapter } returns DesktopLocaleAdapter(preferences.appLanguage)
            every { downloadManager } returns downloads
            every { downloadQueuePort } returns downloads
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
        }
        val previousLocale = Locale.getDefault()
        val scene = ImageComposeScene(1_000, height, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        try {
            Locale.setDefault(Locale.US)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme { Navigator(screen) { CurrentScreen() } }
                }
            }
            repeat(5) {
                scene.render()
                yield()
            }
            block(scene)
        } finally {
            Locale.setDefault(previousLocale)
            scene.close()
        }
    }

    private fun semanticBranch(scene: ImageComposeScene, label: String, role: Role): SemanticsNode =
        nodes(scene, true)
            .filter { it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == role }
            .single { label in subtreeText(it) }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene, true).single {
            it.config.contains(SemanticsActions.OnClick) && label in subtreeText(it)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun subtreeText(node: SemanticsNode): List<String> = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean) =
        scene.semanticsOwners.flatMap { flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
