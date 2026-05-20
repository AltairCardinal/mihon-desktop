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
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.test.screenshot.ScreenshotService
import mihon.desktop.test.state.applicationState
import mihon.desktop.test.state.downloadState
import mihon.desktop.test.state.historyState
import mihon.desktop.test.state.updatesState
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
                val dlState = downloadState
                val upState = updatesState
                val histState = historyState
                """{
                    |"currentScreen": "${state.currentScreen.value ?: "HomeScreen"}",
                    |"isLoading": ${state.isLoading.value},
                    |"notifications": [],
                    |"screens": [],
                    |"actions": [],
                    |"testMode": ${state.testMode},
                    |"downloadQueueSize": ${dlState.queueSize},
                    |"downloadsPaused": ${dlState.isPaused},
                    |"updateCount": ${upState.count},
                    |"hasUnreadUpdates": ${upState.hasUnread},
                    |"historyCount": ${histState.count},
                    |"timestamp": "${Instant.now()}"
                |}
                """.trimMargin()
            }
        }

        // Get list of available screens
        get("/test/screens") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                val screens = TestNavigationController.getAvailableScreens().map { screen ->
                    """{"id":"$screen","name":"$screen"}"""
                }
                "[${screens.joinToString(",")}]"
            }
        }

        // Navigate to a screen (triggers actual UI navigation)
        post("/test/navigate/{screen}") {
            val screenId = call.parameters["screen"] ?: "HomeScreen"

            // First try to get a tab for this screenId
            val tab = TestNavigationController.getTabOrNull(screenId)

            if (tab != null) {
                // This is a tab - navigate to it
                TestNavigationController.navigateToTab(screenId)
                applicationState.setCurrentScreen(screenId)
                applicationState.recordAction(
                    "navigate",
                    mapOf("screen" to screenId, "type" to "tab", "success" to true),
                )
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"newScreen":"$screenId","type":"tab","tabFound":true,"timestamp":"${Instant.now()}"}"""
                }
            } else {
                // Check if this is a known screen that needs special navigation
                val screenMap = mapOf(
                    "SettingsScreen" to "open_general_settings",
                    "GeneralSettingsScreen" to "open_general_settings",
                    "DownloadSettingsScreen" to "open_download_settings",
                    "BackupSettingsScreen" to "open_backup_settings",
                    "ExtensionListScreen" to "open_extensions",
                    "MigrationSearchScreen" to "open_migration",
                )

                // DEBUG: Record in action history
                applicationState.recordAction(
                    "DEBUG_screenMapCheck",
                    mapOf(
                        "screenId" to screenId,
                        "inMap" to screenMap.containsKey(screenId).toString(),
                    ),
                )

                if (screenMap.containsKey(screenId)) {
                    // First navigate to MoreTab
                    TestNavigationController.navigateToTab("MoreTab")
                    // Then trigger the screen navigation via shared state
                    val action = screenMap[screenId]!!
                    applicationState.setCurrentScreen(screenId)
                    applicationState.recordAction(
                        "navigate",
                        mapOf("screen" to screenId, "type" to "nested", "action" to action),
                    )
                    // Set the pending screen navigation for MoreRootScreen to pick up
                    mihon.desktop.ui.settings.TestScreenNavigator.navigateTo(action)
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    ) {
                        """{"success":true,"newScreen":"$screenId","type":"nested","note":"Navigate to MoreTab first, then trigger action","timestamp":"${Instant.now()}"}"""
                    }
                } else {
                    // Unknown screen
                    applicationState.recordAction(
                        "navigate",
                        mapOf("screen" to screenId, "success" to false, "error" to "Unknown screen: $screenId"),
                    )
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    ) {
                        """{"success":false,"newScreen":"$screenId","error":"Unknown screen: $screenId","timestamp":"${Instant.now()}"}"""
                    }
                }
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

            // Process actions
            when (action) {
                // Library actions
                "search", "filter", "sort", "select" -> { }
                // Reader actions
                "reader_next_page", "reader_prev_page", "reader_next_chapter", "reader_prev_chapter",
                "reader_mode", "reader_zoom",
                -> { }
                // Settings actions
                "setting_change", "setting_reset" -> { }
                // Browse actions
                "browse_search" -> { }
                // Download actions
                "downloads_pause_all" -> downloadState.isPaused = true
                "downloads_resume_all" -> downloadState.isPaused = false
                "downloads_cancel", "downloads_cancel_all", "downloads_clear_errors",
                "downloads_retry_errors", "downloads_reorder", "downloads_sort",
                "downloads_reverse",
                -> { }
                // Updates actions
                "updates_refresh", "updates_mark_all_read", "updates_filter",
                "updates_clear_filters", "updates_open_upcoming", "updates_select",
                "updates_download", "updates_mark_read",
                -> { }
                // History actions
                "history_search", "history_clear_all", "history_remove", "history_select" -> { }
                // Extension actions
                "extension_select", "extension_enable", "extension_disable",
                "extension_update", "extension_update_all", "extension_search",
                -> { }
                // Migration actions
                "migration_search", "migration_select" -> { }
                // Backup actions
                "backup_create", "backup_restore" -> { }
                // Manga detail actions
                "addToLibrary", "removeFromLibrary", "download" -> { }
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
            TestNavigationController.reset()
            downloadState.reset()
            updatesState.reset()
            historyState.reset()
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
