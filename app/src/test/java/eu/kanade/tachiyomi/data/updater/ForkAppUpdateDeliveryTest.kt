package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ApplicationUpdatePreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ForkAppUpdateDeliveryTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val release = Release(
        "0.19.4",
        "notes",
        "https://github.com/AltairCardinal/mihon-desktop/releases/tag/android-vc91",
        "https://github.com/AltairCardinal/mihon-desktop/releases/download/android-vc91/app.apk",
        versionCode = 91,
    )

    @Test
    fun `unavailable notifications do not deduplicate until a page was actually presented`() = runTest {
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NewUpdate(release)
        var attempts = 0
        fun checker() = AppUpdateChecker(interactor, { _, _ ->
            attempts++
            false
        }, preferences)
        assertTrue(checker().checkForUpdate(application) is GetApplicationRelease.Result.NewUpdate)
        assertTrue(checker().checkForUpdate(application) is GetApplicationRelease.Result.NewUpdate)
        assertEquals(2, attempts)
        checker().markPresented(release)
        assertEquals(GetApplicationRelease.Result.NoNewUpdate, checker().checkForUpdate(application))
        assertEquals(2, attempts)
    }

    @Test
    fun `same display name with a higher versionCode is a distinct automatic prompt`() = runTest {
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returnsMany listOf(
            GetApplicationRelease.Result.NewUpdate(release),
            GetApplicationRelease.Result.NewUpdate(release),
            GetApplicationRelease.Result.NewUpdate(release.copy(versionCode = 92)),
        )
        var attempts = 0
        val checker = AppUpdateChecker(interactor, { _, _ ->
            attempts++
            true
        }, preferences)
        repeat(3) { checker.checkForUpdate(application) }
        assertEquals(2, attempts)
    }

    @Test
    fun `automatic result is discarded when preferences change during discovery`() = runTest {
        listOf(false, true).forEach { disable ->
            val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val interactor = mockk<GetApplicationRelease>()
            coEvery { interactor.await(any()) } coAnswers {
                entered.complete(Unit)
                resume.await()
                GetApplicationRelease.Result.NewUpdate(release)
            }
            var attempts = 0
            val checker = AppUpdateChecker(interactor, { _, _ ->
                attempts++
                true
            }, preferences)
            val checking = async { checker.checkForUpdate(application) }
            entered.await()
            if (disable) preferences.enabled().set(false) else preferences.testingChannel().set(false)
            resume.complete(Unit)
            assertEquals(GetApplicationRelease.Result.NoNewUpdate, checking.await())
            assertEquals(0, attempts)
        }
    }

    @Test
    fun `presentation records the channel used for discovery`() = runTest {
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NewUpdate(release)
        val checker = AppUpdateChecker(interactor, { _, _ -> true }, preferences)
        checker.checkForUpdate(application, forceCheck = true)
        preferences.testingChannel().set(false)
        checker.markPresented(release)
        assertEquals(
            "91",
            preferences.lastPromptedVersion(
                tachiyomi.domain.release.model.ReleaseOs.ANDROID,
                tachiyomi.domain.release.model.UpdateChannel.TESTING,
            ).get(),
        )
        assertEquals(
            "",
            preferences.lastPromptedVersion(
                tachiyomi.domain.release.model.ReleaseOs.ANDROID,
                tachiyomi.domain.release.model.UpdateChannel.STABLE,
            ).get(),
        )
    }

    @Test
    fun `manual check returns available release without notifying or marking automatic presentation`() = runTest {
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NewUpdate(release)
        var attempts = 0
        val checker = AppUpdateChecker(interactor, { _, _ ->
            attempts++
            true
        }, preferences)
        assertTrue(checker.checkForUpdate(application, forceCheck = true) is GetApplicationRelease.Result.NewUpdate)
        assertEquals(0, attempts)
        checker.checkForUpdate(application)
        assertEquals(1, attempts)
    }

    @Test
    fun `notifier reports false when system notifications are unavailable`() {
        val manager = application.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(AppUpdateNotifier(application).promptUpdate(release))
    }

    @Test
    fun `retry notification and receiver preserve verified download identity`() {
        val manager = application.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(Notifications.CHANNEL_APP_UPDATE, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val checksum = "a".repeat(64)
        AppUpdateNotifier(application).onDownloadError(release.downloadLink, release.version, checksum, 123, 91)
        val notification = requireNotNull(shadowOf(manager).getNotification(Notifications.ID_APP_UPDATE_ERROR))
        val intent = shadowOf(notification.actions.first().actionIntent).savedIntent
        assertEquals(checksum, intent.getStringExtra(AppUpdateDownloadJob.EXTRA_EXPECTED_SHA256))
        assertEquals(123, intent.getLongExtra(AppUpdateDownloadJob.EXTRA_EXPECTED_SIZE, -1))
        assertEquals(91, intent.getLongExtra(AppUpdateDownloadJob.EXTRA_EXPECTED_VERSION_CODE, -1))
        mockkObject(AppUpdateDownloadJob.Companion)
        try {
            every { AppUpdateDownloadJob.start(any(), any(), any(), any(), any(), any()) } just Runs
            NotificationReceiver().onReceive(application, intent)
            verify(exactly = 1) {
                AppUpdateDownloadJob.start(application, release.downloadLink, release.version, checksum, 123, 91)
            }
        } finally {
            unmockkObject(AppUpdateDownloadJob.Companion)
        }
    }
}
