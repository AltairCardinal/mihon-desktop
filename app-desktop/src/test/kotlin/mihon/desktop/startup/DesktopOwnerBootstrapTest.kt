package mihon.desktop.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.startProductionDesktopApplication
import mihon.desktop.test.state.applicationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.File

/**
 * The window has to be created before the dependency graph is ready, otherwise the
 * application stays invisible while the container is being built.
 */
@Isolated
class DesktopOwnerBootstrapTest {

    @Test
    fun `diagnostic launch flag is configured before the production window event loop`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val property = "mihon.reader.renderTraceDir"
        val previous = System.getProperty(property)
        System.clearProperty(property)
        val requested = File(tempDir, "render-capture").absolutePath
        val entered = CompletableDeferred<String?>()
        val startup = async {
            startProductionDesktopApplication(
                args = arrayOf("--reader-render-trace=$requested"),
                broker = mihon.desktop.platform.DesktopExternalActionBroker(File(tempDir, "trace-owner.json")),
                registrar = mihon.desktop.platform.DesktopUriSchemeRegistrar {
                    mihon.desktop.platform.DesktopUriSchemeRegistration.Result.Unavailable(
                        mihon.desktop.platform.DesktopUriSchemeRegistration.UnavailableReason.NON_PACKAGED_RUNTIME,
                    )
                },
                ownerIngressDependencies = { kotlinx.coroutines.awaitCancellation() },
                windowGeometryStore = { InMemoryGeometryStore() },
                runWindowEventLoop = { _, close ->
                    entered.complete(System.getProperty(property))
                    close()
                },
            )
        }
        try {
            assertEquals(requested, withTimeout(5_000) { entered.await() })
        } finally {
            startup.cancel()
            runCatching { withTimeout(10_000) { startup.await() } }
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `the window event loop starts while the dependency graph is still building`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val stateFile = File(tempDir, "bootstrap-window.json")
        val ownerReady = CompletableDeferred<Unit>()
        val windowEntered = CompletableDeferred<Unit>()
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = NoopService,
            localSourceScanService = NoopService,
            autoBackupScheduler = NoopService,
            startupCleanup = {},
        )

        val startup = async {
            startProductionDesktopApplication(
                args = emptyArray(),
                broker = mihon.desktop.platform.DesktopExternalActionBroker(stateFile),
                registrar = mihon.desktop.platform.DesktopUriSchemeRegistrar {
                    mihon.desktop.platform.DesktopUriSchemeRegistration.Result.Unavailable(
                        mihon.desktop.platform.DesktopUriSchemeRegistration.UnavailableReason.NON_PACKAGED_RUNTIME,
                    )
                },
                ownerIngressDependencies = {
                    ownerReady.await()
                    // The owner never publishes its user interface in this test. The
                    // dependency graph would need a fully populated container, and the
                    // subject of the test is the window that starts before it.
                    kotlinx.coroutines.awaitCancellation()
                },
                windowGeometryStore = { InMemoryGeometryStore() },
                runWindowEventLoop = { _, requestClose ->
                    windowEntered.complete(Unit)
                    requestClose()
                },
            )
        }

        try {
            withTimeout(5_000) { windowEntered.await() }
            assertFalse(ownerReady.isCompleted, "the window must not wait for the dependency graph")
        } finally {
            ownerReady.complete(Unit)
            startup.cancel()
            runCatching { withTimeout(10_000) { startup.await() } }
            runtime.closeAndJoin()
        }
    }

    @Test
    fun `the class warm up starts before the instance broker is elected`(
        @TempDir tempDir: File,
    ) = runBlocking {
        // The classes the first user interface needs are read while the process starts.
        // The broker election opens the local socket and costs a noticeable part of
        // the start-up, so the warm up has to be in flight before it.
        val recordedBefore = mihon.desktop.startup.DesktopStartupTrace.recorded().size
        val stateFile = File(tempDir, "bootstrap-warmup.json")
        val windowEntered = CompletableDeferred<Unit>()

        val startup = async {
            startProductionDesktopApplication(
                args = emptyArray(),
                broker = mihon.desktop.platform.DesktopExternalActionBroker(stateFile),
                registrar = mihon.desktop.platform.DesktopUriSchemeRegistrar {
                    mihon.desktop.platform.DesktopUriSchemeRegistration.Result.Unavailable(
                        mihon.desktop.platform.DesktopUriSchemeRegistration.UnavailableReason.NON_PACKAGED_RUNTIME,
                    )
                },
                ownerIngressDependencies = { kotlinx.coroutines.awaitCancellation() },
                windowGeometryStore = { InMemoryGeometryStore() },
                runWindowEventLoop = { _, requestClose ->
                    windowEntered.complete(Unit)
                    requestClose()
                },
            )
        }

        try {
            withTimeout(5_000) { windowEntered.await() }
            val phases = mihon.desktop.startup.DesktopStartupTrace.recorded()
                .drop(recordedBefore)
                .map { it.phase }
            val warmUpIndex = phases.indexOf("warmup-start")
            val brokerIndex = phases.indexOf("broker-owner")
            assertTrue(warmUpIndex >= 0, "the class warm up did not start: $phases")
            assertTrue(brokerIndex >= 0, "the instance broker was not elected: $phases")
            assertTrue(
                warmUpIndex < brokerIndex,
                "the class warm up started after the broker election: $phases",
            )
        } finally {
            startup.cancel()
            runCatching { withTimeout(10_000) { startup.await() } }
        }
    }

    @Test
    fun `a prepared owner is published to the window`() {
        val bootstrap = DesktopOwnerBootstrapController(applicationState)
        assertTrue(bootstrap.state.value is DesktopOwnerBootstrapState.Preparing)

        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = NoopService,
            localSourceScanService = NoopService,
            autoBackupScheduler = NoopService,
            startupCleanup = {},
        )
        val preferenceNode = java.util.prefs.Preferences.userRoot()
            .node("/mihon-test/bootstrap/${java.util.UUID.randomUUID()}")
        try {
            val preferences = eu.kanade.tachiyomi.core.security.SecurityPreferences(
                tachiyomi.core.common.preference.DesktopPreferenceStore(preferenceNode),
            ).apply {
                useAuthenticator().set(false)
                lockAppAfter().set(0)
            }
            val appLock = mihon.desktop.security.DesktopAppLock(
                preferences,
                mihon.desktop.security.DesktopPassphraseVerifier(
                    mihon.desktop.platform.DesktopCredentialStore(MemoryCredentialBackend()),
                ),
            )
            bootstrap.publishRuntime(runtime, appLock)

            val ready = bootstrap.state.value
            assertTrue(ready is DesktopOwnerBootstrapState.Ready)
            assertSame(runtime, (ready as DesktopOwnerBootstrapState.Ready).runtime)
            assertNull(ready.owner, "a runtime only preparation has no user interface owner")
        } finally {
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `a bootstrap failure is delivered to the waiter`() = runBlocking {
        val bootstrap = DesktopOwnerBootstrapController(applicationState)
        val failure = IllegalStateException("dependency graph failed")
        bootstrap.publishFailure(failure)

        val thrown = runCatching { bootstrap.awaitOwner() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals("dependency graph failed", thrown?.message)
    }

    private object NoopService : mihon.desktop.DesktopRuntimeService {
        override fun start() = Unit
        override fun stop() = Unit
    }

    /** Geometry store that never touches the user preferences. */
    private class InMemoryGeometryStore : mihon.desktop.platform.DesktopWindowGeometryStore {
        private var value: String = ""

        override fun read(): String = value

        override fun write(value: String): Boolean {
            this.value = value
            return true
        }
    }

    /** Credential backend that keeps the secret in memory instead of the operating system. */
    private class MemoryCredentialBackend : mihon.desktop.platform.CredentialBackend {
        private var secret: CharArray? = null

        override fun save(account: String, secret: CharArray) {
            this.secret?.fill('\u0000')
            this.secret = secret.copyOf()
        }

        override fun load(account: String): CharArray? = secret?.copyOf()

        override fun delete(account: String) {
            secret?.fill('\u0000')
            secret = null
        }
    }
}
