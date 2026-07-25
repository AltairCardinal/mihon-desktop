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
import mihon.desktop.task.StoredTask
import mihon.domain.error.AppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskStatus
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.ReadingProgressTrackRetryConsumer
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import tachiyomi.domain.track.interactor.TrackerSyncRetryScheduler
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.DelayedTrackerSyncPersistence
import tachiyomi.domain.track.service.DelayedTrackerSyncQueue
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderSession
import tachiyomi.domain.track.service.mergeHighest
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets

fun interface DesktopNetworkConnectivity {
    fun isConnected(): Boolean
}

object JvmDesktopNetworkConnectivity : DesktopNetworkConnectivity {
    override fun isConnected(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { network ->
            network.isUp && !network.isLoopback && network.inetAddresses.asSequence().any()
        }
    }.getOrDefault(false)
}

class DesktopTrackerSyncScheduler(
    private val scheduler: DesktopTaskScheduler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectivity: DesktopNetworkConnectivity = JvmDesktopNetworkConnectivity,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val sync: () -> ReadingProgressTrackSync,
) : TrackerSyncRetryScheduler, DelayedTrackerSyncPersistence, DesktopRuntimeService {
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
        scheduler.allTasks()
            .filter { it.task.id.startsWith(PREFIX) && it.status in runnableStatuses }
            .forEach { stored ->
                val task = stored.task
                if (TaskConstraint.NetworkConnected in task.constraints && !connectivity.isConnected()) {
                    return@forEach
                }
                val checkpoint = task.checkpoint?.cursor?.decodeCheckpoint()
                    ?: TrackerCheckpoint.Malformed(IllegalArgumentException("Missing tracker sync checkpoint"))
                when (checkpoint) {
                    is TrackerCheckpoint.Shared -> runSharedItem(task.id, checkpoint.item)
                    is TrackerCheckpoint.Legacy -> runLegacyRequest(task.id, checkpoint.request)
                    is TrackerCheckpoint.Malformed -> {
                        scheduler.start(task.id)
                        scheduler.fail(task.id, AppError.MalformedData(checkpoint.cause))
                    }
                }
            }
    }

    override suspend fun getItems(): List<DelayedTrackerSyncItem> =
        sharedItems(scheduler.allTasks()).map { it.second }

    override suspend fun upsertMax(item: DelayedTrackerSyncItem): DelayedTrackerSyncItem {
        requireNotNull(item.eventId) { "Desktop tracker checkpoint requires an event id" }
        val merged = scheduler.transaction { tasks ->
            val id = sharedTaskId(item.trackId)
            val index = tasks.indexOfFirst { it.task.id == id }
            val current = tasks.getOrNull(index)?.task?.checkpoint?.cursor?.decodeSharedItemOrNull()
            val merged = current?.mergeHighest(item) ?: item
            val task = BackgroundTask(
                id = id,
                idempotencyKey = "tracker-sync:track:${item.trackId}",
                constraints = setOf(TaskConstraint.NetworkConnected),
                checkpoint = TaskCheckpoint(cursor = merged.encodeItem()),
            )
            val replacement = tasks.getOrNull(index)?.copy(
                task = task,
                status = TaskStatus.Pending,
                failure = null,
                failedUnits = emptyList(),
            ) ?: StoredTask(task)
            if (index >= 0) tasks[index] = replacement else tasks += replacement
            merged
        }
        launchPending()
        return merged
    }

    override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double): Boolean =
        scheduler.transaction { tasks ->
            val index = tasks.indexOfFirst { it.task.id == sharedTaskId(trackId) }
            if (index < 0) return@transaction false
            val current = tasks[index].task.checkpoint?.cursor?.decodeSharedItemOrNull()
                ?: return@transaction false
            if (current.lastChapterRead > lastChapterRead) return@transaction false
            tasks[index] = tasks[index].copy(status = TaskStatus.Completed)
            true
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
            try {
                if (delayMillis > 0) delay(delayMillis)
                runPending()
            } finally {
                worker = null
                if (started && hasRetryablePending()) {
                    launchPending(retryDelayMillis)
                }
            }
        }
    }

    private suspend fun runLegacyRequest(taskId: String, request: TrackerSyncRequest) {
        scheduler.start(taskId)
        try {
            sync().sync(request)
            scheduler.complete(taskId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            scheduler.fail(taskId, AppError.Unknown(error))
        }
    }

    private suspend fun runSharedItem(taskId: String, item: DelayedTrackerSyncItem) {
        if (item.attempt > DelayedTrackerSyncQueue.MAX_RUN_ATTEMPT_COUNT) {
            terminalQueue().markRetryExhausted(item.trackId)
            val reason = getItems().firstOrNull { it.trackId == item.trackId }?.failureReason
            scheduler.start(taskId)
            scheduler.fail(taskId, reason.toAppError())
            return
        }
        scheduler.start(taskId)
        try {
            val retryConsumer = sync()
            require(retryConsumer is ReadingProgressTrackRetryConsumer) {
                "Desktop tracker retries require the shared retry consumer"
            }
            retryConsumer.drain(item)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            upsertMax(
                item.copy(
                    attempt = item.attempt + 1,
                    failureReason = item.failureReason ?: TrackerProviderErrorKind.UNKNOWN.name,
                ),
            )
        }
    }

    private fun sharedItems(tasks: List<StoredTask>): List<Pair<String, DelayedTrackerSyncItem>> =
        tasks.asSequence()
            .filter { it.task.id.startsWith(PREFIX) && it.status !in completedStatuses }
            .mapNotNull { stored ->
                stored.task.checkpoint?.cursor?.decodeSharedItemOrNull()?.let { stored.task.id to it }
            }
            .toList()

    private fun hasRetryablePending() = scheduler.allTasks().any { stored ->
        if (stored.task.id.startsWith(PREFIX).not() || stored.status !in runnableStatuses) return@any false
        when (val checkpoint = stored.task.checkpoint?.cursor?.decodeCheckpoint()) {
            is TrackerCheckpoint.Shared ->
                checkpoint.item.attempt <= DelayedTrackerSyncQueue.MAX_RUN_ATTEMPT_COUNT + 1
            is TrackerCheckpoint.Legacy, is TrackerCheckpoint.Malformed, null -> true
        }
    }

    private fun terminalQueue() = DelayedTrackerSyncQueue(
        persistence = this,
        session = { TrackerProviderSession(it, false) },
        execute = { _: TrackerProviderRequest.Edit -> TrackerProviderResult.Success() },
    )

    private fun taskId(request: TrackerSyncRequest) =
        "$PREFIX${"${request.idempotencyKey}:${request.attempt}".hashCode().toUInt()}"

    private fun sharedTaskId(trackId: Long) = "$PREFIX$trackId"

    private fun DelayedTrackerSyncItem.encodeItem() = listOf(
        ITEM_VERSION,
        trackId,
        mangaId,
        trackerId,
        lastChapterRead,
        URLEncoder.encode(requireNotNull(eventId), StandardCharsets.UTF_8),
        attempt,
        URLEncoder.encode(failureReason.orEmpty(), StandardCharsets.UTF_8),
    ).joinToString("|")

    private fun String.decodeItem(): DelayedTrackerSyncItem {
        val values = split('|')
        require(values.firstOrNull() == ITEM_VERSION && values.size == 8) {
            "Invalid shared tracker sync checkpoint"
        }
        return DelayedTrackerSyncItem(
            trackId = values[1].toLong(),
            mangaId = values[2].toLong(),
            trackerId = values[3].toLong(),
            lastChapterRead = values[4].toDouble(),
            eventId = URLDecoder.decode(values[5], StandardCharsets.UTF_8),
            attempt = values[6].toInt(),
            failureReason = URLDecoder.decode(values[7], StandardCharsets.UTF_8).takeIf(String::isNotEmpty),
        )
    }

    private fun String.decodeSharedItemOrNull(): DelayedTrackerSyncItem? =
        if (substringBefore('|') == ITEM_VERSION) runCatching { decodeItem() }.getOrNull() else null

    private fun String.decodeCheckpoint(): TrackerCheckpoint =
        if (substringBefore('|') == ITEM_VERSION) {
            runCatching { TrackerCheckpoint.Shared(decodeItem()) }
                .getOrElse { TrackerCheckpoint.Malformed(it) }
        } else {
            runCatching { TrackerCheckpoint.Legacy(decodeRequest()) }
                .getOrElse { TrackerCheckpoint.Malformed(it) }
        }

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

    private fun String?.toAppError(): AppError = when (this?.substringBefore(':')) {
        "NETWORK" -> AppError.Network()
        "AUTHENTICATION" -> AppError.Authentication()
        "RATE_LIMITED" -> AppError.RateLimited()
        "SERVER" -> AppError.Server(this.substringAfter(':', "500").toIntOrNull() ?: 500)
        else -> AppError.Unknown()
    }

    private companion object {
        const val PREFIX = "tracker-sync-"
        const val ITEM_VERSION = "item-v1"
        const val RETRY_DELAY_MILLIS = 30_000L
        val runnableStatuses = setOf(TaskStatus.Pending, TaskStatus.Running)
        val completedStatuses = setOf(TaskStatus.Completed, TaskStatus.Cancelled)
    }

    private sealed interface TrackerCheckpoint {
        data class Shared(val item: DelayedTrackerSyncItem) : TrackerCheckpoint
        data class Legacy(val request: TrackerSyncRequest) : TrackerCheckpoint
        data class Malformed(val cause: Throwable) : TrackerCheckpoint
    }
}
