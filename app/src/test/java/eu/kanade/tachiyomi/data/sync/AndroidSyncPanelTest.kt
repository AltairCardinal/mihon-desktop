package eu.kanade.tachiyomi.data.sync

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.util.storage.getUriCompat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncFailureLogStatus
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidSyncPanelTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var previous: InjektScope
    private lateinit var activity: ActivityController<ComponentActivity>
    private val panel = TestPanel()

    @Before
    fun setup() {
        // Robolectric allocates a new filesDir per test while FileProvider keeps its static path cache.
        // Real application processes keep one filesDir; clear only this test-environment cache.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val runtime = mockk<SyncRuntime> { every { this@mockk.panel } returns this@AndroidSyncPanelTest.panel }
        Injekt.addSingleton(runtime)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After
    fun teardown() {
        activity.pause().stop().destroy()
        Injekt = previous
    }

    @Test
    fun `real library toolbar opens the shared panel through default runtime binding`() {
        panel.state.value = panel.state.value.copy(pendingTotal = 120, busy = true)
        showToolbar()
        compose.onNodeWithTag("sync-open").assertIsDisplayed().performClick()
        compose.onNodeWithText("99+").assertIsDisplayed()
        compose.onNodeWithTag("sync-wait").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("sync-settings").performClick()
        compose.onNodeWithTag("sync-settings-list", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("sync-password-status"))
        compose.onNodeWithTag("sync-password-status", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("sync-password-status", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sync-back").performClick()
        compose.onNodeWithTag("sync-wait").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("sync-close").performClick()
        compose.runOnIdle {
            assertFalse(panel.state.value.visible)
            assertFalse(panel.actions.contains(SyncPanelAction.CancelSync))
        }
    }

    @Test
    fun `native shared sheet renders the same compact plan in main and setup merging`() {
        panel.state.value = panel.state.value.copy(
            run = SyncRunSnapshot(
                "compact-run", "space", 1, SyncTrigger.MANUAL, SyncRunState.RUNNING, SyncRunPhase.DOWNLOADING,
                6, 100, 0, 0, 0, attemptId = 1, nextRetryAt = 0, lastProgressAt = 1000,
                stopReason = null, ownerSession = "fixture", createdAt = 1000, updatedAt = 1000,
                confirmedItems = 4, plannedItems = 10,
            ),
        )
        showToolbar()
        compose.onNodeWithTag("sync-open").performClick()
        fun assertCompact() {
            val track = compose.onNodeWithTag("sync-progress-track", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(
                0.4f,
                track.config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current,
            )
            val status = compose.onNodeWithTag("sync-progress-status", useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(
                status.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.contains("4/10"),
            )
            compose.onNodeWithTag("sync-round-time", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("sync-progress-details-toggle", useUnmergedTree = true).assertDoesNotExist()
        }
        assertCompact()
        compose.runOnIdle {
            panel.state.value = panel.state.value.copy(page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.MERGING)
        }
        assertCompact()
        compose.onNodeWithTag("sync-back").performClick()
        compose.onNodeWithTag("sync-pause-run").performClick()
        compose.runOnIdle { assertEquals(SyncPanelAction.PauseSync, panel.actions.last()) }
        compose.onNodeWithTag("sync-close").performClick()
        compose.runOnIdle { assertFalse(panel.state.value.visible) }
    }

    @Test
    fun `native wrapper routes disconnected cancelled history to shared setup`() {
        panel.state.value = panel.state.value.copy(
            connection = panel.state.value.connection!!.copy(enabled = false),
            run = SyncRunSnapshot(
                "cancelled-run", "space", 1, SyncTrigger.MANUAL, SyncRunState.CANCELLED, SyncRunPhase.COMPLETE,
                0, 0, 0, 0, 0, attemptId = 1, nextRetryAt = 0, lastProgressAt = 1000,
                stopReason = "user", ownerSession = null, createdAt = 1000, updatedAt = 17_515_000,
                confirmedItems = 1536,
            ),
        )
        showToolbar()
        compose.onNodeWithTag("sync-open").performClick()
        compose.onNodeWithTag("sync-now").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sync-authorize").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(SyncPanelPage.SETUP, panel.state.value.page)
            assertFalse(panel.state.value.connection!!.enabled)
            assertEquals(1536L, panel.state.value.run!!.confirmedItems)
            assertTrue(panel.actions.contains(SyncPanelAction.BeginSetup))
            assertFalse(panel.actions.contains(SyncPanelAction.Synchronize))
        }
    }

    @Test
    fun `native password entry handles system back inside the existing sheet`() {
        panel.state.value = panel.state.value.copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.NEW_PASSWORD,
        )
        showToolbar()
        compose.onNodeWithTag("sync-open").performClick()
        compose.onNodeWithTag("sync-password-input").assertIsDisplayed().performTextInput("temporary")
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("sync-password-input").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            assertTrue(panel.state.value.visible)
            assertFalse(panel.actions.any { it is SyncPanelAction.SubmitPassword })
        }
    }

    @Test
    fun `real sync sheet opens the persisted failure report`() {
        requireUnixFileProviderHost()
        val report = activity.get().filesDir.resolve("sync-failures/sheet-report.txt")
        report.parentFile!!.mkdirs()
        report.writeText("无法恢复条目详情", Charsets.UTF_8)
        try {
            panel.state.value = panel.state.value.copy(
                run = SyncRunSnapshot(
                    "report-run", "space", 1, SyncTrigger.MANUAL, SyncRunState.PARTIAL, SyncRunPhase.MERGING,
                    0, 1, 0, 0, 1, attemptId = 1, nextRetryAt = 0, lastProgressAt = 0,
                    stopReason = "projection_pending", ownerSession = null, createdAt = 0, updatedAt = 1,
                ),
                failureLog = SyncFailureLogStatus.Ready("report-run", report.absolutePath, 1),
            )
            showToolbar()
            compose.onNodeWithTag("sync-open").performClick()
            compose.onNodeWithTag("sync-failure-log-open").performScrollTo().performClick()
            val intent = requireNotNull(shadowOf(activity.get()).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("text/plain", intent.type)
        } finally {
            report.delete()
        }
    }

    @Test
    fun `failure log opens a readable content uri and rejects files outside report directory`() {
        requireUnixFileProviderHost()
        val context = activity.get()
        val report = context.filesDir.resolve("sync-failures/test-report.txt")
        val outside = context.filesDir.resolve("outside.txt")
        report.parentFile!!.mkdirs()
        report.writeText("无法恢复的漫画：测试", Charsets.UTF_8)
        outside.writeText("Not a sync report", Charsets.UTF_8)
        try {
            val actions = AndroidSyncPanelActions(context)
            assertEquals("content", report.getUriCompat(context).scheme)
            actions.openFailureLog(report.absolutePath)
            val intent = requireNotNull(shadowOf(context).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("text/plain", intent.type)
            assertEquals("content", intent.data?.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val restored = context.contentResolver.openInputStream(requireNotNull(intent.data))!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            assertEquals("无法恢复的漫画：测试", restored)
            actions.openFailureLog(outside.absolutePath)
            assertEquals(null, shadowOf(context).nextStartedActivity)
        } finally {
            report.delete()
            outside.delete()
        }
    }

    @Test
    fun `missing text viewer offers a chooser with a readable report attachment`() {
        requireUnixFileProviderHost()
        val context = activity.get()
        val report = context.filesDir.resolve("sync-failures/share-report.txt")
        report.parentFile!!.mkdirs()
        report.writeText("保存失败数据详情", Charsets.UTF_8)
        val withoutViewer = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Intent.ACTION_VIEW) throw ActivityNotFoundException("no viewer")
                super.startActivity(intent)
            }
        }
        try {
            AndroidSyncPanelActions(withoutViewer).openFailureLog(report.absolutePath)
            val chooser = requireNotNull(shadowOf(context).nextStartedActivity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val share = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("text/plain", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("content", share.clipData?.getItemAt(0)?.uri?.scheme)
        } finally {
            report.delete()
        }
    }

    @Test
    fun `native authorization uses browser intent and hides clipboard previews`() {
        val actions = AndroidSyncPanelActions(activity.get())
        actions.openBrowser("https://github.com/login/device")
        assertEquals("https://github.com/login/device", shadowOf(activity.get()).nextStartedActivity?.dataString)
        actions.copyCode("ABCD-EFGH")
        val clipboard = activity.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("ABCD-EFGH", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(clipboard.primaryClip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
    }

    @Test
    fun `receiving device code opens the system browser from the real sync sheet`() {
        panel.state.value = panel.state.value.copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.SIGN_IN,
            setupBusy = true,
            deviceCode = GitHubDeviceCode(
                "secret-device",
                "ABCD-EFGH",
                "https://github.com/login/device",
                900,
                5,
            ),
        )
        showToolbar()
        compose.onNodeWithTag("sync-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sync-auth-waiting-browser", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("https://github.com/login/device", shadowOf(activity.get()).nextStartedActivity?.dataString)
    }

    @Test
    fun `real sync sheet explains device code wait and delayed network check`() {
        panel.state.value = panel.state.value.copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.SIGN_IN,
            setupBusy = true,
            authRequestStartedAtMillis = 1_000,
            nowMillis = 1_000,
        )
        showToolbar()
        compose.onNodeWithTag("sync-open").performClick()
        compose.onNodeWithTag("sync-auth-getting-code", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sync-auth-network-hint").assertDoesNotExist()
        panel.state.value = panel.state.value.copy(nowMillis = 6_000)
        compose.onNodeWithTag("sync-auth-network-hint", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun showToolbar() {
        activity.get().setContent {
            MaterialTheme {
                LibraryToolbar(
                    false, 0, LibraryToolbarTitle("Library"), {}, {}, {}, {}, {}, {}, {},
                    null, {}, null,
                )
            }
        }
    }

    @Test
    fun `diagnostic adapter shares generated JSON and blocks private or unrelated files`() {
        val context = activity.get()
        val directory = context.cacheDir.resolve("sync-diagnostics").apply { mkdirs() }
        val file = directory.resolve("sync-diagnostic-01234567-0123-0123-0123-012345678901.json")
        file.writeText("{}", Charsets.UTF_8)
        val uri = android.net.Uri.parse("content://synthetic-test/diagnostic.json")
        val actions = AndroidSyncPanelActions(context, diagnosticUri = { uri })
        actions.openDiagnostics(file.path)
        val chooser = requireNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("application/json", share.type)
        assertEquals(uri, share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, share.clipData!!.getItemAt(0).uri)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        for (invalid in listOf(
            context.filesDir.resolve(file.name),
            directory.resolve("private/session.json"),
            directory.resolve("arbitrary.json"),
            directory.resolve("sync-diagnostic-private.txt"),
        )) {
            invalid.parentFile!!.mkdirs()
            invalid.writeText("private", Charsets.UTF_8)
            actions.openDiagnostics(invalid.path)
            assertEquals(null, shadowOf(context).nextStartedActivity)
        }
    }

    @Test
    fun `native diagnostics open dispatches to platform JSON adapter`() {
        val path = activity.get().cacheDir.resolve("sync-diagnostics/sync-diagnostic-fixture.json").path
        panel.state.value = panel.state.value.copy(
            page = SyncPanelPage.DIAGNOSTICS,
            diagnosticSnapshot = mihon.data.sync.runtime.SyncDiagnosticSnapshot(
                status = mihon.data.sync.runtime.SyncDiagnosticStatus.OK,
            ),
            diagnosticPath = path,
        )
        io.mockk.mockkConstructor(AndroidSyncPanelActions::class)
        try {
            every { anyConstructed<AndroidSyncPanelActions>().openDiagnostics(path) } returns Unit
            showToolbar()
            compose.onNodeWithTag("sync-open").performClick()
            compose.onNodeWithTag("sync-diagnostics-list", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("sync-diagnostic-open"))
            compose.onNodeWithTag("sync-diagnostic-open").performClick()
            io.mockk.verify(exactly = 1) { anyConstructed<AndroidSyncPanelActions>().openDiagnostics(path) }
        } finally {
            io.mockk.unmockkConstructor(AndroidSyncPanelActions::class)
        }
    }

    @Test
    fun `native diagnostics entry returns via system back to settings`() {
        showToolbar()
        compose.onNodeWithTag("sync-open").performClick()
        compose.onNodeWithTag("sync-settings").performClick()
        compose.onNodeWithTag("sync-settings-diagnostics").performClick()
        compose.onNodeWithTag("sync-diagnostic-capture").assertIsDisplayed().performClick()
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page) }
        compose.onNodeWithTag("sync-settings-diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("sync-back").performClick()
        compose.runOnIdle {
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            assertTrue(panel.actions.contains(SyncPanelAction.CaptureDiagnostics))
        }
    }

    private fun requireUnixFileProviderHost() {
        // AndroidX FileProvider compares canonical paths with a literal '/', while Robolectric
        // uses the host filesystem. Run these real-provider integration tests on a Unix host.
        org.junit.Assume.assumeTrue(
            "Real Android FileProvider requires Unix host paths",
            java.io.File.separatorChar == '/',
        )
    }

    private class TestPanel : SyncPanel {
        override val state = MutableStateFlow(
            SyncPanelState(
                loaded = true,
                connection = SyncConnection(
                    "space",
                    1,
                    SyncRepository("owner", "repo", "sync"),
                    true,
                    protectionMode = "none",
                ),
            ),
        )
        private val openedDeviceCodes = mutableSetOf<String>()
        override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean = openedDeviceCodes.add(code.deviceCode)
        val actions = mutableListOf<SyncPanelAction>()
        override fun dispatch(action: SyncPanelAction) {
            actions += action
            state.value = when (action) {
                SyncPanelAction.Open -> state.value.copy(visible = true)
                SyncPanelAction.Close -> state.value.copy(visible = false)
                is SyncPanelAction.Navigate -> state.value.copy(page = action.page)
                SyncPanelAction.Back -> state.value.copy(
                    page = if (state.value.page == SyncPanelPage.DIAGNOSTICS) {
                        SyncPanelPage.SETTINGS
                    } else {
                        SyncPanelPage.MAIN
                    },
                )
                SyncPanelAction.BeginSetup -> state.value.copy(page = SyncPanelPage.SETUP)
                else -> state.value
            }
        }
    }
}
