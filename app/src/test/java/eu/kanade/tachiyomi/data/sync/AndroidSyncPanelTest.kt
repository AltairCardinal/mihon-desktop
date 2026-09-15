package eu.kanade.tachiyomi.data.sync

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File

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
        compose.onNodeWithTag("sync-now").assertIsDisplayed()
        compose.onNodeWithTag("sync-settings").performClick()
        compose.onNodeWithTag("sync-settings-list", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("sync-show-recovery"))
        compose.onNodeWithTag("sync-show-recovery").assertIsDisplayed()
        compose.onNodeWithTag("sync-back").performClick()
        compose.onNodeWithTag("sync-now").assertIsDisplayed()
        compose.onNodeWithTag("sync-close").performClick()
        compose.runOnIdle {
            assertFalse(panel.state.value.visible)
            assertFalse(panel.actions.contains(SyncPanelAction.CancelSync))
        }
    }

    @Test
    fun `real sheet picker result writes the recovery before acknowledging it`() {
        panel.state.value = panel.state.value.copy(
            page = SyncPanelPage.RECOVERY,
            setupStep = SyncSetupStep.RECOVERY,
            recoveryText = "picker-recovery-fixture",
        )
        val file = File.createTempFile("sync-picker-", ".json", activity.get().cacheDir)
        try {
            showToolbar()
            compose.onNodeWithTag("sync-open").performClick()
            compose.onNodeWithTag("sync-save-recovery").performClick()
            val request = shadowOf(activity.get()).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertFalse(panel.actions.any { it is SyncPanelAction.RecoverySaved })
            compose.runOnUiThread {
                shadowOf(activity.get()).receiveResult(
                    request.intent,
                    Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(file)),
                )
            }
            compose.waitUntil(5000) {
                panel.actions.contains(SyncPanelAction.RecoverySaved("picker-recovery-fixture"))
            }
            assertEquals("picker-recovery-fixture", file.readText(Charsets.UTF_8))
        } finally {
            file.delete()
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
    fun `recovery save acknowledges only a successful real document write`() = runBlocking {
        val directory = RuntimeEnvironment.getApplication().cacheDir
        val file = File.createTempFile("sync-recovery-", ".txt", directory)
        try {
            val files = AndroidSyncPanelFiles(activity.get(), panel)
            assertTrue(files.save(Uri.fromFile(file), "recovery-fixture"))
            assertEquals("recovery-fixture", file.readText(Charsets.UTF_8))
            assertEquals(listOf(SyncPanelAction.RecoverySaved("recovery-fixture")), panel.actions)
            assertFalse(files.save(Uri.fromFile(directory), "must-not-be-acknowledged"))
            assertEquals(1, panel.actions.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `recovery document import is bounded and malformed utf8 is rejected`() = runBlocking {
        val file = File.createTempFile("sync-import-", ".txt", activity.get().cacheDir)
        try {
            val files = AndroidSyncPanelFiles(activity.get(), panel)
            file.writeText("recovery-fixture", Charsets.UTF_8)
            assertTrue(files.import(Uri.fromFile(file)))
            assertEquals(listOf(SyncPanelAction.SetRecovery("recovery-fixture")), panel.actions)
            file.writeBytes(ByteArray(16 * 1024 + 1))
            assertFalse(files.import(Uri.fromFile(file)))
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertFalse(files.import(Uri.fromFile(file)))
            assertEquals(1, panel.actions.size)
        } finally {
            file.delete()
        }
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

    private class TestPanel : SyncPanel {
        override val state = MutableStateFlow(
            SyncPanelState(
                loaded = true,
                connection = SyncConnection("space", 1, SyncRepository("owner", "repo", "sync"), true),
            ),
        )
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
