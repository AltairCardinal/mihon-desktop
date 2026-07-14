package mihon.desktop.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.task.DesktopTaskScheduler
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import tachiyomi.domain.track.interactor.TrackerSyncRetryScheduler
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DesktopTrackerSyncScheduler(
    private val scheduler: DesktopTaskScheduler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sync: () -> ReadingProgressTrackSync,
) : TrackerSyncRetryScheduler, DesktopRuntimeService {
    private var worker: Job? = null
    private var started = false

    override suspend fun schedule(request: TrackerSyncRequest) {
        scheduler.register(
            BackgroundTask(
                id = taskId(request),
                idempotencyKey = "tracker-sync:${request.idempotencyKey}:${request.attempt}",
                constraints = setOf(TaskConstraint.NetworkConnected),
                checkpoint = TaskCheckpoint(cursor = request.encode()),
            ),
        )
        launchPending()
    }

    suspend fun runPending() {
        scheduler.pendingTasks().filter { it.id.startsWith(PREFIX) }.forEach { task ->
            val request = task.checkpoint?.cursor?.decodeRequest() ?: return@forEach
            scheduler.start(task.id)
            runCatching { sync().sync(request) }
                .onSuccess { scheduler.complete(task.id) }
        }
    }

    override fun start() {
        if (started) return
        started = true
        launchPending()
    }

    override fun stop() {
        started = false
        worker?.cancel()
        worker = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun launchPending(delayMillis: Long = 0) {
        if (!started || worker?.isActive == true) return
        worker = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            runPending()
            worker = null
            if (started && scheduler.pendingTasks().any { it.id.startsWith(PREFIX) }) {
                launchPending(RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun taskId(request: TrackerSyncRequest) =
        "$PREFIX${"${request.idempotencyKey}:${request.attempt}".hashCode().toUInt()}"

    private fun TrackerSyncRequest.encode() = listOf(
        URLEncoder.encode(eventId, StandardCharsets.UTF_8),
        mangaId,
        chapterNumber,
        trackerId ?: "",
        attempt,
    ).joinToString("|")

    private fun String.decodeRequest(): TrackerSyncRequest {
        val values = split('|')
        require(values.size == 5) { "Invalid tracker sync checkpoint" }
        return TrackerSyncRequest(
            eventId = URLDecoder.decode(values[0], StandardCharsets.UTF_8),
            mangaId = values[1].toLong(),
            chapterNumber = values[2].toDouble(),
            trackerId = values[3].takeIf(String::isNotEmpty)?.toLong(),
            attempt = values[4].toInt(),
        )
    }

    private companion object {
        const val PREFIX = "tracker-sync-"
        const val RETRY_DELAY_MILLIS = 30_000L
    }
}
