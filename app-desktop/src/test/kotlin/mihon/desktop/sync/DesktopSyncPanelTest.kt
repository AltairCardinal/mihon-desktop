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
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
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
            panel.state.value = panel.state.value.copy(
                run = SyncRunSnapshot(
                    "compact-run", "space", 1, SyncTrigger.MANUAL, SyncRunState.RUNNING, SyncRunPhase.UPLOADING,
                    6, 100, 0, 0, 0, attemptId = 1, nextRetryAt = 0, lastProgressAt = 1000,
                    stopReason = null, ownerSession = "fixture", createdAt = 1000, updatedAt = 1000,
                    confirmedItems = 4, plannedItems = 10,
                ),
            )
            render()
            assertEquals(0.4f, find("sync-progress-track")!!.config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertTrue(find("sync-progress-status")!!.config[SemanticsProperties.Text].single().text.contains("4/10"))
            assertTrue(find("sync-round-time") != null)
            assertTrue(find("sync-progress-details-toggle") == null)
            assertTrue(find("sync-pending-list") != null)
            panel.state.value = panel.state.value.copy(page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.MERGING)
            render()
            assertEquals(0.4f, find("sync-progress-track")!!.config[SemanticsProperties.ProgressBarRangeInfo].current)
            click("sync-back")
            panel.state.value = panel.state.value.copy(
                connection = mihon.data.sync.runtime.SyncConnection(
                    "space", 1, mihon.domain.sync.transport.SyncRepository("owner", "repo", "sync"), false,
                ),
                run = SyncRunSnapshot(
                    "cancelled-run", "space", 1, SyncTrigger.MANUAL, SyncRunState.CANCELLED, SyncRunPhase.COMPLETE,
                    0, 0, 0, 0, 0, attemptId = 1, nextRetryAt = 0, lastProgressAt = 1000,
                    stopReason = "user", ownerSession = null, createdAt = 1000, updatedAt = 17_515_000,
                    confirmedItems = 1536,
                ),
            )
            render()
            click("sync-now")
            assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
            assertFalse(panel.state.value.connection!!.enabled)
            assertEquals(1536L, panel.state.value.run!!.confirmedItems)
            assertTrue(panel.actions.contains(SyncPanelAction.BeginSetup))
            assertFalse(panel.actions.contains(SyncPanelAction.Synchronize))
            click("sync-back")
            panel.state.value = panel.state.value.copy(connection = null, run = null)
            render()
            click("sync-settings")
            assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
            requireNotNull(find("sync-settings-list")!!.config[SemanticsActions.ScrollToIndex].action).invoke(2)
            render()
            click("sync-settings-diagnostics")
            click("sync-diagnostic-capture")
            requireNotNull(find("sync-diagnostic-capture")!!.config[SemanticsActions.RequestFocus].action).invoke()
            render()
            val escapeType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val escapeFactory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods
                .single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(
                escapeFactory.invoke(null, Key.Escape.keyCode, escapeType, 0, false, false, false, false, null)))
            render()
            assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
            requireNotNull(find("sync-settings-list")!!.config[SemanticsActions.ScrollToIndex].action).invoke(2)
            render()
            assertTrue(find("sync-settings-diagnostics") != null)
            assertTrue(panel.actions.contains(SyncPanelAction.CaptureDiagnostics))
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
            mockkObject(DesktopSyncDiagnosticOpener)
            try {
                every { DesktopSyncDiagnosticOpener.open(any(), any(), any()) } returns true
                panel.state.value = panel.state.value.copy(visible = true, page = SyncPanelPage.DIAGNOSTICS,
                    diagnosticSnapshot = mihon.data.sync.runtime.SyncDiagnosticSnapshot(
                        status = mihon.data.sync.runtime.SyncDiagnosticStatus.OK),
                    diagnosticPath = "sync-diagnostic-fixture.json")
                render()
                click("sync-diagnostic-details-toggle")
                val list = find("sync-diagnostics-list")!!
                requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(6)
                render()
                click("sync-diagnostic-open")
                verify(exactly = 1) { DesktopSyncDiagnosticOpener.open("sync-diagnostic-fixture.json", any(), any()) }
            } finally {
                unmockkObject(DesktopSyncDiagnosticOpener)
            }
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
                SyncPanelAction.Back -> state.value.copy(page = if (state.value.page == SyncPanelPage.DIAGNOSTICS)
                    SyncPanelPage.SETTINGS else SyncPanelPage.MAIN)
                SyncPanelAction.BeginSetup -> state.value.copy(page = SyncPanelPage.SETUP)
                else -> state.value
            }
        }
    }
}
