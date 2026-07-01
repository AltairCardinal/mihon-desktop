package mihon.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
