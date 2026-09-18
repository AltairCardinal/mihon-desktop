package eu.kanade.tachiyomi.extension.permission

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class InstalledAppsPermissionAppModuleTest {
    @Test
    fun `AppModule binds a single permission controller to the production extension manager`() = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val app = mockk<Application>(relaxed = true)
        val pm = mockk<PackageManager>()
        var manager: ExtensionManager? = null
        every { pm.packageInstaller } returns mockk { every { mySessions } returns emptyList() }
        var permission = PackageManager.PERMISSION_DENIED
        every { app.packageManager } returns pm
        every { app.packageName } returns "app.test"
        every { pm.getPermissionInfo(any(), 0) } returns PermissionInfo()
        every { pm.getPackageInfo("app.test", PackageManager.GET_PERMISSIONS) } returns PackageInfo().apply {
            requestedPermissions = arrayOf(AndroidInstalledAppsPermissionDetector.PERMISSION)
        }
        every { pm.checkPermission(AndroidInstalledAppsPermissionDetector.PERMISSION, "app.test") } answers
            { permission }
        val preferences = mockk<SourcePreferences>(relaxed = true) {
            every { enabledLanguages() } returns mockk<Preference<Set<String>>> {
                every { isSet() } returns true
            }
        }
        Injekt.addSingleton(
            mockk<mihon.domain.extensionrepo.interactor.GetExtensionRepo> {
                every { subscribeAll() } returns kotlinx.coroutines.flow.emptyFlow()
            },
        )
        Injekt.addSingleton(SecurityPreferences(InMemoryPreferenceStore()))
        Injekt.addSingleton(preferences)
        Injekt.addSingleton(mockk<TrustExtension>(relaxed = true))
        mockkStatic(ContextCompat::class)
        mockkObject(ExtensionLoader)
        val installed = Extension.Installed(
            "Example", "extension.test", "1", 1, 1.4, "en", false, null, emptyList(), null, isShared = true,
        )
        every { ContextCompat.getMainExecutor(app) } returns Executor { }
        every { ContextCompat.registerReceiver(eq(app), any(), any(), any()) } returns null
        every { ExtensionLoader.scanInventory(app) } returns
            mihon.domain.extension.suggestion.ExtensionInventory(initialized = true)
        coEvery { ExtensionLoader.loadExtensions(app, false) } returns emptyList()
        coEvery { ExtensionLoader.loadExtensions(app, true) } returns listOf(LoadResult.Success(installed))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            Injekt.importModule(AppModule(app))
            val controller = Injekt.get<InstalledAppsPermissionController>()
            val resolved = Injekt.get<ExtensionManager>()
            manager = resolved
            assertSame(controller, Injekt.get<InstalledAppsPermissionController>())
            withTimeout(5_000) { resolved.isInitialized.first { it } }
            withTimeout(5_000) {
                controller.state.first { it.status == InstalledAppsPermissionStatus.DENIED && !it.isRefreshing }
            }
            permission = PackageManager.PERMISSION_GRANTED
            val owner = mockk<LifecycleOwner>()
            val lifecycle = LifecycleRegistry.createUnsafe(owner)
            every { owner.lifecycle } returns lifecycle
            lifecycle.currentState = Lifecycle.State.STARTED
            App().onStart(owner)
            withTimeout(5_000) { resolved.installedExtensionsFlow.first { it.isNotEmpty() } }
            withTimeout(5_000) {
                controller.state.first { it.status == InstalledAppsPermissionStatus.GRANTED && !it.isRefreshing }
            }
            lifecycle.currentState = Lifecycle.State.DESTROYED
            assertEquals(listOf(installed), resolved.installedExtensionsFlow.value)
        } finally {
            manager?.scope?.coroutineContext?.get(Job)?.cancelAndJoin()
            Dispatchers.resetMain()
            unmockkObject(ExtensionLoader)
            unmockkStatic(ContextCompat::class)
            Injekt = previous
        }
    }
}
