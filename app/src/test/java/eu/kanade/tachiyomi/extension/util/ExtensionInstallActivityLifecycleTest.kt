package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionInstallActivityLifecycleTest {
    @Test
    fun `restored installation uses original UUID and package without dispatching another system window`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { restoreInstallWindow("original-window", "owned.extension") } returns true
        }
        Injekt.addSingleton(manager)
        val intent = Intent().setDataAndType(Uri.parse("content://fixture/original"), ExtensionInstaller.APK_MIME)
            .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "original-window")
            .putExtra(ExtensionInstaller.EXTRA_PACKAGE_NAME, "owned.extension")
        try {
            val controller = Robolectric.buildActivity(
                ExtensionInstallActivity::class.java,
                intent,
            ).create(Bundle()).start()
            verify(exactly = 1) { manager.restoreInstallWindow("original-window", "owned.extension") }
            assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
            assertFalse(controller.get().isFinishing)
            val callback = ExtensionInstallActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            ).apply { isAccessible = true }
            callback.invoke(controller.get(), 500, Activity.RESULT_OK, null)
            verify(exactly = 1) { manager.completeInstallWindow("original-window", InstallStep.Installed) }
            controller.stop().destroy()
        } finally {
            Injekt = previous
        }
    }

    @Test
    fun `uninstall result bridge preserves actual success for rollback owner`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:owned.extension"))
            .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "rollback-removal")
        val controller = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).create().start()
        try {
            val callback = ExtensionInstallActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            ).apply { isAccessible = true }
            callback.invoke(controller.get(), 500, Activity.RESULT_OK, null)
            callback.invoke(controller.get(), 500, Activity.RESULT_CANCELED, null)
            verify(exactly = 1) { manager.completeUninstall("rollback-removal", Activity.RESULT_OK) }
            verify(exactly = 0) { manager.completeUninstall("rollback-removal", Activity.RESULT_CANCELED) }
        } finally {
            controller.stop().destroy()
            Injekt = previous
        }
    }

    @Test
    fun `service rejects a different installer without cancelling submitted owner`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        val service = Robolectric.buildService(ExtensionInstallService::class.java).get()
        val existing = mockk<eu.kanade.tachiyomi.extension.installer.ShizukuInstaller>(relaxed = true)
        ExtensionInstallService::class.java.getDeclaredField("installer").apply {
            isAccessible = true
            set(service, existing)
        }
        try {
            val intent = ExtensionInstallService.getIntent(
                service,
                "new-package",
                Uri.parse("content://fixture/new"),
                eu.kanade.domain.base.BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
            )
            service.onStartCommand(intent, 0, 2)
            verify(exactly = 1) {
                manager.pauseInstall(
                    "new-package",
                    mihon.domain.extension.suggestion.SuggestionBatchPause.INSTALLER_CHANGED,
                )
            }
            verify(exactly = 0) { existing.addToQueue(any(), any()) }
            verify(exactly = 0) { existing.onDestroy() }
        } finally {
            Injekt = previous
        }
    }

    @Test
    fun `restored system uninstall waits with original owner without launching again`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { restoreUninstall("restored-removal", "owned.extension") } returns true
        }
        Injekt.addSingleton(manager)
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:owned.extension"))
            .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "restored-removal")
        try {
            val controller = Robolectric.buildActivity(
                ExtensionInstallActivity::class.java,
                intent,
            ).create(Bundle()).start()
            verify(exactly = 1) { manager.restoreUninstall("restored-removal", "owned.extension") }
            assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
            assertFalse(controller.get().isFinishing)
            controller.stop().destroy()
        } finally {
            Injekt = previous
        }
    }

    @Test
    fun `production uninstall dispatch uses result bridge and retains original transaction`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "owned.extension"
                applicationInfo = ApplicationInfo().apply { packageName = "owned.extension" }
            },
        )
        val installer = ExtensionInstaller(application, installPort = mockk(relaxed = true))
        assertTrue(installer.uninstallApk("owned.extension", "owned-removal"))
        val launched = shadowOf(application).nextStartedActivity
        assertEquals(ExtensionInstallActivity::class.java.name, launched.component?.className)
        assertEquals("owned-removal", launched.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID))
        assertEquals(Intent.ACTION_UNINSTALL_PACKAGE, launched.action)
    }

    @Test
    fun `system installer launch failure reports terminal error and closes bridge`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        try {
            val intent = Intent().putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "unlaunchable")
            val controller = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).create()
            verify(exactly = 1) { manager.completeInstallWindow("unlaunchable", InstallStep.Error) }
            assertTrue(controller.get().isFinishing)
            controller.destroy()
        } finally {
            shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false)
            Injekt = previous
        }
    }

    @Test
    fun `uninstall bridge dispatches uninstall and routes cancellation to original removal`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:owned.extension"))
            .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "owned-removal")
        val controller = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).create().start()
        try {
            assertEquals(
                Intent.ACTION_UNINSTALL_PACKAGE,
                shadowOf(controller.get()).nextStartedActivityForResult.intent.action,
            )
            val callback = ExtensionInstallActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            ).apply { isAccessible = true }
            callback.invoke(controller.get(), 500, Activity.RESULT_CANCELED, null)
            verify(exactly = 1) { manager.completeUninstall("owned-removal") }
            verify(exactly = 0) { manager.completeInstallWindow(any(), any()) }
        } finally {
            controller.stop().destroy()
            Injekt = previous
        }
    }

    @Test
    fun `unrelated results do not finish bridge and repeated matching results complete only once`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        val intent = Intent().putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "owned-request")
        val controller = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).create().start()
        try {
            val callback = ExtensionInstallActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            ).apply { isAccessible = true }
            callback.invoke(controller.get(), 999, Activity.RESULT_OK, null)
            assertFalse("Unrelated result cannot close the active request", controller.get().isFinishing)
            callback.invoke(controller.get(), 500, Activity.RESULT_OK, null)
            callback.invoke(controller.get(), 500, Activity.RESULT_CANCELED, null)
            verify(exactly = 1) { manager.completeInstallWindow("owned-request", InstallStep.Installed) }
            verify(exactly = 0) { manager.completeInstallWindow("owned-request", InstallStep.Idle) }
        } finally {
            controller.stop().destroy()
            Injekt = previous
        }
    }

    @Test
    fun `configuration recreation retains the delivery uri until actual completion`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(
            mockk<ExtensionManager>(relaxed = true) {
                every { restoreInstallWindow("owned-request", "owned.extension") } returns true
            },
        )
        try {
            val provider = RecordingProvider()
            ShadowContentResolver.registerProviderInternal("eis.delivery", provider)
            val intent = Intent().setDataAndType(
                Uri.parse("content://eis.delivery/apk"),
                "application/vnd.android.package-archive",
            )
                .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "owned-request")
                .putExtra(ExtensionInstaller.EXTRA_PACKAGE_NAME, "owned.extension")
            val controller = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).setup()
            val changed = Configuration(controller.get().resources.configuration)
            changed.orientation = if (changed.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
            controller.configurationChange(changed)
            assertEquals("Rotation must not consume the system installer's file", 0, provider.deleted)
            controller.pause().stop().destroy()
        } finally {
            Injekt = previous
        }
    }

    private class RecordingProvider : ContentProvider() {
        var deleted = 0
        override fun onCreate() = true
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleted++
            return 1
        }
        override fun getType(uri: Uri) = "application/vnd.android.package-archive"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?,
        ): Cursor? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test
    fun `restoring a dispatched system request does not launch a second installer`() {
        val intent = Intent().putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "owned-request")
        val first = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).create().start()
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, shadowOf(first.get()).nextStartedActivityForResult.intent.action)
        val saved = Bundle()
        first.saveInstanceState(saved)
        val restored = Robolectric.buildActivity(ExtensionInstallActivity::class.java, intent).create(saved).start()
        assertNull(
            "A restored bridge must await its original system result",
            shadowOf(restored.get()).nextStartedActivityForResult,
        )
        restored.stop().destroy()
        first.stop().destroy()
    }
}
