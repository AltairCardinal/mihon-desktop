package eu.kanade.tachiyomi.data.updater

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.util.system.updaterEnabled
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ForkAppUpdateWiringTest {
    private lateinit var previous: InjektScope
    private lateinit var application: Application

    @Before
    fun setup() {
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        application = RuntimeEnvironment.getApplication()
        Injekt.importModule(PreferenceModule(application))
    }

    @After
    fun cleanup() {
        Injekt = previous
    }

    @Test
    fun `production preference DI defaults enable self updates and testing without upstream updater or telemetry`() {
        val preferences = Injekt.get<ApplicationUpdatePreferences>()
        assertTrue(preferences.enabled().get())
        assertTrue(preferences.testingChannel().get())
        assertTrue(updaterEnabled)
        assertFalse(BuildConfig.UPDATER_ENABLED)
        assertFalse(BuildConfig.TELEMETRY_INCLUDED)
    }

    @Test
    fun `checker passes own repository version and trusted package identity from release configuration`() = runTest {
        val captured = slot<GetApplicationRelease.Arguments>()
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(capture(captured)) } returns GetApplicationRelease.Result.NoCompatiblePackage
        val checker = AppUpdateChecker(interactor, notifyUpdate = { _, _ -> error("unexpected notification") })

        assertEquals(GetApplicationRelease.Result.NoCompatiblePackage, checker.checkForUpdate(application, true))
        assertEquals(ForkUpdateRequest.REPOSITORY, captured.captured.repository)
        val fork = requireNotNull(captured.captured.forkUpdate)
        assertEquals(UpdateChannel.TESTING, fork.channel)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), fork.installedVersionCode)
        assertEquals(BuildConfig.FORK_RELEASE_PACKAGE, fork.expectedApplicationId)
        assertEquals(BuildConfig.FORK_RELEASE_CERTIFICATE_SHA256, fork.expectedCertificateSha256)
    }

    @Test
    fun `disabled automatic checks do not call network but manual checks honor selected stable channel`() = runTest {
        val preferences = Injekt.get<ApplicationUpdatePreferences>()
        preferences.enabled().set(false)
        preferences.testingChannel().set(false)
        val interactor = mockk<GetApplicationRelease>()
        coEvery { interactor.await(any()) } returns GetApplicationRelease.Result.NoNewUpdate
        val checker = AppUpdateChecker(interactor, notifyUpdate = { _, _ -> error("unexpected notification") })

        checker.checkForUpdate(application)
        coVerify(exactly = 0) { interactor.await(any()) }
        checker.checkForUpdate(application, forceCheck = true)
        coVerify(exactly = 1) {
            interactor.await(match { it.forceCheck && it.forkUpdate?.channel == UpdateChannel.STABLE })
        }
    }
}
