package tachiyomi.core.common.feedback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable
enum class DiagnosticCategory(val id: String) {
    STARTUP_CRASH("startup_crash"),
    PERFORMANCE("performance"),
    SOURCE_NETWORK("source_network"),
    BROWSE_DETAILS("browse_details"),
    READER("reader"),
    LIBRARY_PROGRESS("library_progress"),
    DOWNLOAD_LOCAL("download_local"),
    BACKUP_SYNC("backup_sync"),
    UPDATE_INSTALL("update_install"),
    INTERFACE("interface"),
    FEATURE_REQUEST("feature_request"),
    OTHER("other"),
}

enum class DiagnosticOperation {
    APPLICATION,
    NETWORK,
    BROWSE,
    DETAILS,
    READER_IMAGE,
    READER_PAGE,
    READER_MODE,
    LIBRARY,
    PROGRESS,
    DOWNLOAD,
    LOCAL_FILE,
    BACKUP,
    RESTORE,
    SYNC,
    UPDATE,
    INSTALL,
    INTERFACE,
    FEEDBACK,
}

enum class DiagnosticStage { START, SUCCESS, FAILURE, CANCELLED, HANDSHAKE, TLS, HTTP, DECODE, PAUSE, RESUME }

data class DiagnosticEvent(
    val category: DiagnosticCategory,
    val operation: DiagnosticOperation,
    val stage: DiagnosticStage,
    val statusCode: Int? = null,
    val durationMs: Long? = null,
    val count: Int? = null,
)

@Serializable
data class DiagnosticSession(
    val id: String,
    val category: DiagnosticCategory,
    val startedAtMillis: Long,
    val paused: Boolean = false,
)

enum class DiagnosticError { STORAGE, CAPACITY, QUEUE_FULL }
data class DiagnosticState(val session: DiagnosticSession? = null, val error: DiagnosticError? = null)
data class DiagnosticLog(
    val content: String,
    val date: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val offsetSeconds: Set<Int>,
)

interface DiagnosticPersistence {
    fun load(): DiagnosticSession?
    fun save(session: DiagnosticSession?)
}

data class DiagnosticLimits(
    val retentionDays: Long = 7,
    val totalBytes: Long = 8L * 1024 * 1024,
    val sessionBytes: Long = 2L * 1024 * 1024,
    val previewBytes: Long = 4L * 1024 * 1024,
)

class DiagnosticStore(
    private val directory: File,
    private val persistence: DiagnosticPersistence,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val limits: DiagnosticLimits = DiagnosticLimits(),
) {
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<DiagnosticState> = mutableState
    private val mutex = Mutex()
    private val commands = Channel<Command>(256)
    private val root = directory.toPath().toAbsolutePath().normalize()
    private var lastCleanup: Long? = null

    init {
        require(
            limits.retentionDays in 1..7 && limits.totalBytes > 0 && limits.sessionBytes > 0 && limits.previewBytes > 0,
        )
        scope.launch(Dispatchers.IO) {
            try {
                for (command in commands) {
                    when (command) {
                        is Command.Barrier -> command.done.complete(Unit)
                        is Command.Event -> mutex.withLock {
                            try {
                                if (state.value.error == DiagnosticError.QUEUE_FULL &&
                                    state.value.session != persistence.load()
                                ) {
                                    pause(DiagnosticError.QUEUE_FULL)
                                }
                                append(command)
                            } catch (_: Exception) {
                                pause(DiagnosticError.STORAGE)
                            }
                        }
                    }
                }
            } finally {
                commands.close()
                while (true) {
                    val pending = commands.tryReceive().getOrNull() ?: break
                    if (pending is Command.Barrier) {
                        pending.done.completeExceptionally(
                            IOException("Diagnostic worker stopped"),
                        )
                    }
                }
            }
        }
    }

    private sealed interface Command {
        data class Event(
            val session: String,
            val value: DiagnosticEvent,
            val time: Long,
            val offset: Int,
            val date: String,
        ) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }

    private fun loadState(): DiagnosticState = try {
        val loaded = persistence.load()
        if (loaded != null && !ID.matches(loaded.id)) throw IOException("Invalid diagnostic session")
        DiagnosticState(loaded, if (loaded?.paused == true) DiagnosticError.CAPACITY else null)
    } catch (_: Exception) {
        DiagnosticState(error = DiagnosticError.STORAGE)
    }

    suspend fun start(category: DiagnosticCategory): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = state.value.session
            if (previous?.category == category && !previous.paused) return@withLock true
            try {
                prepareRoot()
                expire(force = true)
                val session = DiagnosticSession(UUID.randomUUID().toString(), category, nowMillis())
                persistence.save(session)
                mutableState.value = DiagnosticState(session)
                true
            } catch (_: Exception) {
                mutableState.update { it.copy(error = DiagnosticError.STORAGE) }
                false
            }
        }
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = state.value.session
            try {
                persistence.save(null)
            } catch (_: Exception) {
                mutableState.update { it.copy(error = DiagnosticError.STORAGE) }
                return@withLock false
            }
            mutableState.value = DiagnosticState()
            // Disable durably before deleting. Queued old-session events cannot recreate files.
            try {
                if (active != null) {
                    files().filter { it.fileName.toString().endsWith("_${active.id}.jsonl") }
                        .forEach { Files.delete(it) }
                }
            } catch (_: Exception) {
                mutableState.value = DiagnosticState(error = DiagnosticError.STORAGE)
            }
            true
        }
    }

    fun record(event: DiagnosticEvent) {
        val active = state.value.session ?: return
        if (active.paused || active.category != event.category || !validMetrics(event)) return
        try {
            val time = nowMillis()
            val instant = Instant.ofEpochMilli(time)
            val local = instant.atZone(zone())
            val command = Command.Event(
                active.id,
                event,
                time,
                local.offset.totalSeconds,
                local.toLocalDate().toString(),
            )
            if (!DATE.matches(command.date)) return
            if (commands.trySend(command).isFailure) {
                mutableState.update { current ->
                    if (current.session?.id == active.id) {
                        current.copy(session = active.copy(paused = true), error = DiagnosticError.QUEUE_FULL)
                    } else {
                        current
                    }
                }
            }
        } catch (_: Exception) {
            mutableState.update { it.copy(error = DiagnosticError.STORAGE) }
        }
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(done))
        done.await()
        mutex.withLock {
            if (state.value.error == DiagnosticError.QUEUE_FULL) {
                state.value.session?.let {
                    try {
                        persistence.save(it)
                    } catch (_: Exception) {
                        mutableState.update { value -> value.copy(error = DiagnosticError.STORAGE) }
                    }
                }
            }
        }
    }

    suspend fun dates(category: DiagnosticCategory): List<String> = withContext(Dispatchers.IO) {
        flush()
        mutex.withLock {
            expire(force = true)
            files().filter { it.parent.fileName.toString() == category.id && Files.size(it) > 0 }
                .map { it.fileName.toString().substringBefore('_') }.distinct().sortedDescending()
        }
    }

    suspend fun read(category: DiagnosticCategory, date: String): DiagnosticLog? = withContext(Dispatchers.IO) {
        require(DATE.matches(date))
        flush()
        mutex.withLock {
            expire(force = true)
            val matching = files().filter {
                it.parent.fileName.toString() == category.id &&
                    it.fileName.toString().startsWith("${date}_")
            }
            if (matching.isEmpty()) return@withLock null
            if (matching.sumOf { Files.size(it) } >
                limits.previewBytes
            ) {
                throw IOException("Diagnostic preview too large")
            }
            val events = matching.flatMap { readEvents(it) }.sortedBy { it["time"]!!.jsonPrimitive.long }
            if (events.isEmpty()) return@withLock null
            DiagnosticLog(
                events.joinToString("\n", postfix = "\n"),
                date,
                events.first()["time"]!!.jsonPrimitive.long,
                events.last()["time"]!!.jsonPrimitive.long,
                events.map { it["offset"]!!.jsonPrimitive.int }.toSet(),
            )
        }
    }

    private fun append(command: Command.Event) {
        val active = state.value.session ?: return
        if (active.id != command.session || active.paused) return
        prepareRoot()
        expire()
        val entry = buildJsonObject {
            put("time", command.time)
            put("offset", command.offset)
            put("session", command.session)
            put("category", command.value.category.id)
            put("operation", command.value.operation.name)
            put("stage", command.value.stage.name)
            command.value.statusCode?.let { put("statusCode", it) }
            command.value.durationMs?.let { put("durationMs", it) }
            command.value.count?.let { put("count", it) }
        }
        val bytes = "$entry\n".toByteArray(Charsets.UTF_8)
        val existing = files()
        val total = existing.sumOf { Files.size(it) }
        val sessionTotal = existing.filter {
            it.fileName.toString().endsWith("_${active.id}.jsonl")
        }.sumOf { Files.size(it) }
        if (total + bytes.size > limits.totalBytes || sessionTotal + bytes.size > limits.sessionBytes) {
            pause(DiagnosticError.CAPACITY)
            return
        }
        val parent = root.resolve(active.category.id)
        Files.createDirectories(parent)
        checkPath(parent)
        val posix = Files.getFileStore(parent).supportsFileAttributeView("posix")
        if (posix) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        val file = parent.resolve("${command.date}_${active.id}.jsonl")
        checkPath(file)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Files.createFile(
                    file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } else {
                Files.createFile(file)
            }
        }
        Files.newOutputStream(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS,
        ).use {
            it.write(bytes)
        }
        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }

    private fun pause(error: DiagnosticError) {
        val active = state.value.session ?: return
        val paused = active.copy(paused = true)
        mutableState.value = DiagnosticState(paused, error)
        try {
            persistence.save(paused)
        } catch (_: Exception) {
            mutableState.value = DiagnosticState(paused, DiagnosticError.STORAGE)
        }
    }

    private fun prepareRoot() {
        checkPath(root)
        Files.createDirectories(root)
        checkPath(root)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Invalid diagnostic directory")
        recoverCleanupFiles()
    }

    private fun checkPath(path: Path) {
        if (!path.startsWith(root)) throw IOException("Invalid diagnostic path")
        var cursor: Path? = path
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) throw IOException("Linked diagnostic path")
            // Windows directory junctions are not symbolic links. Resolve every
            // existing ancestor, including the private root, before using it.
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) &&
                cursor.toRealPath() != cursor.toAbsolutePath().normalize()
            ) {
                throw IOException("Redirected diagnostic path")
            }
            cursor = cursor.parent
        }
    }

    private fun recoverCleanupFiles() {
        checkPath(root)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(root, "diagnostic-cleanup-*.tmp").use { entries ->
            var count = 0
            for (file in entries) {
                checkPath(file)
                if (!CLEANUP_FILE.matches(file.fileName.toString()) ||
                    !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw IOException("Invalid interrupted diagnostic cleanup")
                }
                Files.delete(file)
                if (++count > 4096) throw IOException("Too many interrupted diagnostic cleanups")
            }
        }
    }

    private fun files(): List<Path> {
        checkPath(root)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Invalid diagnostic directory")
        recoverCleanupFiles()
        val found = mutableListOf<Path>()
        for (category in DiagnosticCategory.entries) {
            val folder = root.resolve(category.id)
            checkPath(folder)
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) continue
            if (!Files.isDirectory(
                    folder,
                    LinkOption.NOFOLLOW_LINKS,
                )
            ) {
                throw IOException("Invalid diagnostic category directory")
            }
            Files.newDirectoryStream(folder).use { entries ->
                for (file in entries) {
                    checkPath(file)
                    if (!FILE.matches(file.fileName.toString()) ||
                        !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw IOException("Unexpected diagnostic file")
                    }
                    found.add(file)
                    if (found.size > 4096) throw IOException("Diagnostic file index too large")
                }
            }
        }
        return found
    }

    private fun readEvents(file: Path): List<JsonObject> {
        checkPath(file)
        if (Files.size(file) > limits.previewBytes) throw IOException("Diagnostic preview too large")
        try {
            val raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count >
                        limits.previewBytes
                    ) {
                        throw IOException("Diagnostic preview too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            return text.lineSequence().filter { it.isNotBlank() }.map { line ->
                val entry =
                    Json.parseToJsonElement(line) as? JsonObject ?: throw IOException("Invalid diagnostic event")
                val allowed = setOf(
                    "time",
                    "offset",
                    "session",
                    "category",
                    "operation",
                    "stage",
                    "statusCode",
                    "durationMs",
                    "count",
                )
                require(entry.keys.all { it in allowed })
                val category = DiagnosticCategory.entries.single { it.id == entry["category"]!!.jsonPrimitive.content }
                val event = DiagnosticEvent(
                    category,
                    DiagnosticOperation.valueOf(entry["operation"]!!.jsonPrimitive.content),
                    DiagnosticStage.valueOf(entry["stage"]!!.jsonPrimitive.content),
                    entry["statusCode"]?.jsonPrimitive?.int,
                    entry["durationMs"]?.jsonPrimitive?.long,
                    entry["count"]?.jsonPrimitive?.int,
                )
                require(validMetrics(event) && ID.matches(entry["session"]!!.jsonPrimitive.content))
                require(entry["offset"]!!.jsonPrimitive.int in -64800..64800)
                entry["time"]!!.jsonPrimitive.long
                val expectedSuffix = "_${entry["session"]!!.jsonPrimitive.content}.jsonl"
                val matchesCategory = file.parent.fileName.toString() == category.id
                val matchesSession = file.fileName.toString().endsWith(expectedSuffix)
                require(matchesCategory && matchesSession)
                entry
            }.toList()
        } catch (_: Exception) {
            throw IOException("Invalid or unreadable diagnostic log")
        }
    }

    private fun expire(force: Boolean = false) {
        val now = nowMillis()
        val previous = lastCleanup
        if (!force && previous != null && now >= previous && now - previous < 60 * 60 * 1000L) return
        val cutoff = now - limits.retentionDays * 24 * 60 * 60 * 1000L
        for (file in files()) {
            val events = readEvents(file)
            val retained = events.filter { it["time"]!!.jsonPrimitive.long >= cutoff }
            if (retained.isEmpty()) {
                Files.delete(file)
            } else if (retained.size != events.size) {
                val temp = Files.createTempFile(root, "diagnostic-cleanup-", ".tmp")
                try {
                    Files.write(temp, retained.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                    checkPath(file)
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }
        lastCleanup = now
    }

    companion object {
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val FILE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9a-f-]{36}\\.jsonl")
        private val CLEANUP_FILE = Regex("diagnostic-cleanup-[0-9]+\\.tmp")
        private fun validMetrics(event: DiagnosticEvent): Boolean =
            (event.statusCode == null || event.statusCode in 100..599) &&
                (event.durationMs == null || event.durationMs in 0..86_400_000L) &&
                (event.count == null || event.count in 0..1_000_000_000)
    }
}

object DiagnosticEvents {
    @Volatile private var store: DiagnosticStore? = null

    @Synchronized
    fun install(recorder: DiagnosticStore?) {
        store = recorder
    }

    @Synchronized
    fun uninstall(recorder: DiagnosticStore) {
        if (store === recorder) store = null
    }
    fun enabled(category: DiagnosticCategory): Boolean = store?.state?.value?.session?.let {
        it.category == category && !it.paused
    } == true
    fun record(event: DiagnosticEvent) {
        try {
            store?.record(event)
        } catch (_: Exception) { /* Diagnostics must not break business operations. */ }
    }
}

internal fun safeCrashIdentifier(value: String): Boolean =
    CRASH_IDENTIFIER.matches(value) && !CRASH_CREDENTIAL.containsMatchIn(value)

private val CRASH_IDENTIFIER = Regex("[A-Za-z0-9_.$<>-]{1,240}")
private val CRASH_CREDENTIAL = Regex(
    "github_pat_|gh[pousr]_|sk[-_]|token|password|secret|authorization|cookie",
    RegexOption.IGNORE_CASE,
)

fun safeCrashReport(error: Throwable, atMillis: Long = System.currentTimeMillis()): String = buildString {
    append("Crash time (UTC millis): ").append(atMillis).append('\n')
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    var current: Throwable? = error
    repeat(8) {
        val throwable = current ?: return@buildString
        if (seen.put(throwable, true) != null) return@buildString
        if (safeCrashIdentifier(throwable.javaClass.name)) append(throwable.javaClass.name).append('\n')
        throwable.stackTrace.take(128).forEach { frame ->
            if (safeCrashIdentifier(frame.className) && safeCrashIdentifier(frame.methodName)) {
                append("  at ").append(frame.className).append('.').append(frame.methodName)
                if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                append('\n')
            }
        }
        current = throwable.cause
    }
}
