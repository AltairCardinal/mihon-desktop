package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.backup.RestoreProgress
import mihon.desktop.ui.settings.BackupRestoreScreenModel
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class BackupTestModeHttpTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `backup create action invokes production factory instead of unconditional success`() = runBlocking {
        val factory = mockk<BackupRestoreScreenModelFactory>()
        coEvery { factory.createBackup(tempDir) } returns tempDir.resolve("created.tachibk")
        val controller = BackupTestModeController(factory)
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/backup_create", """{"directory":"${escaped(tempDir)}"}""")

                coVerify(exactly = 1) { factory.createBackup(tempDir) }
            }
        } finally {
            BackupTestModeBridge.clear(controller)
        }
    }

    @Test
    fun `backup create exposes terminal state and typed storage failure`() = runBlocking {
        val factory = mockk<BackupRestoreScreenModelFactory>()
        coEvery { factory.createBackup(tempDir) } returns tempDir.resolve("created.tachibk")
        val controller = BackupTestModeController(factory)
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val created = post(baseUrl, "/test/action/backup_create", """{"directory":"${escaped(tempDir)}"}""")
                assertEquals(200, created.statusCode())
                assertEquals(
                    "CREATED",
                    created.json().getValue("backup").jsonObject.getValue("phase").jsonPrimitive.content,
                )

                coEvery { factory.createBackup(tempDir) } throws IOException("disk full")
                val failed = post(baseUrl, "/test/action/backup_create", """{"directory":"${escaped(tempDir)}"}""")
                assertEquals(409, failed.statusCode())
                assertTrue(failed.body().contains("WORKFLOW_FAILED"))
                assertTrue(failed.body().contains("\"type\":\"Storage\""))

                assertEquals(400, post(baseUrl, "/test/action/backup_create", "{}").statusCode())
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `backup restore requires confirmation and reports production progress completion and partial failure`() = runBlocking {
        val backupFile = tempDir.resolve("restore.tachibk").apply { writeText("fixture") }
        val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = DesktopBackupRestorer.RestoreResult().apply { incrementSuccess() }
        var restoreResult: TaskState<DesktopBackupRestorer.RestoreResult> = TaskState.Success(result)
        val model = BackupRestoreScreenModel(
            scope = modelScope,
            loadPreview = { BackupPreview(1, 2, 0, 0, 0, 0, 0) },
            restore = { _, onProgress ->
                onProgress(RestoreProgress(1, 1))
                restoreResult
            },
        )
        val factory = mockk<BackupRestoreScreenModelFactory>()
        every { factory.create(any<CoroutineScope>()) } returns model
        val controller = BackupTestModeController(factory)
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val preview = post(
                    baseUrl,
                    "/test/action/backup_restore",
                    """{"file":"${escaped(backupFile)}"}""",
                )
                assertEquals(409, preview.statusCode())
                assertTrue(preview.body().contains("CONFIRMATION_REQUIRED"))
                assertTrue(preview.body().contains("\"confirmationRequired\":true"))

                val completed = post(baseUrl, "/test/action/backup_restore", """{"confirm":"true"}""")
                assertEquals(200, completed.statusCode())
                assertTrue(completed.body().contains("\"phase\":\"COMPLETED\""))

                restoreResult = TaskState.Failure(
                    AppError.PartialFailure(
                        failures = listOf(AppError.Storage(IOException("one item"))),
                    ),
                )
                post(
                    baseUrl,
                    "/test/action/backup_restore",
                    """{"file":"${escaped(backupFile)}"}""",
                )
                val partial = post(baseUrl, "/test/action/backup_restore", """{"confirm":"true"}""")
                assertEquals(409, partial.statusCode())
                assertTrue(partial.body().contains("PARTIAL_FAILURE"))
                assertTrue(partial.body().contains("\"phase\":\"PARTIAL_FAILURE\""))
            }
        } finally {
            controller.close()
            modelScope.cancel()
        }
    }

    @Test
    fun `backup restore progress is observable and cancellation closes the production model job`() = runBlocking {
        val backupFile = tempDir.resolve("cancel.tachibk").apply { writeText("fixture") }
        val progressReached = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<TaskState<DesktopBackupRestorer.RestoreResult>>()
        val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = BackupRestoreScreenModel(
            scope = modelScope,
            loadPreview = { BackupPreview(1, 0, 1, 0, 0, 0, 0) },
            restore = { _, onProgress ->
                onProgress(RestoreProgress(1, 2))
                progressReached.complete(Unit)
                neverComplete.await()
            },
        )
        val factory = mockk<BackupRestoreScreenModelFactory>()
        every { factory.create(any<CoroutineScope>()) } returns model
        val controller = BackupTestModeController(factory)
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/backup_restore", """{"file":"${escaped(backupFile)}"}""")
                val confirming = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/backup_restore", """{"confirm":"true"}""")
                }
                progressReached.await()

                val state = get(baseUrl, "/test/state")
                assertTrue(state.body().contains("\"phase\":\"RESTORING\""))
                assertTrue(state.body().contains("\"completed\":1"))

                val cancelled = post(baseUrl, "/test/action/backup_cancel", "{}")
                assertEquals(200, cancelled.statusCode())
                assertTrue(cancelled.body().contains("\"phase\":\"CANCELLED\""))
                assertEquals(409, confirming.await().statusCode())
            }
        } finally {
            controller.close()
            modelScope.cancel()
        }
    }

    @Test
    fun `backup create cancellation stops production work and concurrent create is typed`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val factory = mockk<BackupRestoreScreenModelFactory>()
        coEvery { factory.createBackup(tempDir) } coAnswers {
            started.complete(Unit)
            try {
                CompletableDeferred<File>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val controller = BackupTestModeController(factory)
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val creating = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/backup_create", """{"directory":"${escaped(tempDir)}"}""")
                }
                started.await()

                val concurrent = post(
                    baseUrl,
                    "/test/action/backup_create",
                    """{"directory":"${escaped(tempDir)}"}""",
                )
                assertEquals(409, concurrent.statusCode())
                assertTrue(concurrent.body().contains("OPERATION_IN_PROGRESS"))

                val cancel = post(baseUrl, "/test/action/backup_cancel", "{}")
                assertEquals(200, cancel.statusCode())
                cancelled.await()
                assertEquals(409, creating.await().statusCode())
                assertTrue(get(baseUrl, "/test/state").body().contains("\"phase\":\"CANCELLED\""))
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `backup owner unavailable and closed failures are typed`() = runBlocking {
        BackupTestModeBridge.controller?.let(BackupTestModeBridge::clear)
        withServer { baseUrl ->
            assertEquals(503, post(baseUrl, "/test/action/backup_create", "{}").statusCode())
        }
        val controller = BackupTestModeController(mockk())
        controller.close()
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val closed = post(baseUrl, "/test/action/backup_create", "{}")
                assertEquals(503, closed.statusCode())
                assertTrue(closed.body().contains("OWNER_CLOSED"))
            }
        } finally {
            BackupTestModeBridge.clear(controller)
        }
    }

    @Test
    fun `create cancel cannot miss a child between construction and active handle publication`() = runBlocking {
        val publishWindow = CompletableDeferred<Unit>()
        val continuePublish = CompletableDeferred<Unit>()
        val allowFactoryReturn = CompletableDeferred<Unit>()
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = mockk<BackupRestoreScreenModelFactory>()
        coEvery { factory.createBackup(tempDir) } coAnswers {
            allowFactoryReturn.await()
            tempDir.resolve("must-not-complete.tachibk")
        }
        val controller = BackupTestModeController(
            factory = factory,
            scope = ownerScope,
            beforeCreateStart = {
                publishWindow.complete(Unit)
                continuePublish.await()
            },
        )
        BackupTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val creating = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/backup_create", """{"directory":"${escaped(tempDir)}"}""")
                }
                publishWindow.await()

                assertEquals(200, post(baseUrl, "/test/action/backup_cancel", "{}").statusCode())
                continuePublish.complete(Unit)
                allowFactoryReturn.complete(Unit)

                assertEquals(409, creating.await().statusCode())
                assertEquals("CANCELLED", controller.snapshot().phase)
            }
        } finally {
            controller.close()
            ownerScope.cancel()
        }
    }

    @Test
    fun `caller cancellation cancels and joins the production create child`() = runBlocking {
        val childStarted = CompletableDeferred<Unit>()
        val childFinished = CompletableDeferred<Unit>()
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = mockk<BackupRestoreScreenModelFactory>()
        coEvery { factory.createBackup(tempDir) } coAnswers {
            childStarted.complete(Unit)
            try {
                CompletableDeferred<File>().await()
            } finally {
                childFinished.complete(Unit)
            }
        }
        val controller = BackupTestModeController(factory, ownerScope)
        try {
            val caller = launch {
                controller.execute("backup_create", mapOf("directory" to tempDir.absolutePath))
            }
            childStarted.await()

            caller.cancel()
            caller.join()

            assertTrue(childFinished.isCompleted)
            assertEquals("CANCELLED", controller.snapshot().phase)
        } finally {
            controller.close()
            ownerScope.cancel()
        }
    }

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(base: String, path: String, body: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(base: String, path: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject

    private fun escaped(file: File) = file.absolutePath.replace("\\", "\\\\")
}
