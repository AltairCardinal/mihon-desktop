package eu.kanade.tachiyomi.extension

import android.content.pm.PackageInstaller
import eu.kanade.tachiyomi.extension.util.PendingExtensionInstallSessions
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import mihon.domain.extension.service.ExtensionInstallArbiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingExtensionInstallSessionsTest {
    private val callback = slot<PackageInstaller.SessionCallback>()
    private val platform = mockk<PackageInstaller>(relaxed = true)
    private val arbiter = ExtensionInstallArbiter()
    private val refresh = Job()
    private var refreshes = 0
    private var snapshotReads = 0
    private val session = mockk<PackageInstaller.SessionInfo> {
        every { sessionId } returns 17
        every { appPackageName } returns "extension.pending"
        every { installerPackageName } returns "app.owner"
    }

    @org.junit.jupiter.api.BeforeEach fun androidHandlerBoundary() {
        io.mockk.mockkStatic(android.os.Looper::class)
        io.mockk.mockkConstructor(android.os.Handler::class)
        every { android.os.Looper.getMainLooper() } returns mockk()
    }

    @org.junit.jupiter.api.AfterEach fun clearHandlerBoundary() {
        io.mockk.unmockkStatic(android.os.Looper::class)
        io.mockk.unmockkConstructor(android.os.Handler::class)
    }
    init {
        every { platform.registerSessionCallback(capture(callback)) } just runs
        every { platform.registerSessionCallback(capture(callback), any()) } just runs
        every { platform.mySessions } answers {
            if (snapshotReads++ > 0) check(callback.isCaptured)
            listOf(session)
        }
        every { platform.getSessionInfo(17) } returns session
    }
    private fun recover() = PendingExtensionInstallSessions(
        platform,
        "app.owner",
        arbiter,
    ) {
        refreshes++
        refresh
    }.also { it.start() }

    @Test fun `startup reserves existing session and terminal waits for inventory before exact release`() {
        recover()
        assertTrue(arbiter.isBusy("extension.pending"))
        callback.captured.onFinished(99, true)
        assertTrue(arbiter.isBusy("extension.pending"))
        callback.captured.onFinished(17, true)
        assertEquals(1, refreshes)
        assertTrue(arbiter.isBusy("extension.pending"))
        refresh.complete()
        assertFalse(arbiter.isBusy("extension.pending"))
        val next = arbiter.reserveSystemWindow("extension.pending")!!
        callback.captured.onFinished(17, false)
        assertTrue(arbiter.isBusy("extension.pending"))
        assertTrue(arbiter.releaseSystemWindow(next))
        verify(exactly = 0) { platform.openSession(any()) }
        verify(exactly = 0) { platform.abandonSession(any()) }
    }

    @Test fun `completion during initial snapshot cannot leave a stale reservation`() {
        every { platform.mySessions } answers {
            if (snapshotReads++ > 0) {
                check(callback.isCaptured)
                callback.captured.onFinished(17, true)
            }
            listOf(session)
        }
        recover()
        verify(exactly = 1) { platform.registerSessionCallback(any(), any()) }
        assertFalse(arbiter.isBusy("extension.pending"))
    }

    @Test fun `foreign installer is never adopted or mutated`() {
        every { session.installerPackageName } returns "other.owner"
        recover()
        verify(exactly = 1) { platform.registerSessionCallback(any(), any()) }
        assertFalse(arbiter.isBusy("extension.pending"))
        verify(exactly = 0) { platform.openSession(any()) }
        verify(exactly = 0) { platform.abandonSession(any()) }
    }

    @Test fun `two initial sessions for one package retain exclusion until both finish`() {
        val second = mockk<PackageInstaller.SessionInfo> {
            every { sessionId } returns 18
            every { appPackageName } returns "extension.pending"
            every { installerPackageName } returns "app.owner"
        }
        every { platform.mySessions } returns listOf(session, second)
        every { platform.getSessionInfo(18) } returns second
        recover()
        assertTrue(arbiter.isBusy("extension.pending"))
        callback.captured.onFinished(17, true)
        refresh.complete()
        assertTrue(arbiter.isBusy("extension.pending"))
        callback.captured.onFinished(18, false)
        assertFalse(arbiter.isBusy("extension.pending"))
    }

    @Test fun `registration explicitly selects main callback handler rather than caller looper`() {
        recover()
        verify(exactly = 1) { platform.registerSessionCallback(any(), any()) }
    }

    @Test fun `unknown package remains an explicit inventory uncertainty until its real terminal`() {
        every { session.appPackageName } returns null
        val recovery = recover()
        assertTrue(recovery.hasUnknownSessions)
        callback.captured.onFinished(17, false)
        assertFalse(recovery.hasUnknownSessions)
        assertEquals(1, refreshes)
    }

    @Test fun `manager exposes initial unknown session as uncertain inventory`() = kotlinx.coroutines.test.runTest {
        every { session.appPackageName } returns null
        val context = mockk<android.content.Context>(relaxed = true) {
            every { packageName } returns "app.owner"
            every { packageManager.packageInstaller } returns platform
        }
        val manager = ExtensionManager(
            context = context,
            preferences = mockk(relaxed = true),
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = { emptyList() },
            installReceiverRegistrar = {},
            scope = backgroundScope,
            inventoryProvider = { mihon.domain.extension.suggestion.ExtensionInventory(initialized = true) },
        )
        runCurrent()
        assertTrue(manager.inventory.value.initialized)
        assertTrue(manager.inventory.value.hasUnknownArtifacts)
        callback.captured.onFinished(17, false)
        runCurrent()
        assertFalse(manager.inventory.value.hasUnknownArtifacts)
    }
}
