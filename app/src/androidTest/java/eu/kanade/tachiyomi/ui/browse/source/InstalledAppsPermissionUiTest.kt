package eu.kanade.tachiyomi.ui.browse.source

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.permission.AndroidInstalledAppsPermissionDetector
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionController
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionState
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InstalledAppsPermissionUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun deniedPermissionDoesNotHidePrivateScanRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retries = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                SourcesScreen(
                    state = SourcesScreenModel.State(
                        isLoading = false,
                        installedAppsPermission = InstalledAppsPermissionState(
                            InstalledAppsPermissionStatus.DENIED,
                            scanFailed = true,
                        ),
                    ),
                    contentPadding = PaddingValues(),
                    onClickItem = { _, _ -> },
                    onClickPin = {},
                    onLongClickItem = {},
                    onRetryInstalledAppsPermission = { retries.incrementAndGet() },
                )
            }
        }
        compose.onNodeWithText(
            context.stringResource(MR.strings.installed_apps_permission_required),
        ).assertIsDisplayed()
        compose.onNodeWithText(context.stringResource(MR.strings.installed_apps_permission_get)).assertIsDisplayed()
        compose.onNodeWithText(context.stringResource(MR.strings.action_retry)).performClick()
        assertTrue(retries.get() == 1)
    }

    @Test
    fun sourceTabPermissionActionCancelSettingsFailureAndRetryAreWired() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permission = AtomicReference(InstalledAppsPermissionStatus.DENIED)
        val settingsCalls = AtomicInteger()
        val registry = PermissionRegistry()
        val controller = InstalledAppsPermissionController(
            detector = { permission.get() },
            settings = {
                settingsCalls.incrementAndGet()
                null
            },
        )
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme {
                    Navigator(object : Screen() {
                        @Composable
                        override fun Content() {
                            val model = rememberScreenModel { SourcesScreenModel(installedAppsPermission = controller) }
                            LaunchedEffect(Unit) { controller.refresh() }
                            sourcesTab(model).content(PaddingValues(), remember { SnackbarHostState() })
                        }
                    })
                }
            }
        }
        val getPermission = context.stringResource(MR.strings.installed_apps_permission_get)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.stringResource(MR.strings.installed_apps_permission_required))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            context.stringResource(MR.strings.installed_apps_permission_required),
        ).assertIsDisplayed()
        compose.onNodeWithText(getPermission).performClick()
        compose.onNodeWithText(
            context.stringResource(MR.strings.installed_apps_permission_explanation),
        ).assertIsDisplayed()
        val getBounds = compose.onNodeWithTag("permission-dialog-get").fetchSemanticsNode().boundsInRoot
        val backBounds = compose.onNodeWithTag("permission-dialog-back").fetchSemanticsNode().boundsInRoot
        assertTrue("Permission action must precede Back vertically", getBounds.top < backBounds.top)
        compose.onNodeWithTag("permission-dialog-back").performClick()
        assertTrue("Back must not launch settings", settingsCalls.get() == 0)
        compose.onNodeWithText(getPermission).performClick()
        compose.onNodeWithTag("permission-dialog-get").performClick()
        assertEquals(AndroidInstalledAppsPermissionDetector.PERMISSION, registry.requestedPermission)
        assertEquals(0, settingsCalls.get())
        compose.runOnIdle { registry.dispatchResult(registry.requestCode, false) }
        compose.onNodeWithTag("permission-dialog-back").performClick()
        assertEquals(0, settingsCalls.get())
        compose.onNodeWithText(getPermission).performClick()
        compose.onNodeWithTag("permission-dialog-get").performClick()
        assertTrue(settingsCalls.get() > 0)
        compose.onNodeWithText(
            context.stringResource(MR.strings.installed_apps_permission_settings_unavailable),
        ).assertIsDisplayed()
        permission.set(InstalledAppsPermissionStatus.GRANTED)
        compose.onNodeWithText(context.stringResource(MR.strings.action_retry)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("installed-apps-permission-notice").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("installed-apps-permission-notice").assertDoesNotExist()
    }

    @Test
    fun directGrantRechecksPermissionAndRestoresSourcesWithoutOpeningSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permission = AtomicReference(InstalledAppsPermissionStatus.DENIED)
        val settingsCalls = AtomicInteger()
        val registry = PermissionRegistry()
        val controller = InstalledAppsPermissionController(
            detector = { permission.get() },
            settings = {
                settingsCalls.incrementAndGet()
                null
            },
        )
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                MaterialTheme {
                    Navigator(object : Screen() {
                        @Composable
                        override fun Content() {
                            val model = rememberScreenModel { SourcesScreenModel(installedAppsPermission = controller) }
                            LaunchedEffect(Unit) { controller.refresh() }
                            sourcesTab(model).content(PaddingValues(), remember { SnackbarHostState() })
                        }
                    })
                }
            }
        }
        val getPermission = context.stringResource(MR.strings.installed_apps_permission_get)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(getPermission).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(getPermission).performClick()
        compose.onNodeWithTag("permission-dialog-get").performClick()
        assertEquals(AndroidInstalledAppsPermissionDetector.PERMISSION, registry.requestedPermission)
        assertEquals(0, settingsCalls.get())
        compose.runOnIdle {
            permission.set(InstalledAppsPermissionStatus.GRANTED)
            registry.dispatchResult(registry.requestCode, true)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("installed-apps-permission-notice").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, settingsCalls.get())
    }

    private class PermissionRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var requestedPermission: String? = null
        var requestCode = 0
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            check(contract is ActivityResultContracts.RequestPermission) { "Must request runtime permission first" }
            this.requestCode = requestCode
            requestedPermission = input as String
        }
    }
}
