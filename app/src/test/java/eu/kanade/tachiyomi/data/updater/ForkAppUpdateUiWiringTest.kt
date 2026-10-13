package eu.kanade.tachiyomi.data.updater

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class ForkAppUpdateUiWiringTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var previous: InjektScope
    private val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
    private val interactor = mockk<GetApplicationRelease>()
    private val manager = mockk<WorkManager>()
    private val request = slot<OneTimeWorkRequest>()
    private val code = BuildConfig.VERSION_CODE.toLong() + 1
    private val release = Release(
        "candidate",
        "notes",
        "https://github.com/AltairCardinal/mihon-desktop/releases/tag/android-vc91",
        "https://github.com/AltairCardinal/mihon-desktop/releases/download/android-vc91/app.apk",
        versionCode = code,
    )

    @Before
    fun setup() {
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(UiPreferences(InMemoryPreferenceStore()))
        Injekt.addSingleton(preferences)
        Injekt.addSingleton(interactor)
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns manager
        every { manager.enqueueUniqueWork(any(), any(), capture(request)) } returns mockk<Operation>(relaxed = true)
        mockkConstructor(AppUpdateNotifier::class, ExtensionApi::class)
        every { anyConstructed<AppUpdateNotifier>().promptUpdate(any()) } returns false
        coEvery { anyConstructed<ExtensionApi>().checkForUpdates(any(), any()) } returns emptyList()
    }

    @After
    fun cleanup() {
        unmockkObject(WorkManager.Companion)
        unmockkConstructor(AppUpdateNotifier::class, ExtensionApi::class)
        Injekt = previous
    }

    @Test
    fun `real update screen Download click enqueues the complete trusted metadata`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { Navigator(screen()) { CurrentScreen() } } }
            compose.onNodeWithText("Download").performClick()
            compose.runOnIdle {
                val input = request.captured.workSpec.input
                assertEquals(release.downloadLink, input.getString(AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL))
                assertEquals("a".repeat(64), input.getString(AppUpdateDownloadJob.EXTRA_EXPECTED_SHA256))
                assertEquals(123, input.getLong(AppUpdateDownloadJob.EXTRA_EXPECTED_SIZE, -1))
                assertEquals(code, input.getLong(AppUpdateDownloadJob.EXTRA_EXPECTED_VERSION_CODE, -1))
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `MainActivity update composable records versionCode only after a real Voyager push`() {
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NewUpdate(release)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val main = MainActivity()
        var navigator: Navigator? = null
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(HostScreen) {
                        navigator = it
                        main.CheckForUpdates()
                        CurrentScreen()
                    }
                }
            }
            compose.waitUntil(5000) { navigator?.lastItem is NewUpdateScreen }
            compose.runOnIdle {
                assertEquals(
                    code.toString(),
                    preferences.lastPromptedVersion(ReleaseOs.ANDROID, UpdateChannel.TESTING).get(),
                )
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `manual-only platform decision opens release page and exposes truncated notes without downloading`() {
        mockkStatic("eu.kanade.tachiyomi.data.updater.AppUpdateCheckerKt")
        every { canInstallForkUpdate(any(), any()) } returns false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { MaterialTheme { Navigator(screen(truncated = true)) { CurrentScreen() } } }
            compose.onNodeWithText("Full release notes are available on the release page.").assertIsDisplayed()
            compose.onNodeWithText(
                "This development build can open the release page; " +
                    "it cannot update the installed release app.",
            ).assertIsDisplayed()
            compose.onNodeWithText("Download").assertDoesNotExist()
            compose.onAllNodesWithText("Open on GitHub")[0].performClick()
            compose.runOnIdle {
                assertEquals(release.releaseLink, shadowOf(activity.get()).nextStartedActivity.dataString)
                verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
            }
        } finally {
            activity.pause().stop().destroy()
            unmockkStatic("eu.kanade.tachiyomi.data.updater.AppUpdateCheckerKt")
        }
    }

    private fun screen(truncated: Boolean = false) = NewUpdateScreen(
        release.version,
        release.info,
        release.releaseLink,
        release.downloadLink,
        expectedSha256 = "a".repeat(64),
        expectedSizeBytes = 123,
        expectedVersionCode = code,
        infoTruncated = truncated,
    )

    private object HostScreen : Screen {
        @Composable override fun Content() = Unit
    }
}
