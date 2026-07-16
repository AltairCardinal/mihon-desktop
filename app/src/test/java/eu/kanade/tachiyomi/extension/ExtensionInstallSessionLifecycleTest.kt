package eu.kanade.tachiyomi.extension

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.installer.PackageInstallerInstaller
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.service.ExtensionInstallPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalAtomicApi::class)
class ExtensionInstallSessionLifecycleTest {

    @BeforeEach
    fun interceptLocalBroadcastManager() {
        mockkStatic(LocalBroadcastManager::class)
        mockkStatic(ContextCompat::class)
        every { LocalBroadcastManager.getInstance(any()) } returns mockk(relaxed = true)
        every {
            ContextCompat.registerReceiver(any(), any(), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        } returns null
    }

    @AfterEach
    fun restoreLocalBroadcastManager() {
        unmockkStatic(LocalBroadcastManager::class)
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `success error and abort each publish one terminal`() {
        listOf(
            PackageInstaller.STATUS_SUCCESS to InstallStep.Installed,
            PackageInstaller.STATUS_FAILURE to InstallStep.Error,
            PackageInstaller.STATUS_FAILURE_ABORTED to InstallStep.Idle,
        ).forEach { (status, expected) ->
            val harness = packageInstallerHarness()

            harness.activate(TRANSACTION_ONE, SESSION_ONE)
            harness.callback(status, TRANSACTION_ONE, SESSION_ONE)
            harness.callback(status, TRANSACTION_ONE, SESSION_ONE)

            verify(exactly = 1) { harness.manager.updateInstallStep(TRANSACTION_ONE, expected) }
        }
    }

    @Test
    fun `pending user action is accepted only for the active transaction and session`() {
        val harness = packageInstallerHarness()
        harness.activate(TRANSACTION_ONE, SESSION_ONE)

        harness.callback(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            transactionId = TRANSACTION_TWO,
            sessionId = SESSION_ONE,
        )
        harness.callback(
            PackageInstaller.STATUS_PENDING_USER_ACTION,
            transactionId = TRANSACTION_ONE,
            sessionId = SESSION_TWO,
        )

        verify(exactly = 0) { harness.service.startActivity(any()) }
        verify(exactly = 0) { harness.manager.updateInstallStep(any(), any()) }
        assertTrue(harness.isActive(TRANSACTION_ONE, SESSION_ONE))
    }

    @Test
    fun `late callback from a same-package retry cannot terminate the new transaction`() {
        val harness = packageInstallerHarness()
        harness.activate(TRANSACTION_ONE, SESSION_ONE)
        harness.callback(PackageInstaller.STATUS_FAILURE_ABORTED, TRANSACTION_ONE, SESSION_ONE)
        harness.activate(TRANSACTION_TWO, SESSION_TWO)

        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_ONE, SESSION_ONE)

        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_TWO, InstallStep.Installed) }
        assertTrue(harness.isActive(TRANSACTION_TWO, SESSION_TWO))
    }

    @Test
    fun `callback must match both active transaction and session`() {
        val harness = packageInstallerHarness()
        harness.activate(TRANSACTION_ONE, SESSION_ONE)

        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_TWO, SESSION_ONE)
        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_ONE, SESSION_TWO)

        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Installed) }
        assertTrue(harness.isActive(TRANSACTION_ONE, SESSION_ONE))
    }

    @Test
    fun `cancel before enqueue leaves a tombstone and never processes the entry`() {
        val harness = queueHarness()

        harness.cancel(TRANSACTION_ONE)
        harness.installer.addToQueue(TRANSACTION_ONE, mockk())

        assertEquals(emptyList<String>(), harness.processed)
    }

    @Test
    fun `cancelling active package session abandons it and publishes idle exactly once`() {
        val harness = packageInstallerHarness()
        harness.activate(TRANSACTION_ONE, SESSION_ONE)

        harness.cancelActive()
        harness.callback(PackageInstaller.STATUS_SUCCESS, TRANSACTION_ONE, SESSION_ONE)

        verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
        verify(exactly = 1) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Idle) }
        verify(exactly = 0) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Installed) }
    }

    @Test
    fun `service destroy without callback abandons the session and publishes one error terminal`() {
        val harness = packageInstallerHarness()
        harness.activate(TRANSACTION_ONE, SESSION_ONE)

        harness.installer.onDestroy()

        verify(exactly = 1) { harness.packageInstaller.abandonSession(SESSION_ONE) }
        verify(exactly = 1) { harness.manager.updateInstallStep(TRANSACTION_ONE, InstallStep.Error) }
    }

    @Test
    fun `transaction identity is UUID based and does not collide for Java hash collisions`() = runTest {
        val firstPackage = "extension.Aa"
        val secondPackage = "extension.BB"
        assertEquals(firstPackage.hashCode(), secondPackage.hashCode())
        val installer = ExtensionInstaller(
            context = mockk(relaxed = true),
            scope = backgroundScope,
            installPort = mockk<ExtensionInstallPort>(relaxed = true),
        )

        installer.downloadAndInstall("https://repo.example/aa.apk", availableExtension(firstPackage))
        installer.downloadAndInstall("https://repo.example/bb.apk", availableExtension(secondPackage))

        val transactions = activeTransactionIds(installer)
        val first = checkNotNull(transactions[firstPackage])
        val second = checkNotNull(transactions[secondPackage])

        UUID.fromString(first)
        UUID.fromString(second)
        assertNotEquals(first, second)
        assertFalse(first == firstPackage.hashCode().toString())
        assertFalse(second == secondPackage.hashCode().toString())
    }

    @Test
    fun `platform wait times out when PackageInstaller never calls back`() = runTest {
        val installer = ExtensionInstaller(
            context = mockk(relaxed = true),
            scope = backgroundScope,
            installPort = mockk<ExtensionInstallPort>(relaxed = true),
        )
        val waiting = async {
            runCatching { awaitPlatformResult(installer, CompletableDeferred()) }.exceptionOrNull()
        }

        runCurrent()
        advanceTimeBy(2 * 60 * 1000L)
        runCurrent()

        assertTrue(waiting.isCompleted)
        assertInstanceOf(TimeoutCancellationException::class.java, waiting.await())
    }

    private fun activeTransactionIds(installer: ExtensionInstaller): Map<String, String> {
        val field = ExtensionInstaller::class.java.getDeclaredField("activeTransactions").apply { isAccessible = true }
        val active = field.get(installer) as Map<*, *>
        return active.mapValues { (_, value) ->
            checkNotNull(value).javaClass.getDeclaredField("transactionId").apply { isAccessible = true }
                .get(value) as String
        }.mapKeys { (key, _) -> key as String }
    }

    private suspend fun awaitPlatformResult(
        installer: ExtensionInstaller,
        result: CompletableDeferred<InstallStep>,
    ): InstallStep = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = ExtensionInstaller::class.java.getDeclaredMethod(
            "awaitPlatformResult",
            CompletableDeferred::class.java,
            kotlin.coroutines.Continuation::class.java,
        ).apply { isAccessible = true }
        val returned = method.invoke(installer, result, continuation)
        @Suppress("UNCHECKED_CAST")
        if (returned === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else returned as InstallStep
    }

    private fun availableExtension(packageName: String) =
        Extension.Available(
            name = packageName,
            pkgName = packageName,
            versionName = "1.0",
            versionCode = 1,
            libVersion = 1.4,
            lang = "en",
            isNsfw = false,
            sources = emptyList(),
            apkName = "$packageName.apk",
            iconUrl = "https://repo.example/icon.png",
            repoUrl = "https://repo.example",
        )

    private fun packageInstallerHarness(): PackageHarness {
        val packageInstaller = mockk<PackageInstaller>(relaxed = true)
        val packageManager = mockk<PackageManager> {
            every { this@mockk.packageInstaller } returns packageInstaller
        }
        val service = mockk<Service>(relaxed = true) {
            every { this@mockk.packageManager } returns packageManager
            every { applicationContext } returns this@mockk
        }
        val manager = mockk<ExtensionManager>(relaxed = true)
        val installer = PackageInstallerInstaller(service)
        Installer::class.java.getDeclaredField("extensionManager\$delegate").apply {
            isAccessible = true
            set(installer, lazyOf(manager))
        }
        return PackageHarness(service, packageInstaller, manager, installer)
    }

    private fun queueHarness(): QueueHarness {
        val service = mockk<Service>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }
        val processed = mutableListOf<String>()
        val installer = object : Installer(service) {
            override var ready = true

            override fun processEntry(entry: Entry) {
                processed += entry.transactionId
            }
        }
        return QueueHarness(installer, processed)
    }

    private class PackageHarness(
        val service: Service,
        val packageInstaller: PackageInstaller,
        val manager: ExtensionManager,
        val installer: PackageInstallerInstaller,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun activate(transactionId: String, sessionId: Int) {
            val entry = Installer.Entry(transactionId, mockk<Uri>())
            Installer::class.java.getDeclaredField("waitingInstall").apply {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (get(installer) as AtomicReference<Installer.Entry?>).store(entry)
            }
            val activeType = PackageInstallerInstaller::class.java.declaredClasses.single {
                it.simpleName == "ActiveSession"
            }
            val active = activeType.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(entry, sessionId)
            val reference = PackageInstallerInstaller::class.java.getDeclaredField("activeSession").apply {
                isAccessible = true
            }.get(installer) as AtomicReference<Any?>
            reference.store(active)
        }

        @Suppress("DEPRECATION")
        fun callback(
            status: Int,
            transactionId: String,
            sessionId: Int,
            userAction: Intent? = null,
        ) {
            val intent = mockk<Intent>(relaxed = true) {
                every {
                    getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                } returns status
                every { getIntExtra(PackageInstaller.EXTRA_SESSION_ID, any()) } returns sessionId
                every { getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) } returns transactionId
                every { getParcelableExtra<Intent>(Intent.EXTRA_INTENT) } returns userAction
            }
            receiver().onReceive(service, intent)
        }

        fun cancelActive() {
            PackageInstallerInstaller::class.java.getDeclaredMethod("cancelEntry", Installer.Entry::class.java)
                .invoke(installer, activeEntry())
            Installer::class.java.getDeclaredMethod("cancelQueue", String::class.java)
                .apply { isAccessible = true }
                .invoke(installer, TRANSACTION_ONE)
        }

        fun isActive(transactionId: String, sessionId: Int): Boolean {
            val reference = PackageInstallerInstaller::class.java.getDeclaredField("activeSession").apply {
                isAccessible = true
            }.get(installer) as AtomicReference<*>
            val active = reference.load() ?: return false
            val type = active.javaClass
            val entry = type.getDeclaredField("entry").apply { isAccessible = true }.get(active) as Installer.Entry
            val activeSessionId = type.getDeclaredField("sessionId").apply { isAccessible = true }.getInt(active)
            return entry.transactionId == transactionId && activeSessionId == sessionId
        }

        private fun activeEntry(): Installer.Entry {
            val reference = PackageInstallerInstaller::class.java.getDeclaredField("activeSession").apply {
                isAccessible = true
            }.get(installer) as AtomicReference<*>
            val active = checkNotNull(reference.load())
            return active.javaClass.getDeclaredField("entry").apply { isAccessible = true }
                .get(active) as Installer.Entry
        }

        private fun receiver(): BroadcastReceiver = PackageInstallerInstaller::class.java
            .getDeclaredField("packageActionReceiver")
            .apply { isAccessible = true }
            .get(installer) as BroadcastReceiver
    }

    private class QueueHarness(
        val installer: Installer,
        val processed: List<String>,
    ) {
        fun cancel(transactionId: String) {
            Installer::class.java.getDeclaredMethod("cancelQueue", String::class.java)
                .apply { isAccessible = true }
                .invoke(installer, transactionId)
        }
    }

    private companion object {
        const val SESSION_ONE = 101
        const val SESSION_TWO = 202
        val TRANSACTION_ONE: String = UUID.randomUUID().toString()
        val TRANSACTION_TWO: String = UUID.randomUUID().toString()
    }
}
