package eu.kanade.tachiyomi.data.updater

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ApplicationUpdatePreferences

class AppUpdateCheckerCompatibilityTest {
    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `automatic prompts deduplicate successful delivery but manual checks remain visible`() = runTest {
        val release = Release("v2.0.0", "info", "https://example/release", "https://example/app.apk", versionCode = 999)
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NewUpdate(release)
        var notifications = 0
        val checker = AppUpdateChecker(
            interactor,
            notifyUpdate = { _, _ ->
                notifications++
                true
            },
            updatePreferences = ApplicationUpdatePreferences(InMemoryPreferenceStore()),
        )
        checker.checkForUpdate(context)
        checker.checkForUpdate(context)
        assertEquals(1, notifications)
        assertEquals(
            GetApplicationRelease.Result.NewUpdate(release),
            checker.checkForUpdate(context, forceCheck = true),
        )
        assertEquals(1, notifications)
    }

    @Test
    fun `failed notification is not recorded as already delivered`() = runTest {
        val release = Release("v2.0.0", "info", "https://example/release", "https://example/app.apk", versionCode = 999)
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NewUpdate(release)
        var notifications = 0
        val checker = AppUpdateChecker(interactor, notifyUpdate = { _, _ ->
            notifications++
            if (notifications == 1) error("notification failed")
            true
        }, updatePreferences = ApplicationUpdatePreferences(InMemoryPreferenceStore()))
        org.junit.jupiter.api.Assertions.assertTrue(runCatching { checker.checkForUpdate(context) }.isFailure)
        checker.checkForUpdate(context)
        assertEquals(2, notifications)
    }

    @Test
    fun `only installed formal release identity permits an in-app installation`() {
        org.junit.jupiter.api.Assertions.assertTrue(canInstallForkUpdate("app.mihon.desktop.fork", isDebug = false))
        org.junit.jupiter.api.Assertions.assertFalse(canInstallForkUpdate("app.mihon.desktop.fork.dev", isDebug = true))
        org.junit.jupiter.api.Assertions.assertFalse(canInstallForkUpdate("app.mihon.desktop.fork", isDebug = true))
    }

    @Test
    fun `no compatible package stays distinct without notification`() = runTest {
        val getApplicationRelease = mockk<GetApplicationRelease>()
        coEvery { getApplicationRelease.await(any()) } returns GetApplicationRelease.Result.NoCompatiblePackage
        var notifications = 0
        val checker = AppUpdateChecker(
            getApplicationRelease = getApplicationRelease,
            notifyUpdate = { _, _ ->
                notifications++
                true
            },
            updatePreferences = ApplicationUpdatePreferences(InMemoryPreferenceStore()),
        )

        val result = checker.checkForUpdate(context, forceCheck = true)

        assertSame(GetApplicationRelease.Result.NoCompatiblePackage, result)
        assertEquals(0, notifications)
    }

    @Test
    fun `existing non-update results pass through without notification`() = runTest {
        listOf(
            GetApplicationRelease.Result.NoNewUpdate,
            GetApplicationRelease.Result.OsTooOld,
        ).forEach { expected ->
            val getApplicationRelease = mockk<GetApplicationRelease>()
            coEvery { getApplicationRelease.await(any()) } returns expected
            var notifications = 0
            val checker = AppUpdateChecker(
                getApplicationRelease = getApplicationRelease,
                notifyUpdate = { _, _ ->
                    notifications++
                    true
                },
                updatePreferences = ApplicationUpdatePreferences(InMemoryPreferenceStore()),
            )

            assertSame(expected, checker.checkForUpdate(context))
            assertEquals(0, notifications)
        }
    }

    @Test
    fun `new update remains visible and triggers exactly one notification`() = runTest {
        val release = Release(
            version = "v2.0.0",
            info = "info",
            releaseLink = "https://example/release",
            downloadLink = "https://example/app.apk",
            versionCode = 999,
        )
        val expected = GetApplicationRelease.Result.NewUpdate(release)
        val getApplicationRelease = mockk<GetApplicationRelease>()
        coEvery { getApplicationRelease.await(any()) } returns expected
        val notified = mutableListOf<Release>()
        val checker = AppUpdateChecker(
            getApplicationRelease = getApplicationRelease,
            notifyUpdate = { _, update ->
                notified += update
                true
            },
            updatePreferences = ApplicationUpdatePreferences(InMemoryPreferenceStore()),
        )

        val result = checker.checkForUpdate(context)

        assertSame(expected, result)
        assertEquals(listOf(release), notified)
    }
}
