package mihon.desktop.task

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.error.AppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DesktopTaskSchedulerIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `checkpoint and cancellation obey legal terminal transitions`() {
        val scheduler = scheduler()
        scheduler.register(task("one", "same"))
        scheduler.complete("one")

        assertFalse(scheduler.cancel("one"))
        assertFalse(scheduler.checkpoint("one", TaskCheckpoint("late", 2)))
        scheduler.register(task("again", "same"))
        assertTrue(scheduler.pendingTasks().isEmpty())
        assertEquals(TaskStatus.Completed, scheduler.snapshot("one")?.status)
    }

    @Test
    fun `idempotency key deduplicates pending registrations`() {
        val scheduler = scheduler()
        scheduler.register(task("first", "daily"))
        scheduler.register(task("second", "daily"))

        assertEquals(listOf("first"), scheduler.pendingTasks().map { it.id })
    }

    @Test
    fun `invalid progress and checkpoint are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TaskCheckpoint("x", -1, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { TaskCheckpoint("x", 1, 1.1f) }
    }

    @Test
    fun `corrupt store is quarantined and startup remains available`() {
        val file = directory.resolve("tasks.json")
        Files.writeString(file, "{truncated")

        val store = FileTaskCheckpointStore(file)

        assertTrue(store.load().isEmpty())
        assertTrue(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().contains("corrupt") } })
        assertTrue(store.diagnostics().single().contains("corrupt"))
    }

    @Test
    fun `each corrupt store gets a unique quarantine name`() {
        val file = directory.resolve("tasks.json")
        repeat(2) {
            Files.writeString(file, "{truncated")
            FileTaskCheckpointStore(file).load()
        }

        assertEquals(2L, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("corrupt") }.count() })
    }

    @Test
    fun `legacy string failure store is loaded without quarantining other pending checkpoints`() {
        val file = directory.resolve("tasks.json")
        Files.writeString(
            file,
            """
            [
              {
                "task": {"id":"failed","idempotencyKey":"failed-key"},
                "status": "Failed",
                "failure": "legacy failure message",
                "failedUnits": []
              },
              {
                "task": {
                  "id":"pending",
                  "idempotencyKey":"pending-key",
                  "checkpoint":{"cursor":"page-4","completedUnits":3,"progress":0.75}
                },
                "status": "Pending",
                "failure": null,
                "failedUnits": []
              }
            ]
            """.trimIndent(),
        )

        val store = FileTaskCheckpointStore(file)
        val scheduler = DesktopTaskScheduler(store)

        val legacyFailure = scheduler.snapshot("failed")?.failure?.toAppError()
        assertTrue(legacyFailure is AppError.Unknown)
        assertEquals("legacy failure message", legacyFailure?.cause?.message)
        assertEquals(TaskStatus.Pending, scheduler.snapshot("pending")?.status)
        assertEquals(TaskCheckpoint("page-4", 3, 0.75f), scheduler.snapshot("pending")?.task?.checkpoint)
        assertTrue(Files.exists(file))
        assertTrue(store.diagnostics().isEmpty())
        assertEquals(0L, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("corrupt") }.count() })
    }

    @ParameterizedTest(name = "failure={0}")
    @MethodSource("invalidLegacyFailures")
    fun `invalid legacy failure quarantines the whole store without partially loading pending tasks`(
        description: String,
        invalidFailure: String,
    ) {
        val file = directory.resolve("tasks-$description.json")
        Files.writeString(
            file,
            """
            [
              {
                "task":{"id":"failed","idempotencyKey":"failed-key"},
                "status":"Failed",
                "failure":$invalidFailure
              },
              {
                "task":{
                  "id":"pending",
                  "idempotencyKey":"pending-key",
                  "checkpoint":{"cursor":"keep-me","completedUnits":2}
                },
                "status":"Pending"
              }
            ]
            """.trimIndent(),
        )
        val store = FileTaskCheckpointStore(file)

        val loaded = store.load()

        assertTrue(loaded.isEmpty(), "No pending task may be partially recovered from an invalid store")
        assertFalse(Files.exists(file), "The original invalid store must be moved")
        val quarantined = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("${file.fileName}.corrupt-") }.toList()
        }
        assertEquals(1, quarantined.size)
        assertTrue(store.diagnostics().single().startsWith("corrupt task store quarantined:"))
        assertTrue(store.diagnostics().single().substringAfter(':').isNotBlank())
    }

    @Test
    fun `new store writes structured failures as objects and omits absent failure`() {
        val file = directory.resolve("tasks.json")
        val scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        scheduler.register(task("server", "server-key"))
        scheduler.fail("server", AppError.Server(503, IllegalStateException("unavailable")))
        scheduler.register(task("rate", "rate-key"))
        scheduler.fail("rate", AppError.RateLimited(42, IllegalStateException("slow down")))
        scheduler.register(task("pending", "pending-key"))

        val tasks = Json.parseToJsonElement(Files.readString(file)).jsonArray
            .associateBy { it.jsonObject.getValue("task").jsonObject.getValue("id").jsonPrimitive.content }
        val serverFailure = tasks.getValue("server").jsonObject.getValue("failure").jsonObject
        val rateFailure = tasks.getValue("rate").jsonObject.getValue("failure").jsonObject

        assertEquals("Server", serverFailure.getValue("type").jsonPrimitive.content)
        assertEquals("503", serverFailure.getValue("statusCode").jsonPrimitive.content)
        assertEquals("unavailable", serverFailure.getValue("message").jsonPrimitive.content)
        assertEquals("RateLimited", rateFailure.getValue("type").jsonPrimitive.content)
        assertEquals("42", rateFailure.getValue("retryAfterSeconds").jsonPrimitive.content)
        assertEquals("slow down", rateFailure.getValue("message").jsonPrimitive.content)
        assertFalse(tasks.getValue("pending").jsonObject.containsKey("failure"))
    }

    @Test
    fun `concurrent scheduler instances do not lose writers`() = runTest {
        val file = directory.resolve("tasks.json")
        (1..20).map { index ->
            async { DesktopTaskScheduler(FileTaskCheckpointStore(file)).register(task("id-$index", "key-$index")) }
        }.awaitAll()

        assertEquals(20, DesktopTaskScheduler(FileTaskCheckpointStore(file)).pendingTasks().size)
    }

    @Test
    fun `atomic move fallback replaces store and ignores stale temp`() {
        val file = directory.resolve("tasks.json")
        Files.writeString(directory.resolve("tasks.json.stale.tmp"), "bad")
        val scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file, atomicMove = { _, _ -> false }))

        scheduler.register(task("one", "one"))

        assertEquals(listOf("one"), DesktopTaskScheduler(FileTaskCheckpointStore(file)).pendingTasks().map { it.id })
    }

    @Test
    fun `transient atomic move access denial is retried without losing checkpoint`() {
        val file = directory.resolve("tasks.json")
        var attempts = 0
        val store = FileTaskCheckpointStore(file) { source, target ->
            attempts++
            if (attempts == 1) throw AccessDeniedException(source.toString(), target.toString(), "transient lock")
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        }

        DesktopTaskScheduler(store).register(task("one", "one"))

        assertEquals(2, attempts)
        assertEquals(listOf("one"), DesktopTaskScheduler(FileTaskCheckpointStore(file)).pendingTasks().map { it.id })
    }

    @Test
    fun `partial failure survives store reload with structured error fields`() {
        val scheduler = scheduler()
        scheduler.register(task("one", "one"))
        scheduler.start("one")
        val failure = AppError.PartialFailure(
            failures = listOf(AppError.Server(503)),
            failedUnits = listOf(
                AppError.FailedUnit("manga:7", AppError.RateLimited(42)),
                AppError.FailedUnit("manga:8", AppError.Unknown(IllegalStateException("broken payload"))),
            ),
        )

        scheduler.fail("one", failure)

        val restored = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))).snapshot("one")
        val restoredFailure = restored?.failure?.toAppError() as AppError.PartialFailure
        assertEquals(AppError.Server(503), restoredFailure.failures.single())
        assertEquals("manga:7", restoredFailure.failedUnits[0].unitId)
        assertEquals(AppError.RateLimited(42), restoredFailure.failedUnits[0].error)
        assertEquals("manga:8", restoredFailure.failedUnits[1].unitId)
        assertEquals("broken payload", restoredFailure.failedUnits[1].error.cause?.message)
    }

    @ParameterizedTest
    @MethodSource("appErrors")
    fun `every app error variant survives store reload`(failure: AppError) {
        val file = directory.resolve("${failure::class.simpleName}.json")
        val scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        scheduler.register(task("one", "one"))
        scheduler.start("one")

        scheduler.fail("one", failure)

        val restored = DesktopTaskScheduler(FileTaskCheckpointStore(file))
            .snapshot("one")?.failure?.toAppError()
        assertEquals(failure::class, restored?.let { it::class })
        assertEquals(failure.cause?.message, restored?.cause?.message)
        when (failure) {
            is AppError.RateLimited -> assertEquals(failure.retryAfterSeconds, (restored as AppError.RateLimited).retryAfterSeconds)
            is AppError.Server -> assertEquals(failure.statusCode, (restored as AppError.Server).statusCode)
            is AppError.PartialFailure -> {
                restored as AppError.PartialFailure
                assertEquals(failure.failures.map { it::class }, restored.failures.map { it::class })
                assertEquals(failure.failedUnits.map { it.unitId }, restored.failedUnits.map { it.unitId })
            }
            else -> Unit
        }
    }

    companion object {
        @JvmStatic
        fun appErrors(): List<AppError> = listOf(
            AppError.Network(IllegalStateException("network")),
            AppError.Authentication(IllegalStateException("auth")),
            AppError.Challenge(IllegalStateException("challenge")),
            AppError.RateLimited(42, IllegalStateException("rate")),
            AppError.Server(503, IllegalStateException("server")),
            AppError.Permission(IllegalStateException("permission")),
            AppError.MalformedData(IllegalStateException("malformed")),
            AppError.Storage(IllegalStateException("storage")),
            AppError.Cancelled,
            AppError.PartialFailure(
                failures = listOf(AppError.Challenge()),
                failedUnits = listOf(AppError.FailedUnit("unit", AppError.Server(500))),
                cause = IllegalStateException("partial"),
            ),
            AppError.Unknown(IllegalStateException("unknown")),
        )

        @JvmStatic
        fun invalidLegacyFailures(): List<Arguments> = listOf(
            Arguments.of("number", "7"),
            Arguments.of("boolean", "true"),
            Arguments.of("array", "[]"),
            Arguments.of("malformed-object", """{"message":"missing type"}"""),
        )
    }

    private fun scheduler() = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json")))
    private fun task(id: String, key: String) = BackgroundTask(id, key)
}
