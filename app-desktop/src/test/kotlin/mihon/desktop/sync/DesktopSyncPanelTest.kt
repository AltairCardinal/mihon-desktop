package mihon.desktop.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.ui.library.LibraryRootScreen
import mihon.desktop.ui.library.LibraryScreenModel
import mihon.desktop.ui.library.ProvideLibraryScreenModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSyncPanelTest {
    @Test
    fun `actual library root exposes the sync sheet and nested settings without changing navigator`() = runBlocking {
        val panel = TestPanel()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { syncPanel } returns panel }
        val uriHandler = mockk<UriHandler> {
            every { openUri(any()) } throws IllegalStateException("no system browser")
        }
        every { dependencies.shareService.copyText(any()) } returns
            mihon.desktop.platform.DesktopShareResult.Failed(
                mihon.desktop.platform.DesktopShareFailureReason.CLIPBOARD_BUSY,
            )
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(FakeCategoryRepository()),
        )
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}
        scene.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalUriHandler provides uriHandler,
                ) {
                    ProvideLibraryScreenModelFactory({ model }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
        }
        suspend fun render() {
            repeat(4) {
                scene.render()
                yield()
            }
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun find(tag: String) = nodes().firstOrNull {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }
        suspend fun click(tag: String) {
            withTimeout(2_000) { while (find(tag) == null) render() }
            assertTrue(requireNotNull(find(tag)!!.config[SemanticsActions.OnClick].action).invoke())
            render()
        }
        try {
            click("sync-open")
            assertTrue(panel.state.value.visible)
            click("sync-settings")
            assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
            click("sync-back")
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            panel.state.value = panel.state.value.copy(
                page = SyncPanelPage.SETUP,
                deviceCode = mihon.domain.sync.auth.GitHubDeviceCode(
                    "secret",
                    "ABCD-EFGH",
                    "https://github.com/login/device",
                    600,
                    5,
                ),
            )
            click("sync-copy-open")
            verify(exactly = 2) { dependencies.notificationService.post(any()) }
            panel.state.value = panel.state.value.copy(setupStep = SyncSetupStep.NEW_PASSWORD, deviceCode = null)
            withTimeout(2_000) { while (find("sync-password-input") == null) render() }
            requireNotNull(find("sync-password-input")!!.config[SemanticsActions.RequestFocus].action).invoke()
            render()
            val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods
                .single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
            val native = factory.invoke(null, Key.Escape.keyCode, eventType, 0, false, false, false, false, null)
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
            render()
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            click("sync-close")
            assertFalse(panel.state.value.visible)
        } finally {
            scene.close()
        }
    }

    private class TestPanel : SyncPanel {
        override val state = MutableStateFlow(SyncPanelState(loaded = true))
        val actions = mutableListOf<SyncPanelAction>()
        override fun dispatch(action: SyncPanelAction) {
            actions += action
            state.value = when (action) {
                SyncPanelAction.Open -> state.value.copy(visible = true)
                SyncPanelAction.Close -> state.value.copy(visible = false)
                is SyncPanelAction.Navigate -> state.value.copy(page = action.page)
                SyncPanelAction.Back -> state.value.copy(page = SyncPanelPage.MAIN)
                else -> state.value
            }
        }
    }
}
