package mihon.desktop.test.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.domain.sync.security.SyncSecureStore
import java.util.UUID

/** Test Mode only: observe the native controller without serializing credentials or recovery material. */
internal fun Route.syncTestRoutes(panel: SyncPanel?, secureStore: SyncSecureStore?) {
    get("/test/sync") {
        val state = panel?.state?.value
        if (state == null) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val snapshot = buildJsonObject {
            put("visible", state.visible)
            put("loaded", state.loaded)
            put("page", state.page.name)
            put("busy", state.busy)
            put("connected", state.connection?.enabled == true)
            put("queuedTotal", state.queuedTotal)
            put("pendingTotal", state.pendingTotal)
            put("setupStep", state.setupStep.name)
            put("setupBusy", state.setupBusy)
            put("repositoryCount", state.repositories.size)
            put("problem", state.problem?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("authFailure", state.authFailure?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("lastSuccessMillis", state.lastSuccessMillis)
            state.notice?.exchange?.let { result ->
                putJsonObject("lastExchange") {
                    put("status", result.status.name)
                    put("uploaded", result.uploaded)
                    put("downloaded", result.downloaded)
                    put("pending", result.pending)
                }
            }
        }
        call.respondText(snapshot.toString(), ContentType.Application.Json)
    }
    // Explicit transient handoff only. Never return the device secret used for token polling.
    get("/test/sync/authorization") {
        val code = panel?.state?.value?.deviceCode
        if (code == null) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.NoContent)
        } else {
            call.respondText(
                buildJsonObject {
                    put("userCode", code.userCode)
                    put("verificationUri", code.verificationUri)
                }.toString(),
                ContentType.Application.Json,
            )
        }
    }
    post("/test/sync/{action}") {
        val action = when (call.parameters["action"]) {
            "open" -> SyncPanelAction.Open
            "close" -> SyncPanelAction.Close
            "setup" -> SyncPanelAction.BeginSetup
            "authorize" -> SyncPanelAction.Authorize
            "cancel_authorization" -> SyncPanelAction.CancelAuthorization
            "refresh_repositories" -> SyncPanelAction.RefreshRepositories
            "synchronize" -> SyncPanelAction.Synchronize
            "cancel_sync" -> SyncPanelAction.CancelSync
            else -> null
        }
        val status = when {
            action == null -> HttpStatusCode.BadRequest
            panel == null -> HttpStatusCode.ServiceUnavailable
            else -> {
                panel.dispatch(action)
                HttpStatusCode.Accepted
            }
        }
        call.respondText("{}", ContentType.Application.Json, status)
    }
    // Synthetic records exercise the release application's actual OS-backed store across restarts.
    // Only this reserved prefix can be touched; real auth/space records are never accepted as input.
    post("/test/sync/probe/write") {
        if (secureStore == null) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@post
        }
        var stage = "WRITE"
        try {
            val id = UUID.randomUUID().toString()
            check(secureStore.compareAndSet(probeKey(id), null, probeValue(id)))
            stage = "READ"
            check(secureStore.read(probeKey(id)) == probeValue(id))
            call.respondText(
                buildJsonObject { put("id", id) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            call.respondText(
                probeFailure(stage, failure),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
    post("/test/sync/probe/verify/{id}") {
        val id = call.parameters["id"]
        if (id == null || !runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        if (secureStore == null) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@post
        }
        var stage = "VERIFY"
        try {
            val matches = secureStore.read(probeKey(id)) == probeValue(id)
            stage = "REMOVE"
            val removed = matches && secureStore.compareAndSet(probeKey(id), probeValue(id), null)
            call.respondText(
                "{}",
                ContentType.Application.Json,
                if (removed) HttpStatusCode.OK else HttpStatusCode.Conflict,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            call.respondText(
                probeFailure(stage, failure),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}

private fun probeKey(id: String) = "runtime-probe-$id"
private fun probeValue(id: String) = "synthetic-sync-runtime-value:$id"
private fun probeFailure(stage: String, error: Exception) = buildJsonObject {
    put("stage", stage)
    put("failureType", error.javaClass.simpleName)
}.toString()
