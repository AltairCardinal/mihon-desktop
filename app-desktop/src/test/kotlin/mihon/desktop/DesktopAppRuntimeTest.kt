package mihon.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.test.TestArguments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAppRuntimeTest {

    @Test
    fun `start launches services and startup cleanup without blocking caller`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val library = RecordingRuntimeService()
        val local = RecordingRuntimeService()
        val backup = RecordingRuntimeService()
        var cleanupCalls = 0
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = library,
            localSourceScanService = local,
            autoBackupScheduler = backup,
            startupCleanup = { cleanupCalls++ },
            scope = scope,
        )

        runtime.start()

        assertTrue(library.started)
        assertTrue(local.started)
        assertTrue(backup.started)
        assertEquals(0, cleanupCalls)

        advanceUntilIdle()

        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `stop stops services and marks runtime stopped`() = runTest {
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = RecordingRuntimeService(),
            localSourceScanService = RecordingRuntimeService(),
            autoBackupScheduler = RecordingRuntimeService(),
            startupCleanup = {},
            scope = this,
        )

        runtime.start()
        runtime.stop()

        assertFalse(runtime.isRunning)
    }

    @Test
    fun `headless test mode waits for server termination then closes runtime`() = runBlocking {
        val runtime = headlessRuntime().also(DesktopAppRuntime::start)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        var stopCalls = 0
        val handled = async(Dispatchers.Default) {
            runHeadlessMode(
                args = TestArguments(testMode = true, headless = true),
                runtime = runtime,
                awaitTestModeTermination = {
                    waiting.countDown()
                    release.await()
                },
                stopTestMode = { stopCalls++ },
            )
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        assertFalse(handled.isCompleted)
        release.countDown()
        assertTrue(handled.await())
        assertEquals(1, stopCalls)
        assertFalse(runtime.isRunning)
    }

    @Test
    fun `non headless mode leaves runtime and test mode lifecycle untouched`() {
        val runtime = headlessRuntime().also(DesktopAppRuntime::start)

        val handled = runHeadlessMode(
            args = TestArguments(testMode = true, headless = false),
            runtime = runtime,
            awaitTestModeTermination = { error("must not wait") },
            stopTestMode = { error("must not stop") },
        )

        assertFalse(handled)
        assertTrue(runtime.isRunning)
        runtime.close()
    }

    private fun headlessRuntime() = DesktopAppRuntime(
        libraryUpdateScheduler = RecordingRuntimeService(),
        localSourceScanService = RecordingRuntimeService(),
        autoBackupScheduler = RecordingRuntimeService(),
        startupCleanup = {},
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

private class RecordingRuntimeService : DesktopRuntimeService {
    var started = false
    var stopped = false

    override fun start() {
        started = true
    }

    override fun stop() {
        stopped = true
        started = false
    }
}
