package mihon.desktop.migration

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.client.request.header
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.desktop.test.http.MigrationBatchTestBridge
import mihon.desktop.test.http.testHttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DesktopBatchMigrationTestModeTest {
    @Test
    fun `test mode migration actions drive persistent queue`() = runBlocking {
        val directory = Path.of(".test-tmp", "batch-http-${UUID.randomUUID()}")
        Files.createDirectories(directory)
        val controller = DesktopBatchMigrationController(
            DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))),
            executeMigration = { _, _ -> },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        MigrationBatchTestBridge.controller = controller
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) { testHttpServer() }.start(false)
        val client = HttpClient(OkHttp)
        try {
            client.post("http://localhost:$port/test/action/migration_submit") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"mangaId":42,"title":"Queued manga"}""")
            }
            val queue = controller.queues.value.values.single()
            assertEquals(42L, queue.items.single().mangaId)

            client.post("http://localhost:$port/test/action/migration_pause") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"queueId":"${queue.id}"}""")
            }
            assertTrue(controller.queue(queue.id)!!.paused)

            client.post("http://localhost:$port/test/action/migration_cancel_all") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"queueId":"${queue.id}"}""")
            }
            assertTrue(controller.queue(queue.id)!!.cancelled)
        } finally {
            client.close()
            server.stop(0, 500)
            controller.close()
            MigrationBatchTestBridge.controller = null
        }
    }
}
