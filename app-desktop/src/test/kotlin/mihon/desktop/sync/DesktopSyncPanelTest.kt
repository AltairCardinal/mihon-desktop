package mihon.desktop.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.isShiftPressed
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
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPasswordHelpSource
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncFailureLogStatus
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunPhase
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.auth.GitHubDeviceCode
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
    fun `read only sync observer receives actual layout focus and unmount events`() = runBlocking {
        val entries = mutableMapOf<Any, mihon.presentation.sync.SyncUiControl>()
        val observer = object : mihon.presentation.sync.SyncUiObserver {
            override fun observes(tag: String) = tag in setOf("sync-open", "sync-close", "sync-settings", "sync-now", "sync-history", "sync-drag-handle")
            override fun update(token: Any, control: mihon.presentation.sync.SyncUiControl) { entries[token] = control }
            override fun remove(token: Any) { entries.remove(token) }
        }
        val panel = TestPanel()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { syncPanel } returns panel }
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}
        scene.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies,
                    mihon.presentation.sync.LocalSyncUiObserver provides observer) {
                    DesktopLibrarySyncAction()
                }
            }
        }
        suspend fun render() { repeat(4) { scene.render(); yield() } }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun node(tag: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
        }
        try {
            render()
            assertEquals(1, entries.values.count { it.tag == "sync-open" })
            assertTrue(entries.values.single().width > 0)
            node("sync-open").config[SemanticsActions.RequestFocus].action!!.invoke()
            render()
            assertTrue(entries.values.single().focused)
            node("sync-open").config[SemanticsActions.OnClick].action!!.invoke()
            render()
            assertTrue(entries.values.any { it.tag == "sync-close" })
            node("sync-close").config[SemanticsActions.RequestFocus].action!!.invoke()
            render()
            assertTrue(entries.values.single { it.tag == "sync-close" }.focused)
            val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").methods.single {
                it.name.startsWith("KeyEvent") && it.parameterCount == 8
            }
            val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType").getMethod("access\$getKeyDown\$cp").invoke(null)
            val tab = androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, Key.Tab.keyCode, eventType, 0, false, false, false, false, null))
            val seen = mutableSetOf<String>()
            repeat(8) {
                val owner = scene.semanticsOwners.map { flatten(it.unmergedRootSemanticsNode) }.single { nodes ->
                    nodes.any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "sync-close" }
                }
                val focused = owner.single { it.config.getOrElse(SemanticsProperties.Focused) { false } }
                val tag = flatten(focused).firstNotNullOfOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.takeIf { tag -> tag.isNotEmpty() } }.orEmpty()
                assertTrue(tag in entries.values.map { it.tag }, "Focused MAIN control is not observable: $tag, visited=$seen, parentTags=${generateSequence(focused.parent) { it.parent }.map { it.config.getOrElse(SemanticsProperties.TestTag) { "" } }.toList()}, bounds=${focused.boundsInRoot}")
                val observed = entries.values.single { it.tag == tag }
                if (tag == "sync-drag-handle") {
                    assertTrue(focused.boundsInRoot.contains(flatten(focused).first { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }.boundsInRoot.center))
                } else assertTrue(observed.focused)
                seen += tag
                scene.sendKeyEvent(tab)
                render()
            }
            assertTrue(seen.size >= 3)
            node("sync-close").config[SemanticsActions.RequestFocus].action!!.invoke()
            render()
            node("sync-close").config[SemanticsActions.OnClick].action!!.invoke()
            render()
            assertFalse(entries.values.any { it.tag == "sync-close" })
            assertTrue(entries.values.single { it.tag == "sync-open" }.focused)
        } finally { scene.close() }
        assertTrue(entries.isEmpty())
    }

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
            withTimeout(2_000) { while (find("sync-open") == null) render() }
            requireNotNull(find("sync-open")!!.config[SemanticsActions.RequestFocus].action).invoke()
            render()
            assertTrue(find("sync-open")!!.config[SemanticsProperties.Focused])
            click("sync-open")
            assertTrue(panel.state.value.visible)
            click("sync-settings")
            assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
            click("sync-back")
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            panel.state.value = panel.state.value.copy(
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.SIGN_IN,
                setupBusy = true,
                authRequestStartedAtMillis = 1_000,
                nowMillis = 1_000,
            )
            render()
            assertTrue(find("sync-auth-getting-code") != null)
            assertTrue(find("sync-auth-network-hint") == null)
            panel.state.value = panel.state.value.copy(nowMillis = 6_000)
            render()
            assertTrue(find("sync-auth-network-hint") != null)
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
            render()
            assertTrue(find("sync-auth-waiting-browser") != null)
            click("sync-copy-open")
            verify(exactly = 2) { uriHandler.openUri("https://github.com/login/device") }
            verify(exactly = 4) { dependencies.notificationService.post(any()) }
            panel.state.value = panel.state.value.copy(
                setupStep = SyncSetupStep.NEW_PASSWORD,
                setupBusy = false,
                deviceCode = null,
            )
            click("sync-password-enabled")
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
            panel.state.value = panel.state.value.copy(page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.UNLOCK)
            render()
            click("sync-password-help")
            assertEquals(SyncPanelPage.PASSWORD_HELP, panel.state.value.page)
            click("sync-back")
            assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
            assertTrue(find("sync-password-help")!!.config[SemanticsProperties.Focused])
            click("sync-password-help")
            requireNotNull(find("sync-back")!!.config[SemanticsActions.RequestFocus].action).invoke()
            render()
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
            render()
            assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
            assertTrue(panel.state.value.visible)
            val tabNative = factory.invoke(null, Key.Tab.keyCode, eventType, 0, false, false, false, false, null)
            val reverseTabNative = factory.invoke(null, Key.Tab.keyCode, eventType, 0, false, false, false, true, null)
            val reverseTab = androidx.compose.ui.input.key.KeyEvent(reverseTabNative)
            assertTrue(reverseTab.isShiftPressed)
            fun modalFocusedId(): Int {
                val modal = scene.semanticsOwners.map { flatten(it.rootSemanticsNode) }.single { owner ->
                    owner.any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "sync-close" }
                }
                return modal.single { it.config.getOrElse(SemanticsProperties.Focused) { false } }.id
            }
            suspend fun tabCycle(keyEvent: androidx.compose.ui.input.key.KeyEvent): List<Int> {
                requireNotNull(find("sync-close")!!.config[SemanticsActions.RequestFocus].action).invoke()
                render()
                val first = modalFocusedId()
                val visited = mutableListOf(first)
                repeat(20) {
                    scene.sendKeyEvent(keyEvent)
                    render()
                    val focused = modalFocusedId()
                    visited += focused
                    if (focused == first) {
                        assertTrue(visited.dropLast(1).distinct().size >= 4, "Tab must traverse the panel controls")
                        assertEquals(visited.dropLast(1).size, visited.dropLast(1).distinct().size)
                        return visited
                    }
                }
                error("Tab must cycle back to its first panel control")
            }
            val forward = tabCycle(androidx.compose.ui.input.key.KeyEvent(tabNative))
            val backward = tabCycle(reverseTab)
            assertEquals(forward.drop(1).dropLast(1).reversed(), backward.drop(1).dropLast(1))
            val opensBeforeKeyboard = panel.actions.count { it == SyncPanelAction.Open }
            val keyUpType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyUp\$cp").invoke(null)
            for (key in listOf(Key.Enter, Key.Spacebar)) {
                requireNotNull(find("sync-password-help")!!.config[SemanticsActions.RequestFocus].action).invoke()
                render()
                for (type in listOf(eventType, keyUpType)) {
                    val press = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
                    scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(press))
                    render()
                }
                assertEquals(SyncPanelPage.PASSWORD_HELP, panel.state.value.page)
                assertEquals(opensBeforeKeyboard, panel.actions.count { it == SyncPanelAction.Open })
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
                render()
                assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
            }
            click("sync-back")
            mockkObject(DesktopSyncFailureLogOpener)
            try {
                every { DesktopSyncFailureLogOpener.open(any(), any()) } returns true
                panel.state.value = panel.state.value.copy(
                    run = SyncRunSnapshot(
                        "report-run", "space", 1, SyncTrigger.MANUAL, SyncRunState.PARTIAL, SyncRunPhase.MERGING,
                        0, 1, 0, 0, 1, attemptId = 1, nextRetryAt = 0, lastProgressAt = 0,
                        stopReason = "projection_pending", ownerSession = null, createdAt = 0, updatedAt = 1,
                    ),
                    failureLog = SyncFailureLogStatus.Ready("report-run", "/reports/failure.txt", 1),
                )
                render()
                click("sync-failure-log-open")
                verify(exactly = 1) { DesktopSyncFailureLogOpener.open("/reports/failure.txt", any()) }
            } finally {
                unmockkObject(DesktopSyncFailureLogOpener)
            }
            click("sync-close")
            assertFalse(panel.state.value.visible)
            assertTrue(find("sync-open")!!.config[SemanticsProperties.Focused])
        } finally {
            scene.close()
        }
    }

    private class TestPanel : SyncPanel {
        override val state = MutableStateFlow(SyncPanelState(loaded = true))
        private val openedDeviceCodes = mutableSetOf<String>()
        override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean = openedDeviceCodes.add(code.deviceCode)
        val actions = mutableListOf<SyncPanelAction>()
        override fun dispatch(action: SyncPanelAction) {
            actions += action
            state.value = when (action) {
                SyncPanelAction.Open -> state.value.copy(visible = true)
                SyncPanelAction.Close -> state.value.copy(visible = false)
                is SyncPanelAction.Navigate -> state.value.copy(page = action.page)
                SyncPanelAction.ShowPasswordHelp -> state.value.copy(page = SyncPanelPage.PASSWORD_HELP, passwordHelpSource = SyncPasswordHelpSource.UNLOCK)
                SyncPanelAction.Back -> if (state.value.page == SyncPanelPage.PASSWORD_HELP) {
                    state.value.copy(page = SyncPanelPage.SETUP, passwordHelpReturn = state.value.passwordHelpReturn + 1)
                } else state.value.copy(page = SyncPanelPage.MAIN)
                else -> state.value
            }
        }
    }
}
