package mihon.desktop.test.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import mihon.desktop.test.screenshot.ScreenshotService
import mihon.desktop.test.state.applicationState
import java.time.Instant

private fun parseJsonBody(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    val map = mutableMapOf<String, String>()
    val content = body.trim().removePrefix("{").removeSuffix("}")
    content.split(",").forEach { pair ->
        val parts = pair.split(":")
        if (parts.size == 2) {
            val key = parts[0].trim().removeSurrounding("\"")
            val value = parts[1].trim().removeSurrounding("\"")
            map[key] = value
        }
    }
    return map
}

/**
 * Configure HTTP test routes.
 */
fun Application.testHttpServer() {
    routing {
        // Health check
        get("/test/health") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"status": "ok", "timestamp": "${Instant.now()}"}"""
            }
        }

        // Get current application state
        get("/test/state") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                val state = applicationState
                """{
                    |"currentScreen": "${state.currentScreen.value ?: "HomeScreen"}",
                    |"isLoading": ${state.isLoading.value},
                    |"notifications": [],
                    |"screens": [],
                    |"actions": [],
                    |"testMode": ${state.testMode},
                    |"timestamp": "${Instant.now()}"
                |}""".trimMargin()
            }
        }

        // Get list of available screens
        get("/test/screens") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                val screens = applicationState.screens.value.map { screen ->
                    """{"id":"$screen","name":"$screen"}"""
                }
                "[${screens.joinToString(",")}]"
            }
        }

        // Navigate to a screen
        post("/test/navigate/{screen}") {
            val screen = call.parameters["screen"] ?: "HomeScreen"
            applicationState.setCurrentScreen(screen)
            applicationState.recordAction(
                "navigate",
                mapOf("screen" to screen),
            )
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success":true,"newScreen":"$screen","timestamp":"${Instant.now()}"}"""
            }
        }

        // Execute a test action
        post("/test/action/{action}") {
            val action = call.parameters["action"] ?: ""
            val body = try {
                call.receiveText()
            } catch (e: Exception) {
                "{}"
            }
            
            val params = parseJsonBody(body)
            
            applicationState.recordAction(action, params)
            
            when (action) {
                "search" -> applicationState.setLoading(true)
                "filter", "sort" -> { }
                else -> { }
            }
            
            if (action == "search") {
                applicationState.setLoading(false)
            }
            
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success":true,"action":"$action","timestamp":"${Instant.now()}"}"""
            }
        }

        // Capture screenshot
        post("/test/screenshot") {
            val body = try {
                call.receiveText()
            } catch (e: Exception) {
                """{"name": "screenshot"}"""
            }
            
            val params = parseJsonBody(body)
            val name = params["name"] ?: "screenshot"
            
            val path = ScreenshotService.capture(name)
            
            if (path != null) {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"path":"$path","timestamp":"${Instant.now()}"}"""
                }
            } else {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.InternalServerError,
                ) {
                    """{"success":false,"error":"Screenshot capture failed","timestamp":"${Instant.now()}"}"""
                }
            }
        }

        // Reset test state
        post("/test/reset") {
            applicationState.reset()
            applicationState.testMode = true
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success": true, "timestamp": "${Instant.now()}"}"""
            }
        }

        // Get action history
        get("/test/history") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                val history = applicationState.actionHistory.value.map { record ->
                    """{"action":"${record.action}","params":{},"timestamp":"${record.timestamp}"}"""
                }
                "[${history.joinToString(",")}]"
            }
        }

        // Data management endpoints
        post("/test/data/manga") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success":true,"operation":"processed","timestamp":"${Instant.now()}"}"""
            }
        }

        post("/test/data/category") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success":true,"operation":"processed","timestamp":"${Instant.now()}"}"""
            }
        }

        post("/test/data/setting") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success":true,"operation":"processed","timestamp":"${Instant.now()}"}"""
            }
        }
    }
}
