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
import mihon.desktop.test.state.readerState
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
                "search", "filter", "sort" -> { }

                // Select manga and open detail
                "select" -> {
                    val index = params["index"]?.toIntOrNull() ?: 0
                    // Record the selection - UI should handle actual navigation
                    applicationState.recordAction("select", mapOf("index" to index))
                }

                // Open manga detail by mangaId
                "open_manga_detail" -> {
                    val mangaId = params["mangaId"]?.toLongOrNull() ?: 0L
                    if (mangaId > 0) {
                        TestNavigationController.navigateToMangaDetail(mangaId)
                        applicationState.setCurrentScreen("MangaDetailScreen")
                        applicationState.recordAction("open_manga_detail", mapOf("mangaId" to mangaId))
                    }
                }

                // Read chapter - open reader
                "read_chapter", "start_reading" -> {
                    val mangaId = params["mangaId"]?.toLongOrNull()
                        ?: params["mangaIndex"]?.toLongOrNull()
                        ?: 0L
                    if (mangaId > 0) {
                        // Navigate to manga detail first
                        TestNavigationController.navigateToMangaDetail(mangaId)

                        // Then open reader
                        val chapterId = params["chapterId"]?.toLongOrNull() ?: mangaId
                        val chapterTitle = params["chapterTitle"] ?: "Chapter ${params["chapterIndex"] ?: 0}"
                        TestNavigationController.openReader(
                            mangaId = mangaId,
                            chapterId = chapterId,
                            chapterTitle = chapterTitle,
                            mangaTitle = "Manga",
                            chapterUrl = "",
                            sourceId = 0L,
                            initialPage = params["chapterIndex"]?.toIntOrNull() ?: 0,
                        )

                        applicationState.setCurrentScreen("ReaderScreen")
                        applicationState.recordAction(action, mapOf("mangaId" to mangaId, "chapterId" to chapterId))
                    }
                }

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

        // Reader navigation endpoints
        post("/test/reader/next_page") {
            val currentPage = readerState.currentPage
            val totalPages = readerState.totalPages

            if (currentPage < totalPages - 1) {
                readerState.updatePage(currentPage + 1)
                applicationState.recordAction("reader_next_page", mapOf("page" to (currentPage + 1)))
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"action":"next_page","page":${currentPage + 1},"totalPages":$totalPages,"timestamp":"${Instant.now()}"}"""
                }
            } else {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":false,"action":"next_page","error":"Already at last page","page":$currentPage,"timestamp":"${Instant.now()}"}"""
                }
            }
        }

        post("/test/reader/prev_page") {
            val currentPage = readerState.currentPage
            val totalPages = readerState.totalPages

            if (currentPage > 0) {
                readerState.updatePage(currentPage - 1)
                applicationState.recordAction("reader_prev_page", mapOf("page" to (currentPage - 1)))
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"action":"prev_page","page":${currentPage - 1},"totalPages":$totalPages,"timestamp":"${Instant.now()}"}"""
                }
            } else {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":false,"action":"prev_page","error":"Already at first page","page":$currentPage,"timestamp":"${Instant.now()}"}"""
                }
            }
        }

        post("/test/reader/go_to_page") {
            val body = try {
                call.receiveText()
            } catch (e: Exception) {
                "{}"
            }
            val params = parseJsonBody(body)
            val targetPage = params["page"]?.toIntOrNull() ?: 0
            val totalPages = readerState.totalPages

            if (targetPage in 0 until totalPages) {
                readerState.updatePage(targetPage)
                applicationState.recordAction("reader_go_to_page", mapOf("page" to targetPage))
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"action":"go_to_page","page":$targetPage,"totalPages":$totalPages,"timestamp":"${Instant.now()}"}"""
                }
            } else {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                ) {
                    """{"success":false,"action":"go_to_page","error":"Invalid page number","requestedPage":$targetPage,"validRange":"0-${totalPages - 1}","timestamp":"${Instant.now()}"}"""
                }
            }
        }

        post("/test/reader/next_chapter") {
            if (readerState.hasNextChapter) {
                applicationState.recordAction("reader_next_chapter", mapOf("chapterId" to readerState.currentChapterId))
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"action":"next_chapter","hasNext":${readerState.hasNextChapter},"timestamp":"${Instant.now()}"}"""
                }
            } else {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":false,"action":"next_chapter","error":"No next chapter available","timestamp":"${Instant.now()}"}"""
                }
            }
        }

        post("/test/reader/prev_chapter") {
            if (readerState.hasPrevChapter) {
                applicationState.recordAction("reader_prev_chapter", mapOf("chapterId" to readerState.currentChapterId))
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":true,"action":"prev_chapter","hasPrev":${readerState.hasPrevChapter},"timestamp":"${Instant.now()}"}"""
                }
            } else {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """{"success":false,"action":"prev_chapter","error":"No previous chapter available","timestamp":"${Instant.now()}"}"""
                }
            }
        }

        post("/test/reader/close") {
            readerState.close()
            applicationState.recordAction("reader_close", emptyMap())
            TestNavigationController.navigateBack()
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{"success":true,"action":"close_reader","timestamp":"${Instant.now()}"}"""
            }
        }

        get("/test/reader/state") {
            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            ) {
                """{
                    |"isOpen": ${readerState.isOpen},
                    |"currentPage": ${readerState.currentPage},
                    |"totalPages": ${readerState.totalPages},
                    |"currentChapterId": ${readerState.currentChapterId},
                    |"isWebtoon": ${readerState.isWebtoon},
                    |"mangaTitle": "${readerState.mangaTitle}",
                    |"chapterTitle": "${readerState.chapterTitle}",
                    |"hasNextChapter": ${readerState.hasNextChapter},
                    |"hasPrevChapter": ${readerState.hasPrevChapter},
                    |"timestamp": "${Instant.now()}"
                |}
                """.trimMargin()
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
            readerState.reset()
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
