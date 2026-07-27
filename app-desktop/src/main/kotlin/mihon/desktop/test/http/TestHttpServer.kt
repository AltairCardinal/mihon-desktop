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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.test.screenshot.ScreenshotService
import mihon.desktop.test.state.applicationState
import mihon.desktop.test.state.downloadState
import mihon.desktop.test.state.historyState
import mihon.desktop.test.state.readerState
import mihon.desktop.test.state.updatesState
import mihon.desktop.ui.browse.SourceBrowseTestFailureCode
import mihon.desktop.ui.browse.SourceBrowseTestModeBridge
import mihon.desktop.ui.browse.SourceBrowseTestSnapshot
import java.time.Instant
import mihon.desktop.migration.BatchMigrationRequest
import mihon.desktop.migration.DesktopBatchMigrationController
import mihon.desktop.tracking.TrackingTestFailureCode
import mihon.desktop.tracking.TrackingTestModeController
import mihon.desktop.tracking.TrackingTestState
import mihon.desktop.ui.settings.DesktopUpdateIntent
import mihon.desktop.ui.settings.DesktopUpdateScreenModel
import mihon.desktop.ui.settings.presentation
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicReference

object MigrationBatchTestBridge {
    @Volatile
    var controller: DesktopBatchMigrationController? = null
}

object TrackingTestBridge {
    private val value = AtomicReference<TrackingTestModeController?>()
    var controller: TrackingTestModeController?
        get() = value.get()
        set(controller) {
            value.set(controller)
        }

    fun install(controller: TrackingTestModeController) {
        value.set(controller)
    }

    fun clear(expected: TrackingTestModeController): Boolean = value.compareAndSet(expected, null)
}

private fun parseJsonBody(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return try {
        Json.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull ?: value.toString()
                is JsonObject -> value.toString()
                else -> value.toString()
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun actionHistoryParams(
    action: String,
    params: Map<String, String>,
): Map<String, String> {
    val sensitiveKeys = when (action) {
        "setting_security_enable" -> setOf("passphrase", "confirmation")
        "setting_security_disable", "setting_security_delay" -> setOf("currentPassphrase")
        "setting_security_change_passphrase" -> setOf("currentPassphrase", "replacement", "confirmation")
        "setting_import_cloudflare_cookie" -> setOf("value")
        "source_login_complete" -> setOf("cookieHeader")
        "tracking_login" -> setOf("password", "apiKey", "code")
        else -> emptySet()
    }
    return params.mapValues { (key, value) -> if (key in sensitiveKeys) "<redacted>" else value }
}

private fun jsonText(value: JsonObject) = Json.encodeToString(JsonObject.serializer(), value)

private val nestedScreenActions = mapOf(
    "SettingsScreen" to "open_general_settings",
    "GeneralSettingsScreen" to "open_general_settings",
    "DownloadSettingsScreen" to "open_download_settings",
    "BackupSettingsScreen" to "open_backup_settings",
    "ExtensionListScreen" to "open_extensions",
    "MigrationSearchScreen" to "open_migration",
    "TrackingSettingsScreen" to "open_tracking",
    "SecuritySettingsScreen" to "open_security_settings",
)

internal fun nestedTestScreenAction(screenId: String): String? = nestedScreenActions[screenId]

internal fun screenshotResponse(
    path: String?,
    timestamp: Instant = Instant.now(),
): JsonObject = buildJsonObject {
    put("success", JsonPrimitive(path != null))
    if (path != null) {
        put("path", JsonPrimitive(path))
    } else {
        put("error", JsonPrimitive("Screenshot capture failed"))
    }
    put("timestamp", JsonPrimitive(timestamp.toString()))
}

internal fun currentTestStateJson(updateModel: DesktopUpdateScreenModel? = null): String {
    val state = applicationState
    val dlState = downloadState
    val upState = updatesState
    val histState = historyState
    val migrationQueues = MigrationBatchTestBridge.controller?.queues?.value?.size ?: 0
    val downloads = DownloadTestModeBridge.controller?.snapshot()
    val updates = UpdatesTestModeBridge.controller?.snapshot()
    val history = HistoryTestModeBridge.controller?.snapshot()
    val backup = BackupTestModeBridge.controller?.snapshot()
    val settings = SettingsTestModeBridge.controller?.snapshot()
    val tracking = TrackingTestBridge.controller?.snapshot()
    return jsonText(buildJsonObject {
        put("currentScreen", JsonPrimitive(state.currentScreen.value ?: "HomeScreen"))
        put("isLoading", JsonPrimitive(state.isLoading.value))
        put("appLocked", JsonPrimitive(state.appLocked.value))
        put("notifications", JsonArray(emptyList()))
        put("screens", JsonArray(state.screens.value.map(::JsonPrimitive)))
        put("actions", JsonArray(state.actions.value.map(::JsonPrimitive)))
        put("testMode", JsonPrimitive(state.testMode))
        put("downloadQueueSize", JsonPrimitive(downloads?.rows?.size ?: dlState.queueSize))
        put("downloadsPaused", JsonPrimitive(downloads?.paused ?: dlState.isPaused))
        put("updateCount", JsonPrimitive(updates?.rows?.size ?: upState.count))
        put("hasUnreadUpdates", JsonPrimitive(updates?.rows?.any { !it.read } ?: upState.hasUnread))
        put("historyCount", JsonPrimitive(history?.rows?.size ?: histState.count))
        put("migrationQueueCount", JsonPrimitive(migrationQueues))
        val update = updateModel?.state?.value?.presentation()
        put("updateStatus", update?.status?.let(::JsonPrimitive) ?: JsonNull)
        put("updateProgress", update?.progress?.let(::JsonPrimitive) ?: JsonNull)
        put("updateReleasePage", update?.releasePage?.let(::JsonPrimitive) ?: JsonNull)
        put("timestamp", JsonPrimitive(Instant.now().toString()))
        put(
            "updates",
            updates?.let {
                Json.encodeToJsonElement(UpdatesTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "downloads",
            downloads?.let {
                Json.encodeToJsonElement(DownloadTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "history",
            history?.let {
                Json.encodeToJsonElement(HistoryTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "backup",
            backup?.let {
                Json.encodeToJsonElement(BackupTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "settings",
            settings?.let {
                Json.encodeToJsonElement(SettingsTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "tracking",
            tracking?.let {
                Json.encodeToJsonElement(TrackingTestState.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "extension",
            SourceExtensionTestModeBridge.controller?.snapshot()?.let {
                Json.encodeToJsonElement(SourceExtensionTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "browse",
            BrowseSearchTestModeBridge.controller?.snapshot()?.let {
                Json.encodeToJsonElement(BrowseSearchTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "source",
            SourceBrowseTestModeBridge.port?.snapshot()?.let {
                Json.encodeToJsonElement(SourceBrowseTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "library",
            LibraryMangaTestModeBridge.controller?.snapshot()?.let {
                Json.encodeToJsonElement(LibraryTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
        put(
            "detail",
            LibraryMangaTestModeBridge.controller?.detailSnapshot()?.let {
                Json.encodeToJsonElement(MangaDetailTestSnapshot.serializer(), it)
            } ?: JsonNull,
        )
    })
}

private fun actionJson(
    action: String,
    success: Boolean,
    error: String? = null,
    settings: kotlinx.serialization.json.JsonElement = JsonNull,
    backup: kotlinx.serialization.json.JsonElement = JsonNull,
    history: kotlinx.serialization.json.JsonElement = JsonNull,
    updates: kotlinx.serialization.json.JsonElement = JsonNull,
    downloads: kotlinx.serialization.json.JsonElement = JsonNull,
    extension: kotlinx.serialization.json.JsonElement = JsonNull,
    browse: kotlinx.serialization.json.JsonElement = JsonNull,
    source: kotlinx.serialization.json.JsonElement = JsonNull,
    library: kotlinx.serialization.json.JsonElement = JsonNull,
    detail: kotlinx.serialization.json.JsonElement = JsonNull,
    tracking: kotlinx.serialization.json.JsonElement = JsonNull,
) = buildJsonObject {
    put("success", JsonPrimitive(success))
    put("action", JsonPrimitive(action))
    put("error", error?.let(::JsonPrimitive) ?: JsonNull)
    put("timestamp", JsonPrimitive(Instant.now().toString()))
    put("settings", settings)
    put("backup", backup)
    put("history", history)
    put("updates", updates)
    put("downloads", downloads)
    put("extension", extension)
    put("browse", browse)
    put("source", source)
    put("library", library)
    put("detail", detail)
    put("tracking", tracking)
}

/**
 * Configure HTTP test routes.
 */
internal fun Application.testHttpServer(
    updateModel: DesktopUpdateScreenModel? = runCatching { Injekt.get<DesktopUpdateScreenModel>() }.getOrNull(),
    platformAcceptanceController: DesktopPlatformAcceptanceController? = null,
    screenshotCapture: (String) -> String? = ScreenshotService::capture,
) {
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
            ) { currentTestStateJson(updateModel) }
        }

        platformAcceptanceController?.let { controller ->
            post("/test/platform-acceptance/share/{kind}") {
                val kind = when (call.parameters["kind"]?.lowercase()) {
                    "text" -> PlatformShareKind.TEXT
                    "file" -> PlatformShareKind.FILE
                    else -> {
                        call.respondText(
                            """{"accepted":false,"failure":"INVALID_KIND"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                }
                val result = controller.share(
                    call.request.headers[PLATFORM_ACCEPTANCE_TOKEN_HEADER],
                    kind,
                )
                val status = when (result.failure) {
                    null -> HttpStatusCode.OK
                    PlatformAcceptanceFailure.MISSING_TOKEN -> HttpStatusCode.Unauthorized
                    PlatformAcceptanceFailure.INVALID_TOKEN -> HttpStatusCode.Forbidden
                    PlatformAcceptanceFailure.TOKEN_ALREADY_USED -> HttpStatusCode.Conflict
                    PlatformAcceptanceFailure.TERMINAL_TIMEOUT -> HttpStatusCode.GatewayTimeout
                }
                call.respondText(
                    Json.encodeToString(PlatformShareAcceptanceResult.serializer(), result),
                    ContentType.Application.Json,
                    status,
                )
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
                // DEBUG: Record in action history
                applicationState.recordAction(
                    "DEBUG_screenMapCheck",
                    mapOf(
                        "screenId" to screenId,
                        "inMap" to (nestedTestScreenAction(screenId) != null).toString(),
                    ),
                )

                val action = nestedTestScreenAction(screenId)
                if (action != null) {
                    // First navigate to MoreTab
                    TestNavigationController.navigateToTab("MoreTab")
                    // Then trigger the screen navigation via shared state
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

            applicationState.recordAction(action, actionHistoryParams(action, params))

            if (action.startsWith("update_")) {
                val intent = when (action) {
                    "update_check" -> DesktopUpdateIntent.CHECK
                    "update_download" -> DesktopUpdateIntent.DOWNLOAD
                    "update_retry" -> DesktopUpdateIntent.RETRY
                    "update_confirm" -> DesktopUpdateIntent.CONFIRM
                    "update_decline" -> DesktopUpdateIntent.DECLINE
                    "update_cancel" -> DesktopUpdateIntent.CANCEL
                    else -> null
                }
                if (intent == null) {
                    call.respondText(jsonText(actionJson(action, false, "UNKNOWN_UPDATE_ACTION")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                if (updateModel == null) {
                    call.respondText(jsonText(actionJson(action, false, "UPDATE_CONTROLLER_UNAVAILABLE")), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                val accepted = updateModel.intent(intent)
                val status = if (accepted) HttpStatusCode.OK else HttpStatusCode.Conflict
                call.respondText(jsonText(actionJson(action, accepted == true, if (accepted == true) null else "UPDATE_ACTION_REJECTED")), ContentType.Application.Json, status)
                return@post
            }

            val isBrowseAction =
                action == "browse_search" ||
                    action == "browse_select" ||
                    action == "source_login_start"
            val browseController = if (isBrowseAction) BrowseSearchTestModeBridge.controller else null
            if (isBrowseAction && browseController == null) {
                val error =
                    if (action == "source_login_start") {
                        "SOURCE_BROWSE_UNAVAILABLE"
                    } else {
                        "BROWSE_OWNER_UNAVAILABLE"
                    }
                call.respondText(
                    jsonText(actionJson(action, false, error)),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val browseResult = browseController?.execute(action, params)

            val isSourceLoginAction =
                action == "source_login_complete" ||
                    action == "source_login_cancel"
            val sourcePort = if (isSourceLoginAction) SourceBrowseTestModeBridge.port else null
            val browseSourceController =
                if (isSourceLoginAction) BrowseSearchTestModeBridge.controller else null
            if (isSourceLoginAction && sourcePort == null && browseSourceController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "SOURCE_BROWSE_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val browseSourceResult = browseSourceController?.executeSourceLogin(action, params)
            val sourceResult =
                browseSourceResult ?: when {
                    sourcePort == null -> null
                    action == "source_login_complete" ->
                        sourcePort.submit(
                            attemptToken = params["attemptToken"],
                            cookieHeader = params["cookieHeader"],
                        )
                    else -> sourcePort.cancel(params["attemptToken"])
                }
            if (isSourceLoginAction && sourceResult == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "SOURCE_BROWSE_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }

            val isExtensionAction = action.startsWith("extension_")
            val extensionController = if (isExtensionAction) SourceExtensionTestModeBridge.controller else null
            if (isExtensionAction && extensionController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "Extension controller unavailable")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val extensionResult = extensionController?.execute(action, params)
            val isLibraryAction = action in setOf(
                "search", "filter", "sort", "select", "open_manga_detail",
                "addToLibrary", "removeFromLibrary", "detail_categories", "detail_chapter", "detail_cover", "download",
            )
            val libraryController = if (isLibraryAction) LibraryMangaTestModeBridge.controller else null
            if (isLibraryAction && libraryController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "LIBRARY_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val libraryResult = libraryController?.execute(action, params)
            val isDownloadAction = action.startsWith("downloads_")
            val downloadController = if (isDownloadAction) DownloadTestModeBridge.controller else null
            if (isDownloadAction && downloadController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "DOWNLOAD_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val downloadResult = downloadController?.execute(action, params)
            val isUpdatesAction = action.startsWith("updates_")
            val updatesController = if (isUpdatesAction) UpdatesTestModeBridge.controller else null
            if (isUpdatesAction && updatesController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "UPDATES_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val updatesResult = updatesController?.execute(action, params)
            val isHistoryAction = action.startsWith("history_")
            val historyController = if (isHistoryAction) HistoryTestModeBridge.controller else null
            if (isHistoryAction && historyController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "HISTORY_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val historyResult = historyController?.execute(action, params)
            val isBackupAction = action in setOf("backup_create", "backup_restore", "backup_cancel")
            val backupController = if (isBackupAction) BackupTestModeBridge.controller else null
            if (isBackupAction && backupController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "BACKUP_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val backupResult = backupController?.execute(action, params)
            val isSettingsAction = action.startsWith("setting_")
            val settingsController = if (isSettingsAction) SettingsTestModeBridge.controller else null
            if (isSettingsAction && settingsController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "SETTINGS_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val settingsResult = settingsController?.execute(action, params)
            val isTrackingAction = action.startsWith("tracking_")
            val trackingController = if (isTrackingAction) TrackingTestBridge.controller else null
            if (isTrackingAction && trackingController == null) {
                call.respondText(
                    jsonText(actionJson(action, false, "TRACKING_OWNER_UNAVAILABLE")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            val trackingResult = trackingController?.execute(action, params)

            // Process actions
            if (!isExtensionAction && !isLibraryAction && !isBrowseAction && !isSourceLoginAction && !isDownloadAction && !isUpdatesAction && !isHistoryAction && !isBackupAction && !isSettingsAction && !isTrackingAction) when (action) {
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
                // Browse actions
                // Migration actions
                "migration_search", "migration_select" -> { }
                "migration_submit" -> {
                    val mangaId = params["mangaId"]?.toLongOrNull() ?: 0L
                    if (mangaId > 0) {
                        MigrationBatchTestBridge.controller?.submit(
                            listOf(BatchMigrationRequest(mangaId, params["title"] ?: "Manga $mangaId")),
                        )
                    }
                }
                "migration_pause" -> params["queueId"]?.let { MigrationBatchTestBridge.controller?.pause(it) }
                "migration_resume" -> params["queueId"]?.let { MigrationBatchTestBridge.controller?.resume(it) }
                "migration_cancel_all" -> params["queueId"]?.let { MigrationBatchTestBridge.controller?.cancelAll(it) }
                "migration_cancel_item" -> {
                    val mangaId = params["mangaId"]?.toLongOrNull()
                    val queueId = params["queueId"]
                    if (mangaId != null && queueId != null) MigrationBatchTestBridge.controller?.cancelItem(queueId, mangaId)
                }
                "migration_retry" -> {
                    val mangaId = params["mangaId"]?.toLongOrNull()
                    val queueId = params["queueId"]
                    if (mangaId != null && queueId != null) MigrationBatchTestBridge.controller?.retryItem(queueId, mangaId)
                }
            }

            call.respondText(
                contentType = ContentType.Application.Json,
                status = when (trackingResult?.failureCode) {
                    TrackingTestFailureCode.INVALID_PARAMETER,
                    TrackingTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    TrackingTestFailureCode.OPERATION_IN_PROGRESS,
                    TrackingTestFailureCode.OPERATION_REJECTED,
                    -> HttpStatusCode.Conflict
                    TrackingTestFailureCode.SERVICE_UNAVAILABLE,
                    TrackingTestFailureCode.OWNER_CLOSED,
                    -> HttpStatusCode.ServiceUnavailable
                    null -> when (settingsResult?.failureCode) {
                    SettingsTestFailureCode.MISSING_PARAMETER,
                    SettingsTestFailureCode.INVALID_PARAMETER,
                    SettingsTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    SettingsTestFailureCode.ROW_NOT_FOUND -> HttpStatusCode.NotFound
                    SettingsTestFailureCode.CONFIRMATION_REQUIRED,
                    SettingsTestFailureCode.AUTHENTICATION_FAILED,
                    SettingsTestFailureCode.OPERATION_IN_PROGRESS,
                    SettingsTestFailureCode.OPERATION_REJECTED,
                    SettingsTestFailureCode.NAVIGATION_REJECTED,
                    -> HttpStatusCode.Conflict
                    SettingsTestFailureCode.BACKEND_UNAVAILABLE,
                    SettingsTestFailureCode.PORT_FAILURE,
                    SettingsTestFailureCode.OWNER_CLOSED,
                    -> HttpStatusCode.ServiceUnavailable
                    null -> when (backupResult?.failureCode) {
                    BackupTestFailureCode.MISSING_PARAMETER,
                    BackupTestFailureCode.INVALID_PARAMETER,
                    BackupTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    BackupTestFailureCode.CONFIRMATION_REQUIRED,
                    BackupTestFailureCode.OPERATION_IN_PROGRESS,
                    BackupTestFailureCode.OPERATION_REJECTED,
                    BackupTestFailureCode.WORKFLOW_FAILED,
                    BackupTestFailureCode.PARTIAL_FAILURE,
                    -> HttpStatusCode.Conflict
                    BackupTestFailureCode.OWNER_CLOSED -> HttpStatusCode.ServiceUnavailable
                    null -> when (historyResult?.failureCode) {
                    TimelineTestFailureCode.MISSING_PARAMETER,
                    TimelineTestFailureCode.INVALID_PARAMETER,
                    TimelineTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    TimelineTestFailureCode.ROW_NOT_FOUND -> HttpStatusCode.NotFound
                    TimelineTestFailureCode.OPERATION_REJECTED,
                    TimelineTestFailureCode.PARTIAL_FAILURE,
                    -> HttpStatusCode.Conflict
                    TimelineTestFailureCode.OWNER_CLOSED -> HttpStatusCode.ServiceUnavailable
                    null -> when (updatesResult?.failureCode) {
                    TimelineTestFailureCode.MISSING_PARAMETER,
                    TimelineTestFailureCode.INVALID_PARAMETER,
                    TimelineTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    TimelineTestFailureCode.ROW_NOT_FOUND -> HttpStatusCode.NotFound
                    TimelineTestFailureCode.OPERATION_REJECTED,
                    TimelineTestFailureCode.PARTIAL_FAILURE,
                    -> HttpStatusCode.Conflict
                    TimelineTestFailureCode.OWNER_CLOSED -> HttpStatusCode.ServiceUnavailable
                    null -> when (downloadResult?.failureCode) {
                    DownloadTestFailureCode.MISSING_PARAMETER,
                    DownloadTestFailureCode.INVALID_PARAMETER,
                    DownloadTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    DownloadTestFailureCode.ROW_NOT_FOUND -> HttpStatusCode.NotFound
                    DownloadTestFailureCode.OPERATION_REJECTED,
                    DownloadTestFailureCode.PARTIAL_FAILURE,
                    -> HttpStatusCode.Conflict
                    DownloadTestFailureCode.OWNER_CLOSED -> HttpStatusCode.ServiceUnavailable
                    null -> when (browseResult?.failureCode) {
                    BrowseSearchTestFailureCode.MISSING_PARAMETER,
                    BrowseSearchTestFailureCode.BLANK_QUERY,
                    BrowseSearchTestFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    BrowseSearchTestFailureCode.SOURCE_NOT_FOUND,
                    BrowseSearchTestFailureCode.ROW_NOT_FOUND,
                    -> HttpStatusCode.NotFound
                    BrowseSearchTestFailureCode.OWNER_CLOSED -> HttpStatusCode.ServiceUnavailable
                    BrowseSearchTestFailureCode.STALE_GENERATION,
                    BrowseSearchTestFailureCode.RECOVERY_UNAVAILABLE,
                    BrowseSearchTestFailureCode.OPERATION_REJECTED,
                    -> HttpStatusCode.Conflict
                    null -> when (sourceResult?.failureCode) {
                    SourceBrowseTestFailureCode.MISSING_TOKEN,
                    SourceBrowseTestFailureCode.MISSING_HEADER,
                    -> HttpStatusCode.BadRequest
                    SourceBrowseTestFailureCode.STALE_GENERATION,
                    SourceBrowseTestFailureCode.NO_ACTIVE_LOGIN,
                    SourceBrowseTestFailureCode.ATTEMPT_MISMATCH,
                    SourceBrowseTestFailureCode.TERMINAL,
                    SourceBrowseTestFailureCode.OPERATION_REJECTED,
                    SourceBrowseTestFailureCode.PORT_CLOSED,
                    -> HttpStatusCode.Conflict
                    null -> when (extensionResult?.failureCode) {
                    SourceExtensionActionFailureCode.MISSING_PARAMETER,
                    SourceExtensionActionFailureCode.UNSUPPORTED_ACTION,
                    -> HttpStatusCode.BadRequest
                    SourceExtensionActionFailureCode.UNKNOWN_PACKAGE -> HttpStatusCode.NotFound
                    SourceExtensionActionFailureCode.ACTION_UNAVAILABLE,
                    SourceExtensionActionFailureCode.NO_PENDING_TRUST,
                    SourceExtensionActionFailureCode.TRUST_PACKAGE_MISMATCH,
                    SourceExtensionActionFailureCode.OPERATION_REJECTED,
                    -> HttpStatusCode.Conflict
                        null -> when (libraryResult?.failureCode) {
                            LibraryMangaActionFailureCode.MISSING_PARAMETER,
                            LibraryMangaActionFailureCode.INVALID_PARAMETER,
                            LibraryMangaActionFailureCode.UNSUPPORTED_ACTION,
                            -> HttpStatusCode.BadRequest
                            LibraryMangaActionFailureCode.ROW_NOT_FOUND,
                            LibraryMangaActionFailureCode.DETAIL_NOT_FOUND,
                            -> HttpStatusCode.NotFound
                            LibraryMangaActionFailureCode.DETAIL_NOT_OPEN,
                            LibraryMangaActionFailureCode.ACTION_UNAVAILABLE,
                            LibraryMangaActionFailureCode.OPERATION_REJECTED,
                            LibraryMangaActionFailureCode.PARTIAL_FAILURE,
                            LibraryMangaActionFailureCode.LIBRARY_LOADING,
                            LibraryMangaActionFailureCode.DETAIL_LOADING,
                            -> HttpStatusCode.Conflict
                            LibraryMangaActionFailureCode.LIBRARY_UNAVAILABLE,
                            LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED,
                            LibraryMangaActionFailureCode.PORT_CLOSED,
                            -> HttpStatusCode.ServiceUnavailable
                            null -> HttpStatusCode.OK
                        }
                    }
                    }
                    }
                    }
                    }
                    }
                    }
                    }
                },
            ) {
                jsonText(
                    actionJson(
                        action = action,
                        success = trackingResult?.success
                            ?: settingsResult?.success
                            ?: backupResult?.success
                            ?: historyResult?.success
                            ?: updatesResult?.success
                            ?: downloadResult?.success
                            ?: browseResult?.success
                            ?: sourceResult?.success
                            ?: extensionResult?.success
                            ?: libraryResult?.success
                            ?: true,
                        error = trackingResult?.failureCode?.name
                            ?: settingsResult?.failureCode?.name
                            ?: backupResult?.failureCode?.name
                            ?: historyResult?.failureCode?.name
                            ?: updatesResult?.failureCode?.name
                            ?: downloadResult?.failureCode?.name
                            ?: browseResult?.failureCode?.name
                            ?: sourceResult?.failureCode?.name
                            ?: extensionResult?.failureCode?.name
                            ?: libraryResult?.failureCode?.name,
                        settings = settingsResult?.let {
                            Json.encodeToJsonElement(SettingsTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        backup = backupResult?.let {
                            Json.encodeToJsonElement(BackupTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        history = historyResult?.let {
                            Json.encodeToJsonElement(HistoryTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        updates = updatesResult?.let {
                            Json.encodeToJsonElement(UpdatesTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        downloads = downloadResult?.let {
                            Json.encodeToJsonElement(DownloadTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        extension = extensionResult?.let {
                            Json.encodeToJsonElement(SourceExtensionTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        browse = browseResult?.let {
                            Json.encodeToJsonElement(BrowseSearchTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        source = sourceResult?.let {
                            Json.encodeToJsonElement(SourceBrowseTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        library = libraryResult?.let {
                            Json.encodeToJsonElement(LibraryTestSnapshot.serializer(), it.snapshot)
                        } ?: JsonNull,
                        detail = libraryController?.detailSnapshot()?.let {
                            Json.encodeToJsonElement(MangaDetailTestSnapshot.serializer(), it)
                        } ?: JsonNull,
                        tracking = trackingResult?.let {
                            Json.encodeToJsonElement(TrackingTestState.serializer(), it.snapshot)
                        } ?: JsonNull,
                    ),
                )
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

            val path = screenshotCapture(name)
            call.respondText(
                text = screenshotResponse(path).toString(),
                contentType = ContentType.Application.Json,
                status = if (path != null) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
            )
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
                buildJsonArray {
                    applicationState.actionHistory.value.forEach { record ->
                        add(
                            buildJsonObject {
                                put("action", JsonPrimitive(record.action))
                                put(
                                    "params",
                                    buildJsonObject {
                                        record.params.forEach { (key, value) ->
                                            put(key, JsonPrimitive(value.toString()))
                                        }
                                    },
                                )
                                put("timestamp", JsonPrimitive(record.timestamp.toString()))
                            },
                        )
                    }
                }.toString()
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
