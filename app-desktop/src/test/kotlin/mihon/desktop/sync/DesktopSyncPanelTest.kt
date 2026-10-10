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
        val registry = mihon.desktop.test.SyncUiRegistry().also { it.start() }
        val registeredObserver = registry.observer()
        val observer = object : mihon.presentation.sync.SyncUiObserver {
            override fun observes(tag: String) = registeredObserver.observes(tag)
            override fun update(token: Any, control: mihon.presentation.sync.SyncUiControl) {
                registeredObserver.update(token, control)
                entries[token] = control
            }
            override fun remove(token: Any) {
                registeredObserver.remove(token)
                entries.remove(token)
            }
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
            assertTrue("sync-progress-details-toggle" in seen)
            node("sync-close").config[SemanticsActions.RequestFocus].action!!.invoke()
            render()
            node("sync-close").config[SemanticsActions.OnClick].action!!.invoke()
            render()
            assertFalse(entries.values.any { it.tag == "sync-close" })
            assertTrue(entries.values.single { it.tag == "sync-open" }.focused)
        } finally { scene.close(); registry.stop() }
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `native compatibility page checks source availability and cancelled chooser keeps the task unresolved`() = runBlocking {
        val panel = TestPanel()
        panel.state.value = SyncPanelState(visible = true, loaded = true, page = SyncPanelPage.RECOVERY,
            recoveryPlatformRequest = mihon.data.sync.runtime.SyncRecoveryPlatformRequest("local-update", mihon.data.sync.runtime.SyncRecoveryPlatformAction.UPDATE),
            recoveryPlatformLaunchPending = true)
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { syncPanel } returns panel }
        val legacy = mockk<java.util.prefs.Preferences>(relaxed = true) { every { get(any(), any()) } returns null }
        every { dependencies.appPreferences } returns mihon.desktop.settings.DesktopAppPreferences(
            tachiyomi.core.common.preference.InMemoryPreferenceStore(), legacy)
        io.mockk.coEvery { dependencies.filePicker.choose(any()) } returns mihon.desktop.platform.DesktopFilePickerResult.Cancelled
        val model = LibraryScreenModel(GetLibraryManga(FakeMangaRepository()), GetCategories(FakeCategoryRepository()))
        val scene = ImageComposeScene(1200, 900, coroutineContext = coroutineContext) {}
        scene.setContent { MaterialTheme { CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
            ProvideLibraryScreenModelFactory({ model }) { Navigator(LibraryRootScreen()) { CurrentScreen() } }
        } } }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun node(tag: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }
        suspend fun click(tag: String) {
            withTimeout(5000) { while (node(tag) == null) { scene.render(); yield() } }
            assertTrue(requireNotNull(node(tag)!!.config[SemanticsActions.OnClick].action).invoke())
            repeat(5) { scene.render(); yield() }
        }
        try {
            click("sync-compatibility-check")
            assertTrue(node("sync-compatibility-no-trusted-source") != null)
            click("sync-compatibility-local-package")
            click("sync-native-recovery-return")
            assertTrue(panel.actions.any { it is SyncPanelAction.RecoveryPlatformCompleted &&
                it.result == mihon.data.sync.runtime.SyncRecoveryPlatformResult.Cancelled })
        } finally { scene.close() }
    }

    @Test
    fun `production library recovery host pushes a real Screen onto its ordinary navigator`() = runBlocking {
        val panel = TestPanel()
        panel.state.value = mihon.data.sync.runtime.SyncPanelState(
            visible = true, loaded = true, page = SyncPanelPage.RECOVERY,
            recoveryPlatformRequest = mihon.data.sync.runtime.SyncRecoveryPlatformRequest(
                "network-fix", mihon.data.sync.runtime.SyncRecoveryPlatformAction.NETWORK),
            recoveryPlatformLaunchPending = true,
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { syncPanel } returns panel }
        val legacy = mockk<java.util.prefs.Preferences>(relaxed = true) {
            every { get(any(), any()) } returns null
        }
        every { dependencies.appPreferences } returns mihon.desktop.settings.DesktopAppPreferences(
            tachiyomi.core.common.preference.InMemoryPreferenceStore(), legacy)
        every { dependencies.networkRoutingPort.activeGlobalMode } returns mihon.desktop.settings.GlobalNetworkMode.SYSTEM
        every { dependencies.networkRoutingPort.activeGlobalProxy } returns null
        every { dependencies.networkRoutingPort.routeObservations } returns MutableStateFlow(emptyList())
        every { dependencies.networkHelper.activeDohProvider } answers { dependencies.appPreferences.dohProvider.get() }
        val model = LibraryScreenModel(GetLibraryManga(FakeMangaRepository()), GetCategories(FakeCategoryRepository()))
        var navigation: Navigator? = null
        val scene = ImageComposeScene(1200, 900, coroutineContext = coroutineContext) {}
        scene.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryScreenModelFactory({ model }) {
                        Navigator(LibraryRootScreen()) { navigator -> navigation = navigator; CurrentScreen() }
                    }
                }
            }
        }
        try {
            repeat(8) { scene.render(); yield() }
            assertTrue(navigation?.lastItem is DesktopSyncRecoveryScreen)
            assertFalse(navigation?.lastItem is cafe.adriel.voyager.navigator.tab.Tab)
            assertEquals("network-fix", (navigation!!.lastItem as DesktopSyncRecoveryScreen).requestId)
            navigation!!.pop()
            repeat(8) { scene.render(); yield() }
            assertTrue(panel.state.value.visible)
            assertEquals(SyncPanelPage.RECOVERY, panel.state.value.page)
            assertTrue(panel.actions.any {
                it is SyncPanelAction.RecoveryPlatformCompleted &&
                    it.result == mihon.data.sync.runtime.SyncRecoveryPlatformResult.NoChange
            }, panel.actions.toString())
        } finally { scene.close() }
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
            panel.state.value = panel.state.value.copy(
                canChangeSpace = true,
                recovery = mihon.data.sync.runtime.SyncSpaceRecovery(
                    mihon.data.sync.runtime.SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                ),
            )
            render()
            click("sync-recovery-open")
            assertTrue(panel.actions.contains(SyncPanelAction.OpenRecovery))
            // The wrapper forwards events; shared controller navigation is exercised in its integration tests.
            panel.state.value = panel.state.value.copy(page = SyncPanelPage.RECOVERY)
            render()
            assertTrue(find("sync-recovery-page") != null)
            requireNotNull(find("sync-recovery-create")!!.config[SemanticsActions.RequestFocus].action).invoke()
            render()
            val recoveryKeyType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod("access\$getKeyDown\$cp").invoke(null)
            val recoveryKeyFactory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods
                .single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
            val recoveryEscape = recoveryKeyFactory.invoke(
                null, Key.Escape.keyCode, recoveryKeyType, 0, false, false, false, false, null,
            )
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(recoveryEscape))
            render()
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            assertTrue(panel.actions.contains(SyncPanelAction.Back))
            click("sync-close")
            assertFalse(panel.state.value.visible)
            assertTrue(find("sync-open")!!.config[SemanticsProperties.Focused])
            panel.state.value = panel.state.value.copy(recovery = null)
            click("sync-open")
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
                every { DesktopSyncFailureLogOpener.open(any(), any(), any()) } returns true
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
                verify(exactly = 1) { DesktopSyncFailureLogOpener.open("/reports/failure.txt", any(), any()) }
            } finally {
                unmockkObject(DesktopSyncFailureLogOpener)
            }
            click("sync-close")
            assertFalse(panel.state.value.visible)
            assertTrue(find("sync-open")!!.config[SemanticsProperties.Focused])
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
        private val openedNativeRequests = mutableSetOf<String>()
        override fun claimRecoveryPlatform(requestId: String): Boolean = openedNativeRequests.add(requestId)
        val actions = mutableListOf<SyncPanelAction>()
        override fun dispatch(action: SyncPanelAction) {
            actions += action
            state.value = when (action) {
                SyncPanelAction.Open -> state.value.copy(visible = true)
                SyncPanelAction.OpenRecovery -> state.value.copy(visible = true, page = SyncPanelPage.RECOVERY)
                SyncPanelAction.Close -> state.value.copy(visible = false)
                is SyncPanelAction.Navigate -> state.value.copy(page = action.page)
                SyncPanelAction.ShowPasswordHelp -> state.value.copy(page = SyncPanelPage.PASSWORD_HELP, passwordHelpSource = SyncPasswordHelpSource.UNLOCK)
                SyncPanelAction.Back -> if (state.value.page == SyncPanelPage.PASSWORD_HELP) {
                    state.value.copy(page = SyncPanelPage.SETUP, passwordHelpReturn = state.value.passwordHelpReturn + 1)
                } else state.value.copy(page = if (state.value.page == SyncPanelPage.DIAGNOSTICS) SyncPanelPage.SETTINGS else SyncPanelPage.MAIN)
                SyncPanelAction.BeginSetup -> state.value.copy(page = SyncPanelPage.SETUP)
                else -> state.value
            }
        }
    }
}
