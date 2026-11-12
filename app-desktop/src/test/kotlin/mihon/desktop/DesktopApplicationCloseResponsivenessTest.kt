package mihon.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.platform.DesktopExternalActionBroker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopApplicationCloseResponsivenessTest {
    @Test
    fun `reopening cannot acquire the profile while shutdown is still saving`(@TempDir tempDir: File) = runBlocking {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        val contender = DesktopExternalActionBroker(stateFile)
        val checkpointStarted = CompletableDeferred<Unit>()
        val releaseCheckpoint = CompletableDeferred<Unit>()
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
            override suspend fun awaitStopped() {
                checkpointStarted.complete(Unit)
                releaseCheckpoint.await()
            }
        }
        val runtime = DesktopAppRuntime(service, service, service, startupCleanup = {})
        var delivered = 0
        owner.setActionConsumer { delivered++ }
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        runtime.attachInstanceBroker(owner)
        runtime.start()
        val closing = async(Dispatchers.Default) { runtime.closeAndJoin() }
        try {
            withTimeout(5_000) { checkpointStarted.await() }
            runtime.close()
            val rejected = DesktopExternalActionBroker.StartResult.Failed(
                DesktopExternalActionBroker.Failure.ProtocolRejected,
            )
            assertEquals(rejected, contender.startOrForward(null))
            assertEquals(rejected, contender.startOrForward("mihon://manga/late"))
            assertEquals(0, delivered)
        } finally {
            contender.close()
            releaseCheckpoint.complete(Unit)
            withTimeout(5_000) { closing.await() }
            owner.close()
        }
        DesktopExternalActionBroker(stateFile).use { reopened ->
            assertTrue(reopened.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        }
    }

    @Test
    fun `window close is published before awaiting cleanup and recovery keeps its window`() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val coordinator = DesktopApplicationCloseCoordinator({
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
        }, null)
        val closing = async { coordinator() }
        try {
            cleanupStarted.await()
            assertTrue(coordinator.closing.value)
            assertFalse(closing.isCompleted)
        } finally {
            releaseCleanup.complete(Unit)
            closing.await()
        }
        val recovery = DesktopApplicationCloseCoordinator({}, null)
        recovery.stopForRecovery()
        assertFalse(recovery.closing.value)
    }

    @Test
    fun `failed broker release after joining can be retried by ordinary close`(@TempDir tempDir: File) = runBlocking {
        val stateFile = File(tempDir, "retry-instance.json")
        var attempts = 0
        val owner = DesktopExternalActionBroker(stateFile, closeServer = { server ->
            attempts++
            if (attempts == 1) error("release failed")
            server.close()
        })
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        val runtime = DesktopAppRuntime(service, service, service, startupCleanup = {})
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        runtime.attachInstanceBroker(owner)
        try {
            assertEquals("release failed", runCatching { runtime.closeAndJoin() }.exceptionOrNull()?.message)
            runtime.close()
            assertEquals(2, attempts)
            DesktopExternalActionBroker(stateFile).use { reopened ->
                assertTrue(reopened.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun `window close keeps UI responsive while runtime saves its checkpoint`() = runBlocking {
        val checkpointStarted = CompletableDeferred<Unit>()
        val releaseCheckpoint = CountDownLatch(1)
        var exits = 0
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
            override suspend fun awaitStopped() {
                checkpointStarted.complete(Unit)
                check(releaseCheckpoint.await(10, TimeUnit.SECONDS))
            }
        }
        val noop = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = noop,
            localSourceScanService = noop,
            autoBackupScheduler = noop,
            syncService = service,
            startupCleanup = {},
        ).also(DesktopAppRuntime::start)
        val coordinator = DesktopApplicationCloseCoordinator(runtime::closeAndJoin, null)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val closing = async(ui) {
                val uiThread = Thread.currentThread()
                coordinator {
                    assertEquals(uiThread, Thread.currentThread())
                    exits++
                }
            }
            try {
                withTimeout(5_000) { checkpointStarted.await() }
                val uiEvent = async(ui) { "responsive" }
                assertEquals("responsive", withTimeout(1_000) { uiEvent.await() })
                assertFalse(closing.isCompleted, "Checkpoint cleanup must still finish before process exit")
                assertEquals(0, exits)
            } finally {
                releaseCheckpoint.countDown()
                withTimeout(5_000) { closing.await() }
            }
        }
        assertEquals(1, exits)
    }
}
