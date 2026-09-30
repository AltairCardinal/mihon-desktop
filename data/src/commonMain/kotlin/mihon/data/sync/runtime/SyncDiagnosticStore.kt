package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path
import okio.buffer
import java.util.UUID

internal data class SyncDiagnosticSession(val salt: ByteString, val expiresAt: Long, val previousAlias: String? = null)

/** Export files and the private opt-in association cache have deliberately different directories. */
internal class SyncDiagnosticStore(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val privateDirectory = directory.resolve("private")
    private val sessionPath = privateDirectory.resolve("session.json")

    @Serializable
    private data class Cache(val salt: String, val expiresAt: Long, val previousAlias: String?)

    fun loadSession(): SyncDiagnosticSession? = try {
        if (!fileSystem.exists(sessionPath)) {
            null
        } else {
            require(fileSystem.metadata(sessionPath).size?.let { it <= 1024 } == true)
            val cached = fileSystem.source(sessionPath).buffer().use { Json.decodeFromString<Cache>(it.readUtf8()) }
            val remaining = cached.expiresAt - clock()
            val salt = cached.salt.decodeBase64()
            if (remaining !in 1..86_400_000 || salt?.size != 32 ||
                cached.previousAlias?.matches(Regex("snapshot-[0-9a-f]{24}")) == false
            ) {
                endSession()
                null
            } else {
                SyncDiagnosticSession(salt, cached.expiresAt, cached.previousAlias)
            }
        }
    } catch (_: Exception) {
        runCatching { endSession() }
        null
    }

    fun beginSession(): SyncDiagnosticSession = SyncDiagnosticSession(
        SyncDiagnostics.randomSalt(),
        clock() + 86_400_000,
    ).also(::saveSession)

    fun saveSession(session: SyncDiagnosticSession) {
        atomicWrite(
            sessionPath,
            Json.encodeToString(Cache(session.salt.base64(), session.expiresAt, session.previousAlias)),
        )
    }

    fun endSession() {
        fileSystem.delete(sessionPath, mustExist = false)
    }

    fun export(snapshot: SyncDiagnosticSnapshot): String {
        var bounded = snapshot.copy(
            events = snapshot.events.takeLast(128),
            truncated = snapshot.truncated || snapshot.events.size > 128,
        )
        var json = bounded.json()
        while (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES && bounded.events.isNotEmpty()) {
            bounded = bounded.copy(events = bounded.events.drop(1), truncated = true)
            json = bounded.json()
        }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val destination = directory.resolve("sync-diagnostic-${UUID.randomUUID()}.json")
        atomicWrite(destination, json)
        return destination.toString()
    }

    private fun atomicWrite(destination: Path, json: String) {
        val temporary = destination.parent!!.resolve("${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            fileSystem.createDirectories(destination.parent!!)
            fileSystem.sink(temporary, mustCreate = true).buffer().use { it.writeUtf8(json) }
            fileSystem.atomicMove(temporary, destination)
        } finally {
            runCatching { fileSystem.delete(temporary, mustExist = false) }
        }
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024
    }
}
