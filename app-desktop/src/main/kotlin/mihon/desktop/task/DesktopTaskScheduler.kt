package mihon.desktop.task

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import mihon.desktop.platform.retryTransientAccessDenied
import mihon.domain.error.AppError
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.BackgroundTaskLifecycle
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskLifecycleEvent
import mihon.domain.task.TaskLifecycleOutcome
import mihon.domain.task.TaskOccurrence
import mihon.domain.task.TaskStatus
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class StoredTask(
    val task: BackgroundTask,
    val status: TaskStatus = TaskStatus.Pending,
    @Serializable(with = StoredAppErrorCompatSerializer::class)
    val failure: StoredAppError? = null,
    val failedUnits: List<String> = emptyList(),
    val workset: List<Long> = emptyList(),
    val worksetInitialized: Boolean = false,
    val completedUnitIds: Set<Long> = emptySet(),
    val libraryUpdate: LibraryUpdateContext? = null,
)

@Serializable
enum class LibraryUpdateTrigger { MANUAL, SCHEDULED }

@Serializable
enum class LibraryUpdateScope { ALL, CATEGORY, SINGLE }

@Serializable
enum class LibraryUnitStatus { SUCCESS, FAILED, SKIPPED, UNPROCESSED }

@Serializable
data class LibraryUpdateUnit(
    val mangaId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val status: LibraryUnitStatus = LibraryUnitStatus.UNPROCESSED,
    val newChapterCount: Int = 0,
    val message: String? = null,
    val title: String = "",
    val failure: StoredAppError? = null,
    val skipReason: tachiyomi.domain.library.service.LibraryUpdateSkipReason? = null,
    val localSource: Boolean = false,
    val sourceUnavailable: Boolean = false,
)

@Serializable
data class LibraryUpdateContext(
    val trigger: LibraryUpdateTrigger,
    val scope: LibraryUpdateScope,
    val categoryId: Long? = null,
    val units: List<LibraryUpdateUnit>,
    val restrictions: Set<String> = emptySet(),
    val fetchWindowUpperBound: Long = Long.MAX_VALUE,
    val checkStartedAt: Long? = null,
    val singleMangaId: Long? = null,
    val deviceRestrictions: Set<String> = emptySet(),
    val waitingForDevice: Map<String, String> = emptyMap(),
    val periodicCheckStartedAt: Long? = null,
)

object StoredAppErrorCompatSerializer : KSerializer<StoredAppError?> {
    override val descriptor: SerialDescriptor = StoredAppError.serializer().descriptor

    override fun deserialize(decoder: Decoder): StoredAppError? {
        require(decoder is JsonDecoder)
        return when (val element = decoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonObject -> decoder.json.decodeFromJsonElement(StoredAppError.serializer(), element)
            is JsonPrimitive -> {
                require(element.isString) { "Stored task failure must be a string, object, or null" }
                StoredAppError(type = "Unknown", message = element.content)
            }
            else -> error("Stored task failure must be a string, object, or null")
        }
    }

    override fun serialize(encoder: Encoder, value: StoredAppError?) {
        require(encoder is JsonEncoder)
        val element = value?.let { encoder.json.encodeToJsonElement(StoredAppError.serializer(), it) } ?: JsonNull
        encoder.encodeJsonElement(element)
    }
}

class FileTaskCheckpointStore(
    private val file: Path,
    private val atomicMove: (Path, Path) -> Boolean = ::moveAtomically,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = locks.computeIfAbsent(file.toAbsolutePath().normalize()) { ReentrantLock() }
    private val diagnosticMessages = mutableListOf<String>()

    fun load(): List<StoredTask> = lock.withLock { loadUnlocked() }

    fun <R> transaction(transform: (MutableList<StoredTask>) -> R): R = lock.withLock {
        val tasks = loadUnlocked().toMutableList()
        val result = transform(tasks)
        saveUnlocked(tasks)
        result
    }

    fun update(transform: (MutableList<StoredTask>) -> Unit) = transaction(transform)

    fun diagnostics(): List<String> = lock.withLock { diagnosticMessages.toList() }

    private fun loadUnlocked(): List<StoredTask> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString(Files.readString(file))
        } catch (error: Exception) {
            val corrupt = file.resolveSibling("${file.fileName}.corrupt-${UUID.randomUUID()}")
            runCatching { Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING) }
            diagnosticMessages += "corrupt task store quarantined: ${error.message}"
            emptyList()
        }
    }

    private fun saveUnlocked(tasks: List<StoredTask>) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, json.encodeToString(tasks))
        try {
            replaceWithRetry(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun replaceWithRetry(temporary: Path) {
        retryTransientAccessDenied {
            if (!atomicMove(temporary, file)) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()

        private fun moveAtomically(source: Path, target: Path): Boolean = try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            false
        }
    }
}

class DesktopTaskScheduler(private val store: FileTaskCheckpointStore) {
    private val published = MutableStateFlow(store.load())
    private val publicationLock = Any()

    fun observe(id: String): Flow<StoredTask?> = published.map { tasks -> tasks.firstOrNull { it.task.id == id } }
        .distinctUntilChanged()

    fun <R> transaction(transform: (MutableList<StoredTask>) -> R): R = synchronized(publicationLock) {
        val result = store.transaction(transform)
        published.value = store.load()
        result
    }

    fun register(task: BackgroundTask): StoredTask {
        var result: StoredTask? = null
        transaction { tasks ->
            val existing = tasks.firstOrNull { it.task.idempotencyKey == task.idempotencyKey }
                ?: tasks.firstOrNull { it.task.id == task.id }
            val decision = BackgroundTaskLifecycle.reduce(
                existing?.toOccurrence(),
                TaskLifecycleEvent.Register(task),
            )
            result = when (decision.outcome) {
                TaskLifecycleOutcome.Applied -> {
                    val replacement = if (existing?.status == TaskStatus.Failed) {
                        StoredTask(
                            task = task,
                            workset = existing.workset,
                            worksetInitialized = existing.worksetInitialized,
                            completedUnitIds = existing.completedUnitIds,
                        )
                    } else {
                        StoredTask(task)
                    }
                    tasks.removeAll { it.task.id == task.id && it.status in terminalStatuses }
                    tasks += replacement
                    replacement
                }
                TaskLifecycleOutcome.AlreadyApplied,
                TaskLifecycleOutcome.Rejected,
                -> existing
            }
        }
        return checkNotNull(result)
    }

    /** Library refreshes own their finite workset; generic tasks retain their existing registration rules. */
    fun beginLibraryUpdate(
        task: BackgroundTask,
        context: LibraryUpdateContext,
        initialized: Boolean = true,
    ): StoredTask = transaction { tasks ->
        val replacement = StoredTask(
            task = task,
            workset = context.units.map { it.mangaId },
            worksetInitialized = initialized,
            libraryUpdate = context,
        )
        tasks.removeAll { it.task.id == task.id }
        tasks += replacement
        replacement
    }

    fun initializeLibraryUpdate(id: String, occurrence: String, context: LibraryUpdateContext): Boolean = transition(
        id,
    ) {
        if (it.status != TaskStatus.Running || it.task.idempotencyKey != occurrence || it.worksetInitialized) {
            it
        } else {
            it.copy(
                workset = context.units.map { unit ->
                    unit.mangaId
                },
                worksetInitialized = true,
                libraryUpdate = context,
            )
        }
    }

    fun recordLibraryDeviceBoundary(
        id: String,
        occurrence: String,
        waiting: Map<String, String>,
        checkingAt: Long? = null,
    ): Boolean = transition(id) {
        val context = it.libraryUpdate
        if (it.status != TaskStatus.Running || it.task.idempotencyKey != occurrence || context == null) {
            it
        } else {
            it.copy(
                libraryUpdate = context.copy(
                    waitingForDevice = waiting,
                    periodicCheckStartedAt = context.periodicCheckStartedAt ?: checkingAt,
                ),
            )
        }
    }

    /** Explicit recovery may checkpoint a committed unit while keeping an occurrence's terminal status intact. */
    fun confirmLibraryReceipt(phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase): Boolean {
        val receipt = phase.effects.taskReceipt ?: return false
        if (!phase.effectsComplete || !phase.checkpointPending || receipt.unitId != phase.mangaId) return false
        val current = snapshot("library-update") ?: return false
        val context = current.libraryUpdate ?: return false
        val original = context.units.singleOrNull { it.mangaId == receipt.unitId } ?: return false
        if (current.task.idempotencyKey != receipt.occurrenceKey || !current.worksetInitialized ||
            current.workset != context.units.map { it.mangaId } || original.sourceId != phase.effects.sourceId ||
            original.mangaUrl != phase.effects.mangaUrl
        ) {
            return false
        }
        if (original.status == LibraryUnitStatus.SUCCESS) return original.newChapterCount == phase.addedIds.size
        return transition(current.task.id) { latest ->
            if (latest != current) {
                latest
            } else {
                val units = context.units.map { unit ->
                    if (unit.mangaId == receipt.unitId) {
                        unit.copy(
                            status = LibraryUnitStatus.SUCCESS,
                            newChapterCount = phase.addedIds.size,
                            message = null,
                            failure = null,
                            sourceUnavailable = false,
                            localSource = false,
                            skipReason = null,
                        )
                    } else {
                        unit
                    }
                }
                val completed = units.filter {
                    it.status in setOf(LibraryUnitStatus.SUCCESS, LibraryUnitStatus.SKIPPED)
                }
                latest.copy(
                    libraryUpdate = context.copy(units = units),
                    failedUnits = units.filter { it.status == LibraryUnitStatus.FAILED }.map { "manga:${it.mangaId}" },
                    completedUnitIds = completed.map { it.mangaId }.toSet(),
                    task = latest.task.copy(
                        checkpoint = TaskCheckpoint(
                            receipt.unitId.toString(),
                            completed.size,
                            completed.size.toFloat() / units.size,
                        ),
                    ),
                )
            }
        }
    }

    fun recordLibraryUnit(
        id: String,
        occurrence: String,
        unit: LibraryUpdateUnit,
    ): Boolean = transition(id) { current ->
        val context = current.libraryUpdate
        if (current.status != TaskStatus.Running || current.task.idempotencyKey != occurrence ||
            context == null || context.units.none {
                it.mangaId == unit.mangaId && it.sourceId == unit.sourceId && it.mangaUrl == unit.mangaUrl
            }
        ) {
            current
        } else {
            val updated = context.units.map { if (it.mangaId == unit.mangaId) unit else it }
            val completed = updated.filter { it.status in setOf(LibraryUnitStatus.SUCCESS, LibraryUnitStatus.SKIPPED) }
            current.copy(
                libraryUpdate = context.copy(units = updated),
                completedUnitIds = completed.map { it.mangaId }.toSet(),
                failedUnits = updated.filter { it.status == LibraryUnitStatus.FAILED }.map { "manga:${it.mangaId}" },
                task = current.task.copy(
                    checkpoint = TaskCheckpoint(
                        unit.mangaId.toString(),
                        completed.size,
                        if (updated.isEmpty()) 1f else completed.size.toFloat() / updated.size,
                    ),
                ),
            )
        }
    }

    fun checkpoint(id: String, checkpoint: TaskCheckpoint): Boolean = lifecycleTransition(
        id,
        TaskLifecycleEvent.Checkpoint(checkpoint),
    ) { current, occurrence ->
        current.copy(task = occurrence.task, status = occurrence.status)
    }

    fun replaceCheckpoint(id: String, checkpoint: TaskCheckpoint): Boolean = transition(id) { current ->
        current.copy(task = current.task.copy(checkpoint = checkpoint))
    }

    fun reopen(id: String): Boolean = transition(id) { current ->
        current.copy(status = TaskStatus.Pending, failure = null, failedUnits = emptyList())
    }

    fun setWorkset(id: String, workset: List<Long>): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Running) || current.worksetInitialized) {
            current
        } else {
            current.copy(workset = workset, worksetInitialized = true)
        }
    }

    fun completeUnit(id: String, unitId: Long, checkpoint: TaskCheckpoint): Boolean = lifecycleTransition(
        id,
        TaskLifecycleEvent.Checkpoint(checkpoint),
    ) { current, occurrence ->
        current.copy(
            task = occurrence.task,
            status = occurrence.status,
            completedUnitIds = current.completedUnitIds + unitId,
        )
    }

    fun cancel(id: String): Boolean = transition(id) { current ->
        if (current.status in setOf(TaskStatus.Pending, TaskStatus.Failed)) {
            current.copy(status = TaskStatus.Cancelled)
        } else {
            applyLifecycle(current, TaskLifecycleEvent.Cancel) { stored, occurrence ->
                stored.copy(status = occurrence.status)
            }
        }
    }

    fun cancelRunning(id: String): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Cancel) { current, occurrence ->
        current.copy(status = occurrence.status)
    }

    fun pause(id: String): Boolean = transition(id) { current ->
        if (current.status != TaskStatus.Running) current else current.copy(status = TaskStatus.Pending)
    }

    fun start(id: String): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Start) { current, occurrence ->
        current.copy(
            status = occurrence.status,
            failure = null,
            failedUnits = emptyList(),
        )
    }

    fun complete(id: String): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Complete) { current, occurrence ->
        current.copy(status = occurrence.status)
    }

    fun fail(id: String, error: AppError): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Fail) {
            current,
            occurrence,
        ->
        current.copy(
            status = occurrence.status,
            failure = error.toStoredAppError(),
            failedUnits = current.libraryUpdate?.units?.filter { it.status == LibraryUnitStatus.FAILED }
                ?.map { "manga:${it.mangaId}" }
                ?: (error as? AppError.PartialFailure)?.failedUnits?.map { it.unitId }.orEmpty(),
        )
    }

    fun pendingTasks(): List<BackgroundTask> = store.load().filter {
        it.status in
            setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed)
    }.map { it.task }
    fun allTasks(): List<StoredTask> = store.load()
    fun snapshot(id: String): StoredTask? = store.load().firstOrNull { it.task.id == id }
    fun isCancelled(id: String): Boolean = snapshot(id)?.status == TaskStatus.Cancelled

    private fun transition(id: String, change: (StoredTask) -> StoredTask): Boolean {
        var changed = false
        transaction { tasks ->
            val index = tasks.indexOfFirst { it.task.id == id }
            if (index >= 0) {
                val next = change(tasks[index])
                changed = next != tasks[index]
                tasks[index] = next
            }
        }
        return changed
    }

    private fun lifecycleTransition(
        id: String,
        event: TaskLifecycleEvent,
        change: (StoredTask, TaskOccurrence) -> StoredTask,
    ): Boolean = transition(id) { current -> applyLifecycle(current, event, change) }

    private fun applyLifecycle(
        current: StoredTask,
        event: TaskLifecycleEvent,
        change: (StoredTask, TaskOccurrence) -> StoredTask,
    ): StoredTask {
        val decision = BackgroundTaskLifecycle.reduce(current.toOccurrence(), event)
        return if (decision.outcome != TaskLifecycleOutcome.Applied) {
            current
        } else {
            change(current, checkNotNull(decision.occurrence))
        }
    }

    private fun StoredTask.toOccurrence() = TaskOccurrence(task = task, status = status)

    private companion object {
        val terminalStatuses = setOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)
    }
}
