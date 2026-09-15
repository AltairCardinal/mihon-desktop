package mihon.presentation.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.inbox.SyncPendingItem
import mihon.data.sync.runtime.SyncBulkConfirmation
import mihon.data.sync.runtime.SyncBulkStatus
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelNotice
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncRepository
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class SyncPanelContentTest {
    @Test
    fun `reconnection explicitly authorizes from status settings and failed discovery`() = rendered(
        connected().copy(problem = SyncRunProblem.AUTHORIZATION),
    ) {
        awaitTag("sync-reconnect")
        click("sync-reconnect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
        panel.state.value = connected().copy(page = SyncPanelPage.SETTINGS)
        render()
        click("sync-settings-connect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
        panel.state.value = connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.REPOSITORY,
            problem = SyncRunProblem.UNKNOWN,
        )
        awaitTag("sync-repo-reconnect")
        click("sync-repo-reconnect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
    }

    @Test
    fun `unconfigured settings disable space actions but disconnected recovery remains available`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETTINGS),
    ) {
        awaitTag("sync-settings-list")
        scroll("sync-settings-list", 5)
        for (tag in listOf("sync-show-recovery", "sync-disconnect", "sync-switch")) {
            assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
        }
        panel.state.value = panel.state.value.copy(connection = connected().connection!!.copy(enabled = false))
        render()
        assertFalse(node("sync-show-recovery").config.contains(SemanticsProperties.Disabled))
        click("sync-show-recovery")
        assertEquals(SyncPanelAction.ShowRecovery, actions.last())
    }

    @Test
    fun `empty pending list omits the selection toolbar`() = rendered(connected()) {
        awaitTag("sync-history")
        assertFalse(hasTag("sync-selection-bar"))
    }

    @Test
    fun `repository setup opens installation and creation in the system browser`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.REPOSITORY),
    ) {
        awaitTag("sync-install-app")
        click("sync-install-app")
        assertEquals(listOf("https://github.com/apps/mihon-desktop/installations/new"), opened)
        click("sync-create-repo")
        assertEquals("https://github.com/new", opened.last())
        assertTrue(actions.isEmpty())
        click("sync-refresh-repos")
        assertEquals(listOf(SyncPanelAction.RefreshRepositories), actions)
    }

    @Test
    fun `unfinished bulk disables replacement decisions while keeping resume available`() = rendered(
        connected().copy(pendingTotal = 1, pending = listOf(item(1))),
    ) {
        for (running in listOf(false, true)) {
            panel.state.value = panel.state.value.copy(
                selecting = false,
                bulk = SyncBulkStatus("frozen", 3, 2, 1, 0, 0, running),
            )
            awaitTag("sync-keep-1")
            render()
            for (tag in listOf("sync-keep-1", "sync-remove-1", "sync-all-menu")) {
                assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
            }
            val control = if (running) "sync-pause-bulk" else "sync-resume-bulk"
            assertFalse(node(control).config.contains(SemanticsProperties.Disabled))
            click(control)
            assertEquals(if (running) SyncPanelAction.PauseBulk else SyncPanelAction.ResumeBulk, actions.last())
            panel.state.value = panel.state.value.copy(selecting = true, selected = setOf(1))
            render()
            for (tag in listOf("sync-keep-selected", "sync-remove-selected")) {
                assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
            }
        }
        panel.state.value = panel.state.value.copy(
            bulk = panel.state.value.bulk!!.copy(remaining = 0, running = false),
        )
        render()
        assertFalse(node("sync-keep-selected").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `disconnected retained space offers connection instead of synchronization`() = rendered(
        connected().copy(connection = connected().connection!!.copy(enabled = false)),
    ) {
        awaitTag("sync-now")
        click("sync-now")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
        awaitTag("sync-history")
        val disconnectedText = texts()
        panel.state.value = panel.state.value.copy(connection = null)
        render()
        assertEquals(disconnectedText, texts())
    }

    @Test
    fun `baseline pause and resume remain separate from an active exchange`() = rendered(
        connected().copy(importRemaining = 9, busy = true),
    ) {
        awaitTag("sync-pause-import")
        click("sync-pause-import")
        assertEquals(SyncPanelAction.PauseImport, actions.last())
        panel.state.value = panel.state.value.copy(importPaused = true)
        render()
        click("sync-resume-import")
        assertEquals(SyncPanelAction.ResumeImport, actions.last())
        assertFalse(actions.contains(SyncPanelAction.CancelSync))
    }

    @Test
    fun `failed and partial notices report the failure without a false success`() = rendered(
        connected().copy(
            notice = SyncPanelNotice(
                exchange = SyncRunResult(
                    SyncRunStatus.FAILED,
                    problem = SyncRunProblem.NETWORK,
                ),
            ),
        ),
    ) {
        awaitTag("sync-notice-error")
        assertFalse(hasTag("sync-notice-counts"))
        panel.state.value = panel.state.value.copy(
            notice = SyncPanelNotice(
                exchange = SyncRunResult(
                    SyncRunStatus.PARTIAL,
                    uploaded = 1,
                    problem = SyncRunProblem.NETWORK,
                ),
            ),
        )
        render()
        assertTrue(hasTag("sync-notice-error"))
        assertTrue(hasTag("sync-notice-counts"))
    }

    @Test
    fun `toolbar shows busy and bounded cancellation count together`() = rendered(
        connected().copy(busy = true, pendingTotal = 120),
    ) {
        awaitTag("sync-open")
        assertTrue(texts().contains("99+"))
        val countBounds = node("sync-count").boundsInRoot
        val buttonBounds = node("sync-open").boundsInRoot
        assertTrue(countBounds.left >= buttonBounds.left && countBounds.right <= buttonBounds.right)
        assertTrue(countBounds.top >= buttonBounds.top && countBounds.bottom <= buttonBounds.bottom)
        assertTrue(node("sync-open").config[SemanticsProperties.StateDescription].isNotEmpty())
        click("sync-open")
        assertEquals(SyncPanelAction.Open, actions.last())
        panel.state.value = connected().copy(queuedMembership = 120)
        render()
        assertFalse(texts().contains("99+"))
        assertEquals("", node("sync-open").config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `status keeps one synchronize action and long list selection stays above rows`() = rendered(
        connected().copy(pendingTotal = 120, pending = (1L..120L).map(::item), queuedReading = 2),
    ) {
        awaitTag("sync-now")
        assertEquals(1, nodes().count { tag(it) == "sync-now" })
        click("sync-now")
        assertEquals(SyncPanelAction.Synchronize, actions.last())
        awaitTag("sync-select")
        click("sync-select")
        assertEquals(SyncPanelAction.SelectionMode(true), actions.last())
        panel.state.value = panel.state.value.copy(selecting = true, selected = setOf(1))
        render()
        click("sync-select-all")
        assertEquals(SyncPanelAction.SelectAll, actions.last())
        click("sync-invert")
        assertEquals(SyncPanelAction.InvertSelection, actions.last())
        val row = node("sync-item-1")
        requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke()
        assertEquals(SyncPanelAction.ToggleItem(1, range = true), actions.last())
        assertTrue(node("sync-selection-bar").boundsInRoot.top < row.boundsInRoot.top)
        captureVisuals("main")
        click("sync-keep-selected")
        assertTrue(actions.last() is SyncPanelAction.PrepareDecision)
    }

    @Test
    fun `settings and records remain in the panel and expose native actions`() = rendered(connected()) {
        awaitTag("sync-settings")
        click("sync-settings")
        assertEquals(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS), actions.last())
        panel.state.value = connected().copy(page = SyncPanelPage.SETTINGS)
        render()
        captureVisuals("settings")
        click("sync-period-0")
        assertEquals(SyncPanelAction.SetPeriod(0), actions.last())
        scroll("sync-settings-list", 5)
        click("sync-show-recovery")
        assertEquals(SyncPanelAction.ShowRecovery, actions.last())
        click("sync-disconnect")
        assertEquals(SyncPanelAction.Ask(SyncPanelQuestion.DISCONNECT), actions.last())
        click("sync-back")
        assertEquals(SyncPanelAction.Back, actions.last())
        panel.state.value = connected().copy(page = SyncPanelPage.HISTORY)
        render()
        assertTrue(hasTag("sync-records"))
    }

    @Test
    fun `device authorization uses native copy and browser without sharing device secret`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            deviceCode = GitHubDeviceCode("secret-device", "ABCD-EFGH", "https://github.com/login/device", 900, 5),
        ),
    ) {
        awaitTag("sync-copy-open")
        click("sync-copy-open")
        assertEquals(listOf("ABCD-EFGH"), copied)
        assertEquals(listOf("https://github.com/login/device"), opened)
        assertFalse(texts().any { it.contains("secret-device") })
        click("sync-cancel-auth")
        assertEquals(SyncPanelAction.CancelAuthorization, actions.last())
    }

    @Test
    fun `recovery export requires native save and merge stays an explicit action`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.RECOVERY,
            newSpace = true,
            recoveryText = "recovery-secret",
        ),
    ) {
        awaitTag("sync-save-recovery")
        click("sync-save-recovery")
        assertEquals(listOf("recovery-secret"), saved)
        assertFalse(actions.any { it is SyncPanelAction.RecoverySaved })
        assertFalse(texts().contains("recovery-secret"))
        assertTrue(node("sync-prepare-merge").config.contains(SemanticsProperties.Disabled))
        panel.state.value = panel.state.value.copy(recoverySaved = true)
        render()
        click("sync-prepare-merge")
        assertEquals(SyncPanelAction.PrepareMerge, actions.last())
        panel.state.value = panel.state.value.copy(setupStep = SyncSetupStep.MERGE)
        render()
        click("sync-confirm-merge")
        assertEquals(SyncPanelAction.ConfirmMerge, actions.last())
    }

    @Test
    fun `bulk confirmation and pause use frozen job actions`() = rendered(
        connected().copy(confirmation = SyncBulkConfirmation("frozen", SyncCancellationDecision.CONFIRM, 80, 40)),
    ) {
        awaitTag("sync-confirm-decision")
        assertTrue(texts().any { it.contains("80") && it.contains("40") })
        click("sync-confirm-decision")
        assertEquals(SyncPanelAction.ConfirmDecision, actions.last())
        panel.state.value = connected().copy(bulk = SyncBulkStatus("frozen", 120, 70, 50, 0, 0, true))
        render()
        click("sync-pause-bulk")
        assertEquals(SyncPanelAction.PauseBulk, actions.last())
        panel.state.value = panel.state.value.copy(bulk = panel.state.value.bulk!!.copy(running = false))
        render()
        click("sync-resume-bulk")
        assertEquals(SyncPanelAction.ResumeBulk, actions.last())
    }

    private fun connected() = SyncPanelState(
        visible = true,
        loaded = true,
        connection = SyncConnection("space", 1, SyncRepository("owner", "private", "sync"), true),
    )

    private fun item(id: Long) = SyncPendingItem(
        id,
        "binding-$id",
        SyncObjectKey(SyncObjectType.MANGA, sourceId = "7", originalUrl = "/$id"),
        "Manga $id",
    )

    private fun rendered(state: SyncPanelState, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(state, ImageComposeScene(560, 720, coroutineContext = coroutineContext) {})
        try {
            fixture.setContent()
            fixture.block()
        } finally {
            fixture.scene.close()
        }
    }

    private class Fixture(initial: SyncPanelState, val scene: ImageComposeScene) {
        val actions = mutableListOf<SyncPanelAction>()
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val saved = mutableListOf<String>()
        var imported = 0
        val panel = TestPanel(initial, actions)
        fun setContent() {
            scene.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val state by panel.state.collectAsState()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            SyncToolbarButton(state) { actions += SyncPanelAction.Open }
                            SyncPanelContent(
                                panel,
                                onOpenBrowser = opened::add,
                                onCopyCode = copied::add,
                                onSaveRecovery = saved::add,
                                onImportRecovery = { imported++ },
                            )
                        }
                    }
                }
            }
        }
        suspend fun captureVisuals(name: String) {
            val directory = System.getProperty("mihon.sync.visualDir")?.let(::File) ?: return
            directory.mkdirs()
            for ((platform, size) in listOf("android" to (400 to 800), "desktop" to (560 to 680))) {
                val rendered = Fixture(
                    panel.state.value,
                    ImageComposeScene(size.first, size.second, coroutineContext = currentCoroutineContext()) {},
                )
                try {
                    rendered.setContent()
                    rendered.awaitTag(if (name == "main") "sync-keep-selected" else "sync-settings-list")
                    rendered.render()
                    rendered.scene.render().use { image ->
                        requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                            File(directory, "$platform-$name.png").writeBytes(data.bytes)
                        }
                    }
                } finally {
                    rendered.scene.close()
                }
            }
        }
        suspend fun render() {
            repeat(3) {
                scene.render()
                yield()
            }
        }
        suspend fun awaitTag(value: String) = withTimeout(2_000) {
            while (!hasTag(value)) {
                render()
                yield()
            }
        }
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun hasTag(value: String) = nodes().any { tag(it) == value }
        fun node(value: String) = nodes().first { tag(it) == value }
        fun click(value: String) {
            assertTrue(requireNotNull(node(value).config[SemanticsActions.OnClick].action).invoke())
        }
        suspend fun scroll(value: String, index: Int) {
            requireNotNull(node(value).config[SemanticsActions.ScrollToIndex].action).invoke(index)
            render()
        }
        fun texts() = nodes().flatMap {
            if (it.config.contains(SemanticsProperties.Text)) {
                it.config[SemanticsProperties.Text].map { text -> text.text }
            } else {
                emptyList()
            }
        }
        fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) {
            node.config[SemanticsProperties.TestTag]
        } else {
            null
        }
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    }

    private class TestPanel(initial: SyncPanelState, private val actions: MutableList<SyncPanelAction>) : SyncPanel {
        override val state = MutableStateFlow(initial)
        override fun dispatch(action: SyncPanelAction) {
            actions += action
        }
    }
}
