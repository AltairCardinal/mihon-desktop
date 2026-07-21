package mihon.desktop.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.platform.PlatformCredentialException
import mihon.desktop.platform.PlatformCredentialUnavailableException
import mihon.domain.security.AuthenticationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore

class DesktopAppLockTest {
    private val preferenceNodes = mutableListOf<Preferences>()

    @AfterEach
    fun cleanPreferences() = preferenceNodes.forEach { runCatching { it.removeNode() } }

    @Test
    fun `first launch locks and positive delay persists then clears shared close time`() {
        var now = 1_000L
        val preferences = preferences(enabled = true, delay = 5)
        val verifier = verifier().also { it.set("secret".toCharArray()) }
        val lock = DesktopAppLock(preferences, verifier) { now }

        val preferenceNode = preferenceNodes.single()
        assertEquals(setOf("lock_app_after", "use_biometric_lock"), preferenceNode.keys().toSet())
        assertEquals(setOf("5", "true"), preferenceNode.keys().map { preferenceNode.get(it, "") }.toSet())
        assertTrue(lock.state.value.requiresUnlock)
        assertEquals(AuthenticationResult.Success, lock.authenticate("secret".toCharArray()))
        lock.onApplicationStopped()
        assertEquals(now, preferences.lastAppClosed().get())
        now += 299_999
        lock.onApplicationStarted()
        assertFalse(lock.state.value.requiresUnlock)
        assertEquals(0L, preferences.lastAppClosed().get())
        lock.onApplicationStopped()
        now += 300_000
        lock.onApplicationStarted()
        assertTrue(lock.state.value.requiresUnlock)
    }

    @Test
    fun `never and immediate delays follow shared policy after unlock`() {
        val preferences = preferences(enabled = true, delay = -1)
        val lock = DesktopAppLock(preferences, verifier().also { it.set("secret".toCharArray()) }) { 10L }
        lock.authenticate("secret".toCharArray())
        lock.onApplicationStopped()
        lock.onApplicationStarted()
        assertFalse(lock.state.value.requiresUnlock)

        preferences.lockAppAfter().set(0)
        lock.onApplicationStopped()
        lock.onApplicationStarted()
        assertTrue(lock.state.value.requiresUnlock)
    }

    @Test
    fun `non success authentication remains fail closed`() {
        val backend = MemoryBackend("secret".toCharArray())
        val lock = DesktopAppLock(
            preferences(enabled = true, delay = 0),
            DesktopPassphraseVerifier(DesktopCredentialStore(backend)),
        ) { 0L }

        assertEquals(AuthenticationResult.Failed, lock.authenticate("wrong".toCharArray()))
        assertEquals(AuthenticationResult.Cancelled, lock.authenticate(null))
        backend.failure = PlatformCredentialUnavailableException(OperatingSystem.WINDOWS)
        assertEquals(AuthenticationResult.Unavailable, lock.authenticate("secret".toCharArray()))
        backend.failure = PlatformCredentialException("failed")
        assertEquals(AuthenticationResult.Error, lock.authenticate("secret".toCharArray()))
        assertTrue(lock.state.value.requiresUnlock)
    }

    @Test
    fun `passphrase lifecycle clears inputs and serializes verification`() {
        val backend = MemoryBackend()
        val verifier = DesktopPassphraseVerifier(DesktopCredentialStore(backend))
        val initial = "initial".toCharArray()
        assertEquals(AuthenticationResult.Success, verifier.set(initial))
        assertTrue(initial.all { it == '\u0000' })
        val wrong = "wrong".toCharArray()
        val rejectedReplacement = "rejected".toCharArray()
        assertEquals(AuthenticationResult.Failed, verifier.reset(wrong, rejectedReplacement))
        assertTrue(wrong.all { it == '\u0000' })
        assertTrue(rejectedReplacement.all { it == '\u0000' })
        val current = "initial".toCharArray()
        val replacement = "new".toCharArray()
        assertEquals(AuthenticationResult.Success, verifier.reset(current, replacement))
        assertTrue(current.all { it == '\u0000' })
        assertTrue(replacement.all { it == '\u0000' })

        backend.loadDelayMillis = 40
        val attempts = List(2) { "new".toCharArray() }
        Executors.newFixedThreadPool(2).use { pool ->
            val results = attempts.map { attempt -> pool.submit<AuthenticationResult> { verifier.verify(attempt) } }
                .map { it.get() }
            assertEquals(listOf(AuthenticationResult.Success, AuthenticationResult.Success), results)
            assertEquals(1, backend.maxConcurrentLoads.get())
        }
        assertTrue(attempts.all { attempt -> attempt.all { it == '\u0000' } })
        assertTrue(backend.loadedCopies.all { loaded -> loaded.all { it == '\u0000' } })
        assertEquals(AuthenticationResult.Success, verifier.delete())
        val deletedAttempt = "new".toCharArray()
        assertEquals(AuthenticationResult.Failed, verifier.verify(deletedAttempt))
        assertTrue(deletedAttempt.all { it == '\u0000' })
    }

    @Test
    fun `runtime forwards lifecycle to app lock`() {
        val lock = RecordingLockLifecycle()
        val noop = object : DesktopRuntimeService { override fun start() = Unit; override fun stop() = Unit }
        val runtime = DesktopAppRuntime(noop, noop, noop, startupCleanup = {}, appLock = lock)
        val closedRuntime = DesktopAppRuntime(noop, noop, noop, startupCleanup = {}, appLock = lock)

        runtime.start()
        runtime.stop()
        closedRuntime.start()
        closedRuntime.close()

        assertEquals(2, lock.starts)
        assertEquals(2, lock.stops)
    }

    @Test
    fun `runtime close completes every cleanup and preserves failures when app lock stop fails`(
        @TempDir tempDir: File,
    ) {
        val events = mutableListOf<String>()
        val appLockFailure = IllegalStateException("app lock stop failed")
        val batchFailure = IllegalStateException("batch stop failed")
        val backupFailure = IllegalStateException("backup stop failed")
        val libraryFailure = IllegalStateException("library stop failed")
        val scopeJob = SupervisorJob()
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = cleanupService("library", events, libraryFailure),
            localSourceScanService = cleanupService("local", events),
            autoBackupScheduler = cleanupService("backup", events, backupFailure),
            trackerSyncScheduler = cleanupService("tracker", events),
            batchMigrationController = cleanupService("batch", events, batchFailure),
            startupCleanup = {},
            scope = CoroutineScope(scopeJob),
            appLock = object : DesktopAppLockLifecycle {
                override fun onApplicationStarted() = Unit
                override fun onApplicationStopped() {
                    events += "appLock"
                    throw appLockFailure
                }
            },
        )
        val stateFile = File(tempDir, "instance.json")
        val broker = DesktopExternalActionBroker(stateFile)
        assertTrue(broker.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        runtime.attachInstanceBroker(broker)
        runtime.start()

        val thrown = assertThrows(IllegalStateException::class.java, runtime::close)

        assertSame(appLockFailure, thrown)
        assertEquals(
            listOf("appLock", "batch", "tracker", "backup", "local", "library"),
            events,
        )
        assertEquals(listOf(batchFailure, backupFailure, libraryFailure), thrown.suppressed.toList())
        assertFalse(runtime.isRunning)
        assertFalse(scopeJob.isActive)
        assertFalse(stateFile.exists())
    }

    private fun preferences(enabled: Boolean, delay: Int): SecurityPreferences {
        val node = Preferences.userRoot().node("/mihon-test/app-lock/${UUID.randomUUID()}")
        preferenceNodes += node
        return SecurityPreferences(DesktopPreferenceStore(node)).apply {
            useAuthenticator().set(enabled)
            lockAppAfter().set(delay)
        }
    }

    private fun verifier() = DesktopPassphraseVerifier(DesktopCredentialStore(MemoryBackend()))

    private fun cleanupService(
        name: String,
        events: MutableList<String>,
        failure: RuntimeException? = null,
    ) = object : DesktopRuntimeService {
        override fun start() = Unit
        override fun stop() {
            events += name
            failure?.let { throw it }
        }
    }

    private class RecordingLockLifecycle : DesktopAppLockLifecycle {
        var starts = 0
        var stops = 0
        override fun onApplicationStarted() {
            starts++
        }

        override fun onApplicationStopped() {
            stops++
        }
    }

    private class MemoryBackend(initial: CharArray? = null) : CredentialBackend {
        @Volatile private var secret = initial?.copyOf()
        @Volatile var failure: RuntimeException? = null
        @Volatile var loadDelayMillis = 0L
        private val activeLoads = AtomicInteger()
        val maxConcurrentLoads = AtomicInteger()
        val loadedCopies = CopyOnWriteArrayList<CharArray>()

        override fun save(account: String, secret: CharArray) {
            failure?.let { throw it }
            this.secret = secret.copyOf()
        }

        override fun load(account: String): CharArray? {
            failure?.let { throw it }
            val active = activeLoads.incrementAndGet()
            maxConcurrentLoads.updateAndGet { maxOf(it, active) }
            try {
                if (loadDelayMillis > 0) Thread.sleep(loadDelayMillis)
                return secret?.copyOf()?.also(loadedCopies::add)
            } finally {
                activeLoads.decrementAndGet()
            }
        }

        override fun delete(account: String) {
            failure?.let { throw it }
            secret?.fill('\u0000')
            secret = null
        }
    }
}
