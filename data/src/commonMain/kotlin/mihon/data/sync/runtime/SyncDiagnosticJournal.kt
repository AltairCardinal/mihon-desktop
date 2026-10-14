package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.sync.runtime.SyncNetworkFailurePhase
import mihon.domain.sync.runtime.SyncTrigger
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer
import java.util.UUID

@Serializable
enum class SyncJournalEventKind {
    RUN_START,
    PHASE,
    EXCEPTION,
    HTTP_REQUEST,
    HTTP_RESPONSE,
    HTTP_FAILURE,
    RETRY_SCHEDULED,
    TERMINAL,
    AUTO_SKIP,
    AUTHORIZATION,
    SPACE_CHECK,
    RECOVERY,
    UNVERIFIED_SCOPE,
}

@Serializable
enum class SyncJournalStage {
    RUNTIME,
    AUTHORIZATION,
    SPACE_CHECK,
    EXCHANGE,
    CHECKING,
    IMPORTING,
    DOWNLOADING,
    MERGING,
    UPLOADING,
    CONFIRMING,
    COMPLETE,
    RECOVERY,
    RECOVERY_CONDITIONS,
    RECOVERY_EXTERNAL,
    RECOVERY_VERIFY,
    RECOVERY_REPAIR,
}

@Serializable
enum class SyncJournalCode {
    BEGIN,
    SUCCESS,
    FAILED,
    CANCELLED,
    RESUMED,
    NO_CONNECTION,
    RECOVERY_BLOCKED,
    STARTUP_ACTIVE,
    RETRY_EXHAUSTED,
    NOT_RESUMABLE,
    PAUSED_OR_BLOCKED,
    RETRY_NOT_DUE,
    CLAIM_REJECTED,
    ACCOUNT_THROTTLED,
    BINDING_UNAVAILABLE,
    UNVERIFIED_OLD_SCOPE,
    CONDITIONS_UNVERIFIED,
    UNKNOWN,
    NETWORK,
    STORAGE,
    AUTHORIZATION,
    SPACE_UNAVAILABLE,
    INVALID_DATA,
    PARTIAL,
    ORIGINAL_VERIFIED,
    NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
    REMAINING,
    WAITING_EXTERNAL,
    QUEUED,
    RUNNING,
    WAITING_NETWORK,
    WAITING_RETRY,
    WAITING_SYSTEM,
    PAUSED_USER,
    BLOCKED,
    SUCCEEDED,
    NOT_ACTIVE,
    REPOSITORY_BINDING_CHANGED,
    PLAN_OWNER_CHANGED,
    RECOVERY_CONTEXT_CHANGED,
}

@Serializable
enum class SyncJournalOperation {
    ACCOUNT,
    AUTHORIZATION,
    REPOSITORY,
    SPACE_DESCRIPTOR,
    GIT_REF,
    GIT_TREE,
    GIT_BLOB,
    GIT_COMMIT,
    PUBLISH,
    OTHER,
}

@Serializable
enum class SyncJournalIoState { OK, UNAVAILABLE, CORRUPT, FAILED }

@Serializable
data class SyncJournalThrowable(
    val type: String,
    val relation: String = "root",
    val frames: List<String> = emptyList(),
)

@Serializable
data class SyncJournalEvent(
    val timeMillis: Long,
    val kind: SyncJournalEventKind,
    val stage: SyncJournalStage,
    val runAlias: String? = null,
    val code: SyncJournalCode? = null,
    val requestAlias: String? = null,
    val networkPhase: SyncNetworkFailurePhase? = null,
    val httpStatus: Int? = null,
    val nextRetryAt: Long? = null,
    val exceptions: List<SyncJournalThrowable> = emptyList(),
    val trigger: SyncTrigger? = null,
    val attemptId: Long? = null,
    val operation: SyncJournalOperation? = null,
    val environment: SyncDiagnosticEnvironment? = null,
)

@Serializable
data class SyncDiagnosticJournalSnapshot(
    val events: List<SyncJournalEvent> = emptyList(),
    val truncated: Boolean = false,
    val readState: SyncJournalIoState = SyncJournalIoState.UNAVAILABLE,
    val writeState: SyncJournalIoState = SyncJournalIoState.UNAVAILABLE,
    val environment: SyncDiagnosticEnvironment = SyncDiagnosticEnvironment(),
    val droppedEvents: Long = 0,
)

@Serializable
private data class JournalFile(val salt: String, val events: List<SyncJournalEvent>, val droppedEvents: Long = 0)

/** Local observations only; never an input to synchronization decisions. */
class SyncDiagnosticJournal(
    private val directory: Path,
    environment: SyncDiagnosticEnvironment = SyncDiagnosticEnvironment(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val environment = environment.safeJournalEnvironment()
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val file = directory.resolve("journal.json")
    private var loaded = false
    private var salt = UUID.randomUUID().toString()
    private val events = mutableListOf<SyncJournalEvent>()
    private var dropped = 0L
    private var readState = SyncJournalIoState.UNAVAILABLE
    private var writeState = SyncJournalIoState.UNAVAILABLE

    fun runAlias(runId: String): String = synchronized(lock) {
        load()
        (salt + ":" + runId).encodeUtf8().sha256().hex().take(20)
    }

    fun record(
        kind: SyncJournalEventKind,
        stage: SyncJournalStage,
        runId: String? = null,
        code: SyncJournalCode? = null,
        failure: Throwable? = null,
        networkPhase: SyncNetworkFailurePhase? = null,
        httpStatus: Int? = null,
        nextRetryAt: Long? = null,
        requestId: String? = null,
        trigger: SyncTrigger? = null,
        attemptId: Long? = null,
        operation: SyncJournalOperation? = null,
    ) {
        synchronized(lock) {
            try {
                load()
                var event = SyncJournalEvent(
                    clock(), kind, stage, runId?.let(::runAlias), code ?: failure?.safeCode(),
                    requestId?.let(::runAlias), networkPhase, httpStatus?.takeIf { it in 100..599 },
                    nextRetryAt, failure?.safeThrowableGraph().orEmpty(), trigger, attemptId, operation, environment,
                )
                while (json.encodeToString(event).encodeToByteArray().size > MAX_EVENT_BYTES) {
                    val longest = event.exceptions.indices.maxByOrNull { event.exceptions[it].frames.size } ?: break
                    val reduced = event.exceptions.toMutableList()
                    if (reduced[longest].frames.isEmpty()) break
                    reduced[longest] = reduced[longest].copy(frames = reduced[longest].frames.dropLast(1))
                    event = event.copy(exceptions = reduced)
                }
                events += event
                trim()
                persist()
            } catch (_: Exception) {
                writeState = SyncJournalIoState.FAILED
            }
        }
    }

    fun snapshot(): SyncDiagnosticJournalSnapshot = synchronized(lock) {
        load()
        SyncDiagnosticJournalSnapshot(
            events.toList(),
            dropped > 0,
            readState,
            writeState,
            environment,
            dropped,
        )
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!FileSystem.SYSTEM.exists(file)) return
            val content = FileSystem.SYSTEM.source(file).buffer().use { source ->
                val buffer = okio.Buffer()
                while (buffer.size <= MAX_FILE_BYTES &&
                    source.read(buffer, minOf(8192L, MAX_FILE_BYTES + 1L - buffer.size)) != -1L
                ) {
                    // Bounded even when another process changes the file during the read.
                }
                buffer.readByteString()
            }
            require(content.size <= MAX_FILE_BYTES)
            val saved = json.decodeFromString<JournalFile>(content.utf8())
            require(saved.salt.matches(Regex("[a-f0-9-]{36}")))
            require(saved.events.size <= MAX_EVENTS)
            // Corrupt files are never allowed to smuggle arbitrary text into an exported snapshot.
            require(saved.events.all { it.isSafePersistedEvent(json) })
            salt = saved.salt
            events += saved.events
            dropped = saved.droppedEvents.coerceAtLeast(0)
            readState = SyncJournalIoState.OK
        } catch (_: Exception) {
            dropped++
            readState = if (runCatching { FileSystem.SYSTEM.metadata(file).isRegularFile }.getOrDefault(false)) {
                SyncJournalIoState.CORRUPT
            } else {
                SyncJournalIoState.FAILED
            }
        }
    }

    private fun trim() {
        while (events.size > MAX_EVENTS || json.encodeToString(JournalFile(salt, events, dropped))
                .encodeToByteArray().size > MAX_FILE_BYTES - 2048
        ) {
            val disposable = events.indexOfFirst { !it.isFailureEvidence() }
            events.removeAt(if (disposable >= 0) disposable else 0)
            dropped++
        }
    }

    private fun persist() {
        val temporary = directory.resolve("journal-${UUID.randomUUID()}.tmp")
        try {
            FileSystem.SYSTEM.createDirectories(directory)
            FileSystem.SYSTEM.sink(temporary).buffer().use {
                it.writeUtf8(json.encodeToString(JournalFile(salt, events, dropped)))
            }
            FileSystem.SYSTEM.atomicMove(temporary, file)
            writeState = SyncJournalIoState.OK
        } finally {
            runCatching { FileSystem.SYSTEM.delete(temporary, mustExist = false) }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 128 * 1024
        private const val MAX_EVENT_BYTES = 16 * 1024
        private const val MAX_EVENTS = 256
    }
}

private fun SyncJournalEvent.isFailureEvidence(): Boolean =
    kind in setOf(SyncJournalEventKind.EXCEPTION, SyncJournalEventKind.HTTP_FAILURE) ||
        code in setOf(SyncJournalCode.FAILED, SyncJournalCode.BLOCKED, SyncJournalCode.UNVERIFIED_OLD_SCOPE)

private fun SyncJournalEvent.isSafePersistedEvent(json: Json): Boolean =
    json.encodeToString(this).encodeToByteArray().size <= 16 * 1024 &&
        (runAlias == null || runAlias.matches(Regex("[a-f0-9]{20}"))) &&
        (requestAlias == null || requestAlias.matches(Regex("[a-f0-9]{20}"))) &&
        (environment == null || environment == environment.safeJournalEnvironment()) &&
        exceptions.size <= 8 && exceptions.all { error ->
            error.type == safeIdentifier(error.type) && error.relation in setOf("root", "cause", "suppressed") &&
                error.frames.size <= 24 && error.frames.all {
                    it.length <= 700 && it.matches(Regex("[A-Za-z0-9_.$():? -]+"))
                }
        }

private fun safeIdentifier(value: String): String =
    value.take(200).takeIf { it.matches(Regex("[A-Za-z0-9_.$]+")) } ?: "unknown"

private fun Throwable.safeThrowableGraph(): List<SyncJournalThrowable> {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val queue = java.util.ArrayDeque<Pair<Throwable, String>>()
    val result = mutableListOf<SyncJournalThrowable>()
    queue.add(this to "root")
    while (queue.isNotEmpty() && result.size < 8) {
        val (error, relation) = queue.removeFirst()
        if (!seen.add(error)) continue
        result += SyncJournalThrowable(
            safeIdentifier(error.javaClass.name),
            relation,
            error.stackTrace.take(24).map { frame ->
                val source = safeIdentifier(
                    frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: "unknown",
                )
                "${safeIdentifier(frame.className)}.${safeIdentifier(frame.methodName)}" +
                    "($source:${frame.lineNumber})"
            },
        )
        error.cause?.takeUnless { seen.contains(it) }?.let { queue.add(it to "cause") }
        error.suppressed.take(3).forEach { queue.add(it to "suppressed") }
    }
    return result
}

private fun Throwable.safeCode(): SyncJournalCode = when (message) {
    "sync repository binding changed" -> SyncJournalCode.REPOSITORY_BINDING_CHANGED
    "sync plan owner changed" -> SyncJournalCode.PLAN_OWNER_CHANGED
    "sync recovery context changed" -> SyncJournalCode.RECOVERY_CONTEXT_CHANGED
    else -> SyncJournalCode.UNKNOWN
}

/** An immutable scope is passed through each application's real HTTP graph. */
data class SyncJournalContext(
    val journal: SyncDiagnosticJournal,
    val stage: SyncJournalStage,
    val runId: String? = null,
    val attemptId: Long? = null,
    val trigger: SyncTrigger? = null,
) {
    fun record(
        kind: SyncJournalEventKind,
        code: SyncJournalCode? = null,
        failure: Throwable? = null,
        networkPhase: SyncNetworkFailurePhase? = null,
        httpStatus: Int? = null,
        requestId: String? = null,
        operation: SyncJournalOperation? = null,
    ) = journal.record(
        kind, stage, runId, code, failure, networkPhase, httpStatus,
        requestId = requestId, attemptId = attemptId, trigger = trigger, operation = operation,
    )
}

private fun SyncDiagnosticEnvironment.safeJournalEnvironment(): SyncDiagnosticEnvironment {
    fun safe(value: String) = value.takeIf { it.length <= 100 && it.matches(Regex("[A-Za-z0-9_.+-]+")) } ?: "UNKNOWN"
    return copy(
        platform = safe(platform),
        appVersion = safe(appVersion),
        sourceRevision = safe(sourceRevision),
        releaseIdentity = safe(releaseIdentity),
        build = safe(build),
    )
}
