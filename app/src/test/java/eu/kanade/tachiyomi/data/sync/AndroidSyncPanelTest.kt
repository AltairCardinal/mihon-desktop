package eu.kanade.tachiyomi.data.sync

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
            .performScrollToNode(hasTestTag("sync-password-status"))
        compose.onNodeWithTag("sync-password-status", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("sync-password-status", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sync-back").performClick()
        compose.onNodeWithTag("sync-now").assertIsDisplayed()
        compose.onNodeWithTag("sync-close").performClick()
        compose.runOnIdle {
            assertFalse(panel.state.value.visible)
            assertFalse(panel.actions.contains(SyncPanelAction.CancelSync))
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
    fun `native authorization uses browser intent and hides clipboard previews`() {
        val actions = AndroidSyncPanelActions(activity.get())
        actions.openBrowser("https://github.com/login/device")
        assertEquals("https://github.com/login/device", shadowOf(activity.get()).nextStartedActivity?.dataString)
        actions.copyCode("ABCD-EFGH")
        val clipboard = activity.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("ABCD-EFGH", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(clipboard.primaryClip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
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
                connection = SyncConnection(
                    "space",
                    1,
                    SyncRepository("owner", "repo", "sync"),
                    true,
                    protectionMode = "none",
                ),
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
