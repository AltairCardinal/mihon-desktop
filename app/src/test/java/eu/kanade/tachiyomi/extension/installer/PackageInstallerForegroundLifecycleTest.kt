package eu.kanade.tachiyomi.extension.installer

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import mihon.domain.extension.suggestion.SuggestionBatchPause
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PackageInstallerForegroundLifecycleTest {
    @Test
    fun `cancellation during foreground recovery waits for actual session cleanup`() = withInstaller { harness ->
        harness.pendingConfirmation()
        val cancelled = Installer.cancelInstallQueue(harness.service, transactionId)
        assertFalse(cancelled.isCompleted)
        verify(exactly = 0) { harness.service.stopSelf() }
        harness.finished(SESSION, false)
        verify(exactly = 1) { harness.service.stopSelf() }
        assertFalse(cancelled.isCompleted)
        harness.destroy()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `missing confirmation intent retains ownership until cleanup`() = withInstaller { harness ->
        harness.result(PackageInstaller.STATUS_PENDING_USER_ACTION, null)
        verify(exactly = 1) { harness.platform.abandonSession(SESSION) }
        verify(exactly = 0) { harness.manager.pauseInstall(any(), any()) }
        verify(exactly = 0) { harness.manager.updateInstallStep(transactionId, InstallStep.Error) }
        harness.finished(SESSION, false)
        verify(exactly = 1) { harness.manager.pauseInstall(transactionId, SuggestionBatchPause.APP_FOREGROUND) }
    }

    @Test
    fun `confirmation intent without action retains ownership until cleanup`() = withInstaller { harness ->
        harness.result(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent())
        verify(exactly = 1) { harness.platform.abandonSession(SESSION) }
        verify(exactly = 0) { harness.manager.pauseInstall(any(), any()) }
        harness.finished(SESSION, false)
        verify(exactly = 1) { harness.manager.pauseInstall(transactionId, SuggestionBatchPause.APP_FOREGROUND) }
    }

    @Test
    fun `background confirmation pauses only after matching session cleanup`() = withInstaller { harness ->
        harness.pendingConfirmation()
        verify(exactly = 0) { harness.service.startActivity(any()) }
        verify(exactly = 1) { harness.platform.abandonSession(SESSION) }
        verify(exactly = 0) { harness.manager.pauseInstall(any(), any()) }
        harness.finished(SESSION + 1, false)
        verify(exactly = 0) { harness.manager.pauseInstall(any(), any()) }
        harness.finished(SESSION, false)
        harness.finished(SESSION, false)
        verify(exactly = 1) { harness.manager.pauseInstall(transactionId, SuggestionBatchPause.APP_FOREGROUND) }
        verify(exactly = 1) { harness.service.stopSelf() }
        verify(exactly = 0) { harness.manager.updateInstallStep(transactionId, InstallStep.Idle) }
    }

    @Test
    fun `actual success wins over pending foreground recovery`() = withInstaller { harness ->
        harness.pendingConfirmation()
        verify(exactly = 1) { harness.platform.abandonSession(SESSION) }
        harness.finished(SESSION, true)
        harness.result(PackageInstaller.STATUS_SUCCESS)
        harness.finished(SESSION, false)
        verify(exactly = 1) { harness.manager.updateInstallStep(transactionId, InstallStep.Installed) }
        verify(exactly = 0) { harness.manager.pauseInstall(transactionId, any()) }
    }

    @Test
    fun `foreground activity launch rejection waits for session cleanup before pause`() = withInstaller(
        foreground = true,
    ) { harness ->
        every { harness.service.startActivity(any()) } throws SecurityException("Activity launch rejected")
        harness.pendingConfirmation()
        verify(exactly = 1) { harness.platform.abandonSession(SESSION) }
        verify(exactly = 0) { harness.manager.pauseInstall(any(), any()) }
        harness.finished(SESSION, false)
        verify(exactly = 1) { harness.manager.pauseInstall(transactionId, SuggestionBatchPause.APP_FOREGROUND) }
    }

    private fun withInstaller(foreground: Boolean = false, block: (Harness) -> Unit) {
        transactionId = java.util.UUID.randomUUID().toString()
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        mockkStatic(ContextCompat::class, UniFile::class)
        mockkObject(ProcessLifecycleOwner.Companion)
        val owner = mockk<LifecycleOwner>()
        val lifecycle = LifecycleRegistry.createUnsafe(owner)
        every { owner.lifecycle } returns lifecycle
        lifecycle.currentState = if (foreground) Lifecycle.State.STARTED else Lifecycle.State.CREATED
        every { ProcessLifecycleOwner.get() } returns owner
        val app = RuntimeEnvironment.getApplication()
        val platform = mockk<PackageInstaller>(relaxed = true)
        val callback = slot<PackageInstaller.SessionCallback>()
        every { platform.registerSessionCallback(capture(callback)) } answers { Unit }
        val session = mockk<PackageInstaller.Session>(relaxed = true)
        every { platform.createSession(any()) } returns SESSION
        every { platform.openSession(SESSION) } returns session
        every { session.openWrite(any(), any(), any()) } returns ByteArrayOutputStream()
        val packageManager = mockk<PackageManager> {
            every { packageInstaller } returns platform
        }
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns app
            every { packageName } returns app.packageName
            every { this@mockk.packageManager } returns packageManager
            every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        }
        every { UniFile.fromUri(any(), any()) } returns mockk {
            every { length() } returns 1L
        }
        val receiver = slot<BroadcastReceiver>()
        every {
            ContextCompat.registerReceiver(service, capture(receiver), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        } returns null
        var installer: PackageInstallerInstaller? = null
        try {
            installer = PackageInstallerInstaller(service)
            installer.addToQueue(transactionId, Uri.parse("content://eis/foreground.apk"))
            verify(exactly = 1) { session.commit(any()) }
            block(
                Harness(
                    service,
                    platform,
                    manager,
                    receiver.captured,
                    finished = { id, success -> callback.captured.onFinished(id, success) },
                    destroy = { checkNotNull(installer).onDestroy() },
                ),
            )
        } finally {
            installer?.onDestroy()
            unmockkStatic(ContextCompat::class, UniFile::class)
            unmockkObject(ProcessLifecycleOwner.Companion)
            Injekt = previous
        }
    }

    private class Harness(
        val service: Service,
        val platform: PackageInstaller,
        val manager: ExtensionManager,
        private val receiver: BroadcastReceiver,
        val finished: (Int, Boolean) -> Unit,
        val destroy: () -> Unit,
    ) {
        fun pendingConfirmation() = result(PackageInstaller.STATUS_PENDING_USER_ACTION)

        fun result(
            status: Int,
            userAction: Intent? = Intent("android.content.pm.action.CONFIRM_INSTALL")
                .setPackage("com.android.packageinstaller")
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, SESSION),
        ) {
            receiver.onReceive(
                service,
                Intent("PackageInstallerInstaller.INSTALL_ACTION")
                    .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, transactionId)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, SESSION)
                    .putExtra(PackageInstaller.EXTRA_STATUS, status)
                    .putExtra(Intent.EXTRA_INTENT, userAction),
            )
        }
    }

    private companion object {
        const val SESSION = 8421
        var transactionId = "foreground-session"
    }
}
