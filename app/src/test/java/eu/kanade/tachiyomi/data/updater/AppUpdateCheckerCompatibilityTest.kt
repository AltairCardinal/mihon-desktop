package eu.kanade.tachiyomi.data.updater

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release

class AppUpdateCheckerCompatibilityTest {
    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `no compatible package preserves Android no-update behavior without notification`() = runTest {
        val getApplicationRelease = mockk<GetApplicationRelease>()
        coEvery { getApplicationRelease.await(any()) } returns GetApplicationRelease.Result.NoCompatiblePackage
        var notifications = 0
        val checker = AppUpdateChecker(
            getApplicationRelease = getApplicationRelease,
            notifyUpdate = { _, _ -> notifications++ },
        )

        val result = checker.checkForUpdate(context, forceCheck = true)

        assertSame(GetApplicationRelease.Result.NoNewUpdate, result)
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
                notifyUpdate = { _, _ -> notifications++ },
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
        )
        val expected = GetApplicationRelease.Result.NewUpdate(release)
        val getApplicationRelease = mockk<GetApplicationRelease>()
        coEvery { getApplicationRelease.await(any()) } returns expected
        val notified = mutableListOf<Release>()
        val checker = AppUpdateChecker(
            getApplicationRelease = getApplicationRelease,
            notifyUpdate = { _, update -> notified += update },
        )

        val result = checker.checkForUpdate(context)

        assertSame(expected, result)
        assertEquals(listOf(release), notified)
    }
}
