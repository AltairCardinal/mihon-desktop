package mihon.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestModeRun
import mihon.desktop.test.completeTestModeStop
import mihon.desktop.test.state.TestState
import mihon.desktop.ui.ExternalActionNavigator
import mihon.desktop.ui.navigatorFixture
import mihon.desktop.ui.settings.DesktopUpdateIntent
import mihon.desktop.ui.settings.DesktopUpdateScreenModel
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.DesktopUpdateState
import mihon.desktop.update.InstallCancelled
import mihon.desktop.update.InstallManualOnly
import mihon.desktop.update.ManualOnly
import mihon.domain.platform.ExternalActionInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAppRuntimeTest {

    @Test
    fun `secondary acknowledgement exits without starting runtime services`(@org.junit.jupiter.api.io.TempDir tempDir: File) {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        val secondary = DesktopExternalActionBroker(stateFile)
        val service = RecordingRuntimeService()
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = service,
            localSourceScanService = RecordingRuntimeService(),
            autoBackupScheduler = RecordingRuntimeService(),
            startupCleanup = {},
        )
        var ownerStarts = 0

        val result = startDesktopInstance(secondary, "tachiyomi://raw") {
            ownerStarts++
            runtime.start()
        }

        assertEquals(DesktopInstanceStartResult.Forwarded, result)
        assertEquals(0, ownerStarts)
        assertFalse(service.started)
        owner.close()
    }

    @Test
    fun `owner runtime close releases broker state and repeated close is harmless`(@org.junit.jupiter.api.io.TempDir tempDir: File) {
        val stateFile = File(tempDir, "instance.json")
        val broker = DesktopExternalActionBroker(stateFile)
        val runtime = headlessRuntime()

        assertEquals(
            DesktopInstanceStartResult.Owner,
            startDesktopInstance(broker, null) {
                runtime.attachInstanceBroker(it)
                runtime.start()
            },
        )
        assertTrue(stateFile.exists())

        runtime.close()
        runtime.close()

        assertFalse(runtime.isRunning)
        assertFalse(stateFile.exists())
    }

    @Test
    fun `runtime close permanently owns an active updater job`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopUpdateController(
            { entered.complete(Unit); try { awaitCancellation() } catch (error: CancellationException) { cancelled.complete(Unit); throw error } },
            { release, _ -> ManualOnly(release.releaseLink) },
            { _, _ -> InstallManualOnly },
            { _, _ -> InstallCancelled },
        )
        val model = DesktopUpdateScreenModel(controller, parentScope)
        val runtime = DesktopAppRuntime(
            RecordingRuntimeService(), RecordingRuntimeService(), RecordingRuntimeService(),
            startupCleanup = {}, scope = parentScope, updateScreenModel = model,
        )
        assertTrue(model.intent(DesktopUpdateIntent.CHECK))
        entered.await()
        val completion = CompletableDeferred<Throwable?>()
        model.operationJob!!.invokeOnCompletion { completion.complete(it) }

        runtime.close()

        assertEquals(Unit, withTimeout(1_000) { cancelled.await() })
        assertSame(model.closeCancellation, completion.await())
        withTimeout(1_000) { model.state.first { it is DesktopUpdateState.Cancelled } }
        assertFalse(model.intent(DesktopUpdateIntent.CHECK))
        runtime.close()
    }

    @Test
    fun `startup failure reports only structured reason without starting owner`(@org.junit.jupiter.api.io.TempDir tempDir: File) {
        val broker = DesktopExternalActionBroker(File(tempDir, "instance.json"))
        val failures = mutableListOf<DesktopExternalActionBroker.Failure>()
        var ownerStarts = 0

        val result = startDesktopInstance(
            broker = broker,
            rawAction = "secret".repeat(DesktopExternalActionBroker.MAX_PAYLOAD_CHARS),
            reportFailure = failures::add,
        ) { ownerStarts++ }

        assertEquals(DesktopInstanceStartResult.Failed(DesktopExternalActionBroker.Failure.MessageTooLarge), result)
        assertEquals(listOf(DesktopExternalActionBroker.Failure.MessageTooLarge), failures)
        assertEquals(0, ownerStarts)
    }

    @Test
    fun `owner broker submits forwarded raw string to Task5 ViewUri ingress`(@org.junit.jupiter.api.io.TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        owner.startOrForward(null)
        var resolvedInput: ExternalActionInput? = null
        val navigator = ExternalActionNavigator(
            resolveTarget = { input ->
                resolvedInput = input
                DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.ParserRejected)
            },
            chapterDestination = { error("not a chapter") },
            testState = TestState(),
        )
        wireDesktopExternalActionBroker(owner, navigator)
        val secondary = DesktopExternalActionBroker(stateFile)
        val raw = "tachiyomi://manga?url=raw%2Fvalue"
        assertEquals(DesktopExternalActionBroker.StartResult.Forwarded, secondary.startOrForward(raw))
        val fixture = navigatorFixture()

        navigator.consumePending(fixture.navigator) {}

        assertEquals(ExternalActionInput.ViewUri(raw), resolvedInput)
        fixture.close()
        secondary.close()
        owner.close()
    }

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
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updater = idleUpdater(parentScope)
        val runtime = headlessRuntime(parentScope, updater).also(DesktopAppRuntime::start)
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
        assertTrue(updater.closed)
        assertFalse(updater.intent(DesktopUpdateIntent.CHECK))
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

    @Test
    fun `previous test mode run cannot terminate a restarted run`() = runBlocking {
        val previous = TestModeRun()
        val current = TestModeRun()
        val waiting = CountDownLatch(1)
        val waiter = async(Dispatchers.Default) {
            waiting.countDown()
            current.awaitTermination()
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        previous.terminate()
        assertFalse(waiter.isCompleted)

        current.terminate()
        waiter.await()
    }

    @Test
    fun `test mode stop releases waiter and runs remaining cleanup after engine failure`() = runBlocking {
        val run = TestModeRun()
        val waiting = CountDownLatch(1)
        val waiter = async(Dispatchers.Default) {
            waiting.countDown()
            run.awaitTermination()
        }
        val cleanup = mutableListOf<String>()

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        val failure = assertThrows(IllegalStateException::class.java) {
            completeTestModeStop(
                run,
                { throw IllegalStateException("engine stop failed") },
                { cleanup += "job" },
                { cleanup += "state" },
            )
        }

        assertEquals("engine stop failed", failure.message)
        assertEquals(listOf("job", "state"), cleanup)
        withTimeout(1_000) { waiter.await() }
    }

    @Test
    fun `headless mode closes runtime when test mode stop fails`() {
        val runtime = headlessRuntime().also(DesktopAppRuntime::start)

        val failure = assertThrows(IllegalStateException::class.java) {
            runHeadlessMode(
                args = TestArguments(testMode = true, headless = true),
                runtime = runtime,
                awaitTestModeTermination = {},
                stopTestMode = { throw IllegalStateException("test mode stop failed") },
            )
        }

        assertEquals("test mode stop failed", failure.message)
        assertFalse(runtime.isRunning)
    }

    private fun headlessRuntime(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        updater: DesktopUpdateScreenModel? = null,
    ) = DesktopAppRuntime(
        libraryUpdateScheduler = RecordingRuntimeService(),
        localSourceScanService = RecordingRuntimeService(),
        autoBackupScheduler = RecordingRuntimeService(),
        startupCleanup = {},
        scope = scope,
        updateScreenModel = updater,
    )

    private fun idleUpdater(scope: CoroutineScope) = DesktopUpdateScreenModel(
        DesktopUpdateController({ error("unused") }, { _, _ -> error("unused") }, { _, _ -> error("unused") }, { _, _ -> error("unused") }),
        scope,
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
