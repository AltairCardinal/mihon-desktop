package eu.kanade.tachiyomi.extension.installer

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import mihon.app.shizuku.IShellInterface
import mihon.app.shizuku.ShellInterface
import mihon.app.shizuku.ShizukuSessionState
import mihon.app.shizuku.shizukuResultIntent
import mihon.domain.extension.suggestion.SuggestionBatchPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShizukuTransactionResultTest {
    @Test
    fun `server abandons created session and closes descriptor when opening fails`() {
        val platform = mockk<PackageInstaller>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true) {
            every { packageManager.packageInstaller } returns platform
        }
        val raw = FailingOpenInstaller()
        val apk = mockk<android.content.res.AssetFileDescriptor>(relaxed = true)
        val shell = ShellInterface(0, context) { raw }
        val failure = assertThrows(Exception::class.java) { shell.prepare(apk, "opening") }
        assertTrue("Fixture must reach actual session creation", raw.created)
        assertEquals("open failed", failure.cause?.message)
        verify(exactly = 1) { platform.abandonSession(701) }
        verify(exactly = 1) { apk.close() }
    }

    @Test
    fun `server preserves write failure when closing also fails and still abandons session`() {
        val platform = mockk<PackageInstaller>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true) {
            every { packageManager.packageInstaller } returns platform
        }
        val raw = FailingOpenInstaller(failOpen = false)
        val apk = mockk<android.content.res.AssetFileDescriptor>(relaxed = true)
        val shell = ShellInterface(0, context) { raw }
        val failure = assertThrows(Exception::class.java) { shell.prepare(apk, "writing") }
        assertTrue("Fixture must reach actual session creation", raw.created)
        assertEquals("write failed", failure.cause?.message)
        assertEquals("close failed", failure.suppressed.single().cause?.message)
        verify(exactly = 1) { platform.abandonSession(701) }
        verify(exactly = 1) { apk.close() }
    }

    class FailingOpenInstaller(private val failOpen: Boolean = true) {
        var created = false

        @Suppress("UNUSED_PARAMETER")
        fun createSession(
            params: PackageInstaller.SessionParams,
            installer: String,
            attribution: String,
            userId: Int,
        ): Int {
            created = true
            return 701
        }

        @Suppress("UNUSED_PARAMETER")
        fun openSession(sessionId: Int): Any =
            if (failOpen) throw java.io.IOException("open failed") else FailingWriteSession()
    }

    class FailingWriteSession {
        @Suppress("UNUSED_PARAMETER")
        fun openWrite(name: String, offset: Long, length: Long): android.os.ParcelFileDescriptor =
            throw java.io.IOException("write failed")

        fun close(): Unit = throw java.io.IOException("close failed")
    }

    @Test
    fun `server recovery query does not authorize another commit and cleanup waits for platform callback`() {
        val platform = mockk<PackageInstaller>(relaxed = true)
        val callbacks = slot<PackageInstaller.SessionCallback>()
        every { platform.registerSessionCallback(capture(callbacks), any()) } just runs
        val info = mockk<PackageInstaller.SessionInfo> {
            every { installerPackageName } returns eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID
            every { isSealed } returns false
        }
        every { platform.getSessionInfo(701) } returns info
        val context = mockk<android.content.Context>(relaxed = true) {
            every { packageManager.packageInstaller } returns platform
        }
        val shell = ShellInterface(0, context) { throw AssertionError("Recovery must not open a new commit path") }
        assertEquals(ShizukuSessionState.PREPARED, shell.sessionState(701, "original"))
        assertThrows(IllegalStateException::class.java) { shell.commit(701, "original") }
        shell.abandon(701, "original")
        verify(exactly = 1) { platform.abandonSession(701) }
        verify(exactly = 0) { context.sendBroadcast(any()) }
        callbacks.captured.onFinished(701, false)
        callbacks.captured.onFinished(701, false)
        verify(exactly = 1) {
            context.sendBroadcast(
                match {
                    it.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) == "original" &&
                        it.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == 701 &&
                        it.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_FAILURE
                },
            )
        }
        assertEquals(ShizukuSessionState.FAILED, shell.sessionState(701, "original"))
    }

    @Test
    fun `server recovery refuses sessions attributed to another installer`() {
        val platform = mockk<PackageInstaller>(relaxed = true)
        every { platform.getSessionInfo(701) } returns mockk {
            every { installerPackageName } returns "another.application"
        }
        val context = mockk<android.content.Context>(relaxed = true) {
            every { packageManager.packageInstaller } returns platform
        }
        val shell = ShellInterface(0, context)
        assertEquals(ShizukuSessionState.UNKNOWN, shell.sessionState(701, "foreign"))
        assertThrows(IllegalStateException::class.java) { shell.abandon(701, "foreign") }
        verify(exactly = 0) { platform.abandonSession(any()) }
    }

    @Test
    fun `shell result pending intents have distinct identity and preserve original transaction`() {
        val first = shizukuResultIntent("app.mihon.eis.dev", "first")
        val second = shizukuResultIntent("app.mihon.eis.dev", "second")
        assertFalse("Extras alone do not distinguish PendingIntent identity", first.filterEquals(second))
        assertEquals("first", first.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID))
        assertEquals("app.mihon.eis.dev", first.`package`)
    }

    @Test
    fun `unavailable shizuku reports service pause for queued transaction`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        mockkStatic(Shizuku::class, ContextCompat::class)
        every { Shizuku.pingBinder() } returns false
        every { Shizuku.addBinderDeadListener(any()) } just runs
        every { Shizuku.removeBinderDeadListener(any()) } returns true
        every { Shizuku.removeRequestPermissionResultListener(any()) } returns true
        val app = RuntimeEnvironment.getApplication()
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns app
            every { packageName } returns app.packageName
            every { resources } returns app.resources
        }
        every { ContextCompat.registerReceiver(service, any(), any(), ContextCompat.RECEIVER_EXPORTED) } returns null
        try {
            val installer = ShizukuInstaller(service)
            installer.addToQueue("unavailable", Uri.parse("content://fixture/first"))
            installer.onDestroy()
            verify(exactly = 1) { manager.pauseInstall("unavailable", SuggestionBatchPause.SERVICE) }
            verify(exactly = 0) { manager.updateInstallStep("unavailable", InstallStep.Error) }
        } finally {
            unmockkStatic(Shizuku::class, ContextCompat::class)
            Injekt = previous
        }
    }

    @Test
    fun `denied shizuku permission reports permission pause`() = verifyInterruption(false)

    @Test
    fun `dead shizuku binder reports service pause`() = verifyInterruption(true)

    private fun verifyInterruption(dead: Boolean) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        mockkStatic(Shizuku::class, ContextCompat::class)
        val death = slot<Shizuku.OnBinderDeadListener>()
        val permission = slot<Shizuku.OnRequestPermissionResultListener>()
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_DENIED
        every { Shizuku.requestPermission(any()) } just runs
        every { Shizuku.addRequestPermissionResultListener(capture(permission)) } just runs
        every { Shizuku.addBinderDeadListener(capture(death)) } just runs
        every { Shizuku.removeBinderDeadListener(any()) } returns true
        every { Shizuku.removeRequestPermissionResultListener(any()) } returns true
        every { Shizuku.unbindUserService(any(), any(), any()) } just runs
        val app = RuntimeEnvironment.getApplication()
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns app
            every { packageName } returns app.packageName
        }
        every { ContextCompat.registerReceiver(service, any(), any(), ContextCompat.RECEIVER_EXPORTED) } returns null
        try {
            val installer = ShizukuInstaller(service)
            installer.addToQueue("interrupted", Uri.parse("content://fixture/first"))
            if (dead) {
                death.captured.onBinderDead()
            } else {
                permission.captured.onRequestPermissionResult(
                    14045,
                    PackageManager.PERMISSION_DENIED,
                )
            }
            installer.onDestroy()
            val reason = if (dead) SuggestionBatchPause.SERVICE else SuggestionBatchPause.PERMISSION
            verify(exactly = 1) { manager.pauseInstall("interrupted", reason) }
            verify(exactly = 0) { manager.updateInstallStep("interrupted", InstallStep.Error) }
        } finally {
            unmockkStatic(Shizuku::class, ContextCompat::class)
            Injekt = previous
        }
    }

    @Test
    fun `unknown and duplicate results never finish another shizuku transaction`() = verifyBound(false)

    @Test
    fun `new transaction protocol requests a new user service version`() = verifyBound(true)

    @Test
    fun `binder loss after submission keeps service alive for the actual package result`() = verifyBound(false, true)

    @Test
    fun `registered receiver filter accepts unique shell result uri`() = verifyBound(false, filterOnly = true)

    @Test
    fun `prepared session is acknowledged before one explicit commit`() = verifyBound(false, handshakeOnly = true)

    @Test
    fun `same transaction with a foreign session cannot finish the active install`() =
        verifyBound(false, sessionOnly = true)

    @Test
    fun `commit transport exception retains ownership for the actual result`() =
        verifyBound(false, commitFailure = true)

    @Test
    fun `bridge loss during preparation prevents a subsequent commit`() = verifyBound(false, prepareDisconnect = true)

    @Test
    fun `reconnection only queries the original sealed session without resubmitting`() =
        verifyBound(false, recoveryState = 2)

    @Test
    fun `reconnection abandons an unsealed original session and waits for its result`() =
        verifyBound(false, recoveryState = 1)

    @Test
    fun `missing original session without verified installed identity remains unknown`() =
        verifyBound(false, recoveryState = 0)

    @Test
    fun `permission failure before prepared acknowledgement pauses rather than advances`() =
        verifyBound(false, prepareFailure = SecurityException("Authorization revoked"))

    @Test
    fun `service failure before prepared acknowledgement pauses rather than advances`() =
        verifyBound(false, prepareFailure = android.os.RemoteException("Service lost"))

    @Test
    fun `callback without a platform status cannot complete the active transaction`() =
        verifyBound(false, missingStatus = true)

    private fun verifyBound(
        protocolOnly: Boolean,
        activeDeath: Boolean = false,
        filterOnly: Boolean = false,
        handshakeOnly: Boolean = false,
        sessionOnly: Boolean = false,
        commitFailure: Boolean = false,
        prepareDisconnect: Boolean = false,
        recoveryState: Int? = null,
        prepareFailure: Exception? = null,
        missingStatus: Boolean = false,
    ) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val manager = mockk<ExtensionManager>(relaxed = true)
        Injekt.addSingleton(manager)
        mockkStatic(Shizuku::class, ContextCompat::class)
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_GRANTED
        val boundArguments = slot<Shizuku.UserServiceArgs>()
        val connection = slot<ServiceConnection>()
        every { Shizuku.bindUserService(capture(boundArguments), capture(connection)) } just runs
        every { Shizuku.unbindUserService(any(), any(), any()) } just runs
        val death = slot<Shizuku.OnBinderDeadListener>()
        every { Shizuku.addBinderDeadListener(capture(death)) } just runs
        every { Shizuku.removeBinderDeadListener(any()) } returns true
        every { Shizuku.removeRequestPermissionResultListener(any()) } returns true
        val app = RuntimeEnvironment.getApplication()
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns app
            every { packageName } returns app.packageName
            every { contentResolver.openAssetFileDescriptor(any(), any()) } returns mockk(relaxed = true)
        }
        val receiver = slot<BroadcastReceiver>()
        val filter = slot<IntentFilter>()
        every {
            ContextCompat.registerReceiver(
                service,
                capture(receiver),
                capture(filter),
                ContextCompat.RECEIVER_EXPORTED,
            )
        } returns null
        val installer = ShizukuInstaller(service)
        val remote = mockk<IShellInterface>(relaxed = true)
        every { remote.prepare(any(), any()) } returns 701
        if (prepareFailure != null) every { remote.prepare(any(), any()) } throws prepareFailure
        if (recoveryState != null) {
            every { remote.sessionState(701, "first") } returns recoveryState
            coEvery { manager.verifyInstalledTransaction("first") } returns false
        }
        if (commitFailure) every { remote.commit(any(), any()) } throws android.os.RemoteException("Lost commit reply")
        if (prepareDisconnect) {
            every { remote.prepare(any(), any()) } answers {
                death.captured.onBinderDead()
                701
            }
        }
        ShizukuInstaller::class.java.getDeclaredField("shellInterface").apply {
            isAccessible = true
            set(installer, remote)
        }
        installer.ready = true
        fun result(id: String, sessionId: Int = 701, status: Int = PackageInstaller.STATUS_SUCCESS) =
            receiver.captured.onReceive(
                app,
                Intent(ACTION_INSTALL_RESULT)
                    .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, id)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    .putExtra(PackageInstaller.EXTRA_STATUS, status),
            )
        try {
            if (filterOnly) {
                val callback = shizukuResultIntent(app.packageName, "filter-transaction")
                assertTrue(
                    filter.captured.match(
                        callback.action,
                        null,
                        callback.scheme,
                        callback.data,
                        null,
                        "EIS",
                    ) >= 0,
                )
                return
            }
            if (protocolOnly) {
                val version = Shizuku.UserServiceArgs::class.java.getDeclaredField("versionCode").apply {
                    isAccessible = true
                }
                assertEquals(3, version.getInt(boundArguments.captured))
                return
            }
            installer.addToQueue("first", Uri.parse("content://fixture/first"))
            if (prepareFailure != null) {
                val reason = if (prepareFailure is SecurityException) {
                    SuggestionBatchPause.PERMISSION
                } else {
                    SuggestionBatchPause.SERVICE
                }
                verify(exactly = 1) { manager.pauseInstall("first", reason) }
                verify(exactly = 0) { manager.updateInstallStep("first", InstallStep.Error) }
                verify(exactly = 0) { remote.commit(any(), any()) }
                return
            }
            if (missingStatus) {
                receiver.captured.onReceive(
                    app,
                    Intent(ACTION_INSTALL_RESULT)
                        .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, "first")
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, 701),
                )
                verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
                result("first")
                verify(exactly = 1) { manager.updateInstallStep("first", InstallStep.Installed) }
                return
            }
            if (recoveryState != null) {
                death.captured.onBinderDead()
                val binder = mockk<android.os.IBinder> {
                    every { queryLocalInterface(any()) } returns remote
                }
                connection.captured.onServiceConnected(ComponentName(app, "ShellInterface"), binder)
                verify(exactly = 1) { remote.sessionState(701, "first") }
                verify(exactly = 1) { remote.commit(701, "first") }
                verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
                verify(exactly = 0) { service.stopSelf() }
                if (recoveryState == 1) {
                    verify(exactly = 1) { remote.abandon(701, "first") }
                    verify(exactly = 0) { manager.pauseInstall(any(), any()) }
                    result("first", status = PackageInstaller.STATUS_FAILURE_ABORTED)
                    verify(exactly = 1) { manager.pauseInstall("first", SuggestionBatchPause.SERVICE) }
                } else {
                    verify(exactly = 0) { remote.abandon(any(), any()) }
                    if (recoveryState == 0) coVerify(exactly = 1) { manager.verifyInstalledTransaction("first") }
                    result("first")
                    verify(exactly = 1) { manager.updateInstallStep("first", InstallStep.Installed) }
                }
                return
            }
            if (commitFailure) {
                verify(exactly = 1) { remote.commit(701, "first") }
                verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
                verify(exactly = 0) { service.stopSelf() }
                result("first")
                verify(exactly = 1) { manager.updateInstallStep("first", InstallStep.Installed) }
                return
            }
            if (prepareDisconnect) {
                verify(exactly = 1) { remote.prepare(any(), "first") }
                verify(exactly = 0) { remote.commit(any(), any()) }
                verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
                return
            }
            if (handshakeOnly) {
                verifyOrder {
                    remote.prepare(any(), "first")
                    remote.commit(701, "first")
                }
                verify(exactly = 1) { remote.commit(701, "first") }
                return
            }
            if (sessionOnly) {
                result("first", 702)
                verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
                result("first")
                verify(exactly = 1) { manager.updateInstallStep("first", InstallStep.Installed) }
                return
            }
            if (activeDeath) {
                death.captured.onBinderDead()
                verify(exactly = 0) { service.stopSelf() }
                verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
                result("first")
                verify(exactly = 1) { manager.updateInstallStep("first", InstallStep.Installed) }
                return
            }
            result("unrelated")
            verify(exactly = 0) { manager.updateInstallStep(any(), any()) }
            result("first")
            verify(exactly = 1) { manager.updateInstallStep("first", InstallStep.Installed) }
            installer.addToQueue("second", Uri.parse("content://fixture/second"))
            result("first")
            verify(exactly = 0) { manager.updateInstallStep("second", any()) }
            result("second")
            verify(exactly = 1) { manager.updateInstallStep("second", InstallStep.Installed) }
        } finally {
            installer.onDestroy()
            unmockkStatic(Shizuku::class, ContextCompat::class)
            Injekt = previous
        }
    }
}
