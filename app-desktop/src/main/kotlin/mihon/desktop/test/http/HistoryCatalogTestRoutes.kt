package mihon.desktop.test.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal fun Route.historyCatalogTestRoutes() {
    get("/test/history/fixture/state") {
        val fixture = HistoryTestModeBridge.controller?.catalogFixture
        if (fixture == null) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        } else {
            call.respondText(fixture.snapshot().toString(), ContentType.Application.Json)
        }
    }
    post("/test/history/fixture/{step}") {
        val fixture = HistoryTestModeBridge.controller?.catalogFixture
        if (fixture ==
            null
        ) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val mode =
            Json.parseToJsonElement(call.receiveText().ifBlank { "{}" }).jsonObject["mode"]?.jsonPrimitive?.content
                ?: "success"
        fixture.execute(requireNotNull(call.parameters["step"]), mode)
        HistoryTestModeBridge.controller?.hydrate()
        call.respondText(fixture.snapshot().toString(), ContentType.Application.Json)
    }
    get("/test/history/catalog-source/manga/history-catalog") {
        call.respondText(
            """{"result":"ok","data":{"id":"history-catalog","type":"manga","attributes":{"title":{"en":"History catalogue acceptance"},"description":{},"status":"ongoing","tags":[]},"relationships":[]}}""",
            ContentType.Application.Json,
        )
    }
    get("/test/history/catalog-source/manga/history-catalog/feed") {
        val fixture = HistoryTestModeBridge.controller?.catalogFixture
        fixture?.chapterCalls?.incrementAndGet()
        fixture?.awaitDirectoryRelease()
        val mode = fixture?.mode ?: "success"
        if (mode == "timeout") delay(31_000)
        val code = when (mode) {
            "http403" -> 403
            "http429" -> 429
            "http500" -> 500
            else -> 200
        }
        val body = when {
            mode == "malformed" -> "{"
            mode == "empty" -> """{"result":"ok","data":[],"total":0}"""
            code != 200 -> """{"result":"error"}"""
            else -> historyCatalogFeed(includeMiddle = mode != "missing_target")
        }
        call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(code))
    }
    get("/test/history/catalog-source/at-home/server/{chapter}") {
        HistoryTestModeBridge.controller?.catalogFixture?.pageCalls?.incrementAndGet()
        val chapter = call.parameters["chapter"]?.takeIf { it in setOf("1", "2", "3") }
            ?: return@get call.respondText("{}", ContentType.Application.Json, HttpStatusCode.NotFound)
        val localBase = "http://127.0.0.1:${call.request.local.localPort}/test/history/catalog-source"
        call.respondText(
            """{"result":"ok","baseUrl":"$localBase","chapter":{"hash":"$chapter","data":["0.png","1.png","2.png","3.png"],"dataSaver":[]}}""",
            ContentType.Application.Json,
        )
    }
    get("/test/history/catalog-source/data/{chapter}/{page}") {
        HistoryTestModeBridge.controller?.catalogFixture?.imageCalls?.incrementAndGet()
        val chapter = call.parameters["chapter"]?.toIntOrNull()?.takeIf { it in 1..3 }
            ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
        val bytes = ByteArrayOutputStream().also { output ->
            val image = BufferedImage(24, 32, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = java.awt.Color(chapter * 60, 40, 80)
                graphics.fillRect(0, 0, 24, 32)
            } finally {
                graphics.dispose()
            }
            check(ImageIO.write(image, "png", output))
        }.toByteArray()
        call.respondBytes(bytes, ContentType.Image.PNG)
    }
}

internal fun historyCatalogFeed(includeMiddle: Boolean = true): String = """{"result":"ok","data":[${listOf(
    3,
    2,
    1,
).filter {
    includeMiddle || it != 2
}.joinToString(",") { n ->
    """{"id":"$n","type":"chapter","attributes":{"chapter":"$n","volume":null,"title":null,"externalUrl":null},"relationships":[]}"""
}}],"total":${if (includeMiddle) 3 else 2}}"""
