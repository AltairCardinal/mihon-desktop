@file:Suppress("ktlint:standard:filename")

package mihon.data.sync.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileMetadata
import okio.FileSystem
import okio.Path

internal enum class SyncGitObjectKind {
    BLOB,
    TREE,
}

internal data class SyncPersistentGitObjectKey(
    val apiOrigin: String,
    val repositoryId: String,
    val repository: String,
    val branch: String,
    val kind: SyncGitObjectKind,
    val objectFormat: String,
    val objectOid: String,
    val validationScope: String,
    val connectionRevision: String,
)

/**
 * A disposable cache for immutable Git object responses. Files are scoped by the
 * complete validation context and checked again whenever they are read. A cache
 * error is deliberately indistinguishable from a miss to callers.
 */
internal class SyncPersistentGitObjectCache(
    private val directory: Path?,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val objectDirectory = directory?.resolve(CACHE_DIRECTORY)

    init {
        require(maxBytes > 0)
    }

    suspend fun read(key: SyncPersistentGitObjectKey): ByteArray? {
        val directoryRoot = objectDirectory ?: return null
        return try {
            filesystemMutex.withLock {
                val root = canonicalRoot(directoryRoot)
                val state = directoryInventory(root)
                val path = root.resolve(fileName(key))
                try {
                    if (!state.trackable) {
                        val metadata = fileSystem.metadataOrNull(path) ?: return@withLock null
                        val fileSize = metadata.size ?: 0L
                        if (fileSize <= 0L || fileSize > maxBytes) {
                            removeEntryBestEffort(root, path, null)
                            return@withLock null
                        }
                        val decoded = decode(key, fileSystem.read(path) { readByteArray() })
                        if (decoded == null) removeEntryBestEffort(root, path, null)
                        return@withLock decoded
                    }
                    val metadata = refreshEntry(root, path, state) ?: return@withLock null
                    val fileSize = metadata.size ?: 0L
                    if (fileSize <= 0L || fileSize > maxBytes) {
                        removeEntryBestEffort(root, path, state)
                        return@withLock null
                    }
                    val encoded = fileSystem.read(path) { readByteArray() }
                    val decoded = decode(key, encoded)
                    if (decoded == null) {
                        removeEntryBestEffort(root, path, state)
                    } else {
                        touchAccessBestEffort(root, path, state, fileSize)
                    }
                    decoded
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    state.valid = false
                    removeEntryBestEffort(root, path, state)
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            invalidateDirectory(directoryRoot)
            null
        }
    }

    suspend fun write(key: SyncPersistentGitObjectKey, content: ByteArray) {
        val directoryRoot = objectDirectory ?: return
        if (content.size.toLong() > maxBytes) return
        val record = encode(key, content)
        if (record.size.toLong() + ACCESS_MARKER_BYTES > maxBytes) return
        try {
            filesystemMutex.withLock {
                val root = canonicalRoot(directoryRoot)
                fileSystem.createDirectories(root)
                val state = directoryInventory(root)
                if (!state.trackable) return@withLock
                val destination = root.resolve(fileName(key))
                val temporary = root.resolve("${fileName(key)}.${randomSuffix()}$TEMP_SUFFIX")
                try {
                    try {
                        refreshEntry(root, destination, state)
                        if (!state.trackable) return@withLock
                        fileSystem.write(temporary, mustCreate = true) { write(record) }
                        val temporaryContent = decode(key, fileSystem.read(temporary) { readByteArray() })
                        check(temporaryContent?.contentEquals(content) == true) {
                            "persistent sync object cache temporary file failed validation"
                        }
                        if (!evictForWrite(
                                root,
                                destination,
                                temporary,
                                record.size.toLong() + ACCESS_MARKER_BYTES,
                                state,
                            )
                        ) {
                            return@withLock
                        }
                        fileSystem.atomicMove(temporary, destination)
                        val stamp = nextAccessStamp(state)
                        val marker = accessPath(root, destination)
                        val markerTemporary = root.resolve("${destination.name}.${randomSuffix()}$TEMP_SUFFIX")
                        try {
                            fileSystem.write(markerTemporary, mustCreate = true) { writeLong(stamp) }
                            fileSystem.atomicMove(markerTemporary, marker)
                            trackEntry(state, destination, record.size.toLong(), ACCESS_MARKER_BYTES, stamp)
                        } finally {
                            if (!removeBestEffort(markerTemporary)) state.valid = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        state.valid = false
                    }
                } finally {
                    if (!removeBestEffort(temporary)) state.valid = false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            invalidateDirectory(directoryRoot)
        }
    }

    suspend fun remove(key: SyncPersistentGitObjectKey) {
        val directoryRoot = objectDirectory ?: return
        try {
            filesystemMutex.withLock {
                val root = canonicalRoot(directoryRoot)
                val state = directoryInventory(root)
                val objectPath = root.resolve(fileName(key))
                try {
                    fileSystem.delete(objectPath)
                    fileSystem.delete(accessPath(root, objectPath))
                    state.remove(objectPath)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    state.valid = false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            invalidateDirectory(directoryRoot)
        }
    }

    private fun encode(key: SyncPersistentGitObjectKey, content: ByteArray): ByteArray {
        val contextDigest = key.contextDigest()
        val checksum = content.toByteString().sha256().hex()
        return Buffer().apply {
            write(MAGIC)
            writeString(contextDigest)
            writeString(key.repositoryId)
            writeString(key.kind.name)
            writeString(key.objectFormat)
            writeString(key.objectOid)
            writeInt(content.size)
            writeString(checksum)
            write(content)
        }.readByteArray()
    }

    private fun decode(key: SyncPersistentGitObjectKey, encoded: ByteArray): ByteArray? = runCatching {
        val buffer = Buffer().write(encoded)
        require(buffer.readByteArray(MAGIC.size.toLong()).contentEquals(MAGIC)) { "cache record version mismatch" }
        require(readString(buffer) == key.contextDigest()) { "cache record context mismatch" }
        require(readString(buffer) == key.repositoryId) { "cache record repository id mismatch" }
        require(readString(buffer) == key.kind.name) { "cache record object kind mismatch" }
        require(readString(buffer) == key.objectFormat) { "cache record object format mismatch" }
        require(readString(buffer) == key.objectOid) { "cache record object id mismatch" }
        val contentSize = buffer.readInt()
        require(contentSize >= 0 && contentSize.toLong() <= maxBytes && contentSize.toLong() <= buffer.size) {
            "cache record length is invalid"
        }
        val checksum = readString(buffer)
        require(checksum.length == SHA256_HEX_LENGTH) { "cache record checksum is invalid" }
        val content = buffer.readByteArray(contentSize.toLong())
        require(buffer.exhausted()) { "cache record has trailing bytes" }
        require(content.toByteString().sha256().hex() == checksum) { "cache record checksum mismatch" }
        require(contentMatchesKey(key, content)) { "cache record object id mismatch" }
        content
    }.getOrNull()

    private fun contentMatchesKey(key: SyncPersistentGitObjectKey, content: ByteArray): Boolean {
        if (key.objectFormat != "git-sha-${key.objectOid.length}") return false
        return when (key.kind) {
            SyncGitObjectKind.BLOB -> gitBlobOid(content, key.objectOid.length) == key.objectOid
            SyncGitObjectKind.TREE -> runCatching {
                val json = Json.parseToJsonElement(content.decodeToString()).jsonObject
                json["sha"]?.jsonPrimitive?.content == key.objectOid
            }.getOrDefault(false)
        }
    }

    private fun readString(buffer: Buffer): String {
        val length = buffer.readInt()
        require(length in 0..MAX_METADATA_FIELD_BYTES && length.toLong() <= buffer.size) {
            "cache record metadata length is invalid"
        }
        return buffer.readByteArray(length.toLong()).decodeToString()
    }

    private fun evictForWrite(
        root: Path,
        destination: Path,
        temporary: Path,
        incomingBytes: Long,
        state: DirectoryInventory,
    ): Boolean {
        var projectedBytes = state.totalBytes - (state.entries[destination]?.totalBytes ?: 0L) + incomingBytes
        if (projectedBytes <= maxBytes) return true

        // Reconcile external changes once on the capacity path; the normal read/append path stays incremental.
        rebuildDirectoryInventory(root, state, temporary)
        if (!state.trackable) return false
        projectedBytes = state.totalBytes - (state.entries[destination]?.totalBytes ?: 0L) + incomingBytes
        if (projectedBytes <= maxBytes) return true

        val candidates = state.entries.entries
            .filter { it.key != destination }
            .sortedWith(compareBy({ it.value.accessStamp }, { it.key.name }))
        for ((path, _) in candidates) {
            val marker = accessPath(root, path)
            try {
                fileSystem.delete(path)
                fileSystem.delete(marker)
            } catch (error: Throwable) {
                state.valid = false
                throw error
            }
            state.remove(path)
            projectedBytes = state.totalBytes - (state.entries[destination]?.totalBytes ?: 0L) + incomingBytes
            if (projectedBytes <= maxBytes) return true
        }
        return false
    }

    private fun accessPath(root: Path, objectPath: Path): Path = root.resolve("${objectPath.name}$ACCESS_SUFFIX")

    private fun readAccessStamp(path: Path): Long? = runCatching {
        val bytes = fileSystem.read(path) { readByteArray() }
        if (bytes.size != ACCESS_MARKER_BYTES.toInt()) return@runCatching null
        Buffer().write(bytes).readLong().takeIf { it >= 0L }
    }.getOrNull()

    private fun nextAccessStamp(state: DirectoryInventory): Long {
        val previous = state.latestAccessStamp
        val now = System.currentTimeMillis()
        val next = if (previous >= now) {
            if (previous == Long.MAX_VALUE) previous else previous + 1L
        } else {
            now
        }
        state.latestAccessStamp = next
        return next
    }

    private fun touchAccess(root: Path, objectPath: Path, state: DirectoryInventory, objectSize: Long) {
        val marker = accessPath(root, objectPath)
        val temporary = root.resolve("${objectPath.name}.${randomSuffix()}$TEMP_SUFFIX")
        val current = state.entries[objectPath] ?: return
        val projectedBytes = state.totalBytes - current.totalBytes + objectSize + ACCESS_MARKER_BYTES
        if (projectedBytes > maxBytes) return
        try {
            val stamp = nextAccessStamp(state)
            fileSystem.write(temporary, mustCreate = true) { writeLong(stamp) }
            fileSystem.atomicMove(temporary, marker)
            state.put(objectPath, objectSize, ACCESS_MARKER_BYTES, stamp)
        } finally {
            if (!removeBestEffort(temporary)) state.valid = false
        }
    }

    private fun touchAccessBestEffort(
        root: Path,
        objectPath: Path,
        state: DirectoryInventory,
        objectSize: Long,
    ) {
        try {
            touchAccess(root, objectPath, state, objectSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Recency metadata is only an eviction hint; keep a valid cache hit usable.
            state.valid = false
        }
    }

    private fun refreshEntry(root: Path, objectPath: Path, state: DirectoryInventory): FileMetadata? {
        val objectMetadata = fileSystem.metadataOrNull(objectPath)
        if (objectMetadata == null) {
            val marker = accessPath(root, objectPath)
            try {
                fileSystem.delete(marker)
                state.remove(objectPath)
                state.removeOrphanMarker(marker)
            } catch (_: Throwable) {
                state.valid = false
            }
            return null
        }
        val marker = accessPath(root, objectPath)
        val markerMetadata = fileSystem.metadataOrNull(marker)
        val stamp = readAccessStamp(marker)
            ?: state.entries[objectPath]?.accessStamp
            ?: objectMetadata.lastModifiedAtMillis
            ?: 0L
        trackEntry(state, objectPath, objectMetadata.size ?: 0L, markerMetadata?.size ?: 0L, stamp)
        return objectMetadata
    }

    private fun trackEntry(
        state: DirectoryInventory,
        path: Path,
        objectBytes: Long,
        markerBytes: Long,
        accessStamp: Long,
    ) {
        if (path in state.entries) {
            state.put(path, objectBytes, markerBytes, accessStamp)
            return
        }
        if (path !in state.entries && state.trackedFileCount >= MAX_SHARED_INVENTORY_FILES) {
            state.disableTracking()
            return
        }
        while (trackedFileCount(state) >= MAX_SHARED_INVENTORY_FILES) {
            val oldestOtherDirectory = sharedDirectoryInventories.indexOfFirst { it !== state }
            if (oldestOtherDirectory < 0) {
                state.disableTracking()
                return
            }
            sharedDirectoryInventories.removeAt(oldestOtherDirectory)
        }
        state.put(path, objectBytes, markerBytes, accessStamp)
    }

    private fun trackOrphanMarker(state: DirectoryInventory, path: Path, bytes: Long) {
        if (path in state.orphanMarkers) {
            state.putOrphanMarker(path, bytes)
            return
        }
        if (path !in state.orphanMarkers && state.trackedFileCount >= MAX_SHARED_INVENTORY_FILES) {
            state.disableTracking()
            return
        }
        while (trackedFileCount(state) >= MAX_SHARED_INVENTORY_FILES) {
            val oldestOtherDirectory = sharedDirectoryInventories.indexOfFirst { it !== state }
            if (oldestOtherDirectory < 0) {
                state.disableTracking()
                return
            }
            sharedDirectoryInventories.removeAt(oldestOtherDirectory)
        }
        state.putOrphanMarker(path, bytes)
    }

    private fun trackedFileCount(state: DirectoryInventory): Int {
        val currentIsRegistered = sharedDirectoryInventories.any { it === state }
        return sharedDirectoryInventories.sumOf { it.trackedFileCount } +
            if (currentIsRegistered) 0 else state.trackedFileCount
    }

    private fun removeEntryBestEffort(root: Path, objectPath: Path, state: DirectoryInventory?) {
        try {
            fileSystem.delete(objectPath)
            val marker = accessPath(root, objectPath)
            fileSystem.delete(marker)
            state?.remove(objectPath)
            state?.removeOrphanMarker(marker)
        } catch (_: Throwable) {
            state?.valid = false
        }
    }

    private fun removeBestEffort(path: Path): Boolean = runCatching {
        fileSystem.delete(path)
        true
    }.getOrDefault(false)

    private fun canonicalRoot(root: Path): Path = runCatching { fileSystem.canonicalize(root) }.getOrDefault(root)

    private fun directoryInventory(root: Path): DirectoryInventory {
        val existingIndex = sharedDirectoryInventories.indexOfFirst { it.fileSystem === fileSystem && it.root == root }
        if (existingIndex >= 0) {
            val existing = sharedDirectoryInventories.removeAt(existingIndex)
            if (existing.valid) {
                sharedDirectoryInventories += existing
                return existing
            }
        }
        if (sharedDirectoryInventories.size >= MAX_SHARED_DIRECTORIES) sharedDirectoryInventories.removeAt(0)
        val rebuilt = rebuildDirectoryInventory(root)
        while (
            sharedDirectoryInventories.isNotEmpty() &&
            (
                sharedDirectoryInventories.size >= MAX_SHARED_DIRECTORIES ||
                    sharedDirectoryInventories.sumOf { it.trackedFileCount } + rebuilt.trackedFileCount >
                    MAX_SHARED_INVENTORY_FILES
                )
        ) {
            sharedDirectoryInventories.removeAt(0)
        }
        sharedDirectoryInventories += rebuilt
        return rebuilt
    }

    private fun rebuildDirectoryInventory(
        root: Path,
        state: DirectoryInventory? = null,
        preserveTemporary: Path? = null,
    ): DirectoryInventory {
        val target = state ?: DirectoryInventory(fileSystem, root)
        target.clear()
        val files = fileSystem.listOrNull(root).orEmpty()
        val objectFiles = files.filter { it.name.endsWith(OBJECT_SUFFIX) }
        val objectNames = objectFiles.mapTo(mutableSetOf()) { it.name }

        files.filter { it.name.endsWith(TEMP_SUFFIX) && it != preserveTemporary }
            .forEach { temporary ->
                if (!removeBestEffort(temporary)) {
                    val size = fileSystem.metadataOrNull(temporary)?.size ?: 0L
                    if (size > 0L) trackOrphanMarker(target, temporary, size)
                }
            }
        files.filter { it.name.endsWith(ACCESS_SUFFIX) && it.name.removeSuffix(ACCESS_SUFFIX) !in objectNames }
            .forEach { marker ->
                val size = fileSystem.metadataOrNull(marker)?.size ?: 0L
                if (!removeBestEffort(marker) && size > 0L) trackOrphanMarker(target, marker, size)
            }

        for (objectPath in objectFiles) {
            if (!target.trackable) break
            val objectMetadata = fileSystem.metadataOrNull(objectPath) ?: continue
            val marker = accessPath(root, objectPath)
            val markerMetadata = fileSystem.metadataOrNull(marker)
            val stamp = (if (markerMetadata != null) readAccessStamp(marker) else null)
                ?: objectMetadata.lastModifiedAtMillis
                ?: 0L
            trackEntry(
                target,
                objectPath,
                objectMetadata.size ?: 0L,
                markerMetadata?.size ?: 0L,
                stamp,
            )
        }
        target.valid = true
        return target
    }

    private suspend fun invalidateDirectory(root: Path) {
        filesystemMutex.withLock {
            val canonical = canonicalRoot(root)
            sharedDirectoryInventories.removeAll { it.fileSystem === fileSystem && it.root == canonical }
        }
    }

    private fun fileName(key: SyncPersistentGitObjectKey): String = "${key.contextDigest()}$OBJECT_SUFFIX"

    private fun SyncPersistentGitObjectKey.contextDigest(): String = Buffer().apply {
        writeString(apiOrigin)
        writeString(repositoryId)
        writeString(repository)
        writeString(branch)
        writeString(kind.name)
        writeString(objectFormat)
        writeString(objectOid)
        writeString(validationScope)
        writeString(connectionRevision)
    }.readByteArray().toByteString().sha256().hex()

    private fun Buffer.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun randomSuffix(): String = (0..1).map { RANDOM.nextInt().toUInt().toString(16) }.joinToString("")

    private data class DirectoryEntry(
        val objectBytes: Long,
        val markerBytes: Long,
        val accessStamp: Long,
    ) {
        val totalBytes: Long get() = objectBytes + markerBytes
    }

    private class DirectoryInventory(
        val fileSystem: FileSystem,
        val root: Path,
    ) {
        val entries = mutableMapOf<Path, DirectoryEntry>()
        val orphanMarkers = mutableMapOf<Path, Long>()
        var totalBytes = 0L
            private set
        var latestAccessStamp = 0L
        var valid = true
        var trackable = true

        val trackedFileCount: Int get() = entries.size + orphanMarkers.size

        fun put(path: Path, objectBytes: Long, markerBytes: Long, accessStamp: Long) {
            if (path !in entries && trackedFileCount >= MAX_SHARED_INVENTORY_FILES) {
                clear()
                trackable = false
                valid = true
                return
            }
            if (!trackable) return
            val previous = entries.put(path, DirectoryEntry(objectBytes, markerBytes, accessStamp))
            totalBytes -= previous?.totalBytes ?: 0L
            totalBytes += objectBytes + markerBytes
            if (accessStamp > latestAccessStamp) latestAccessStamp = accessStamp
        }

        fun remove(path: Path) {
            totalBytes -= entries.remove(path)?.totalBytes ?: 0L
        }

        fun putOrphanMarker(path: Path, bytes: Long) {
            if (path !in orphanMarkers && trackedFileCount >= MAX_SHARED_INVENTORY_FILES) {
                clear()
                trackable = false
                valid = true
                return
            }
            if (!trackable) return
            val previous = orphanMarkers.put(path, bytes)
            totalBytes -= previous ?: 0L
            totalBytes += bytes
        }

        fun removeOrphanMarker(path: Path) {
            totalBytes -= orphanMarkers.remove(path) ?: 0L
        }

        fun disableTracking() {
            // Oversized directories stay read-validated but stop accepting writes until a later cold rebuild.
            clear()
            trackable = false
            valid = true
        }

        fun clear() {
            entries.clear()
            orphanMarkers.clear()
            totalBytes = 0L
            latestAccessStamp = 0L
            valid = false
            trackable = true
        }
    }

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024

        private const val CACHE_DIRECTORY = "github-git-objects-v1"
        private const val OBJECT_SUFFIX = ".obj"
        private const val ACCESS_SUFFIX = ".access"
        private const val TEMP_SUFFIX = ".tmp"
        private const val ACCESS_MARKER_BYTES = 8L
        private const val MAX_METADATA_FIELD_BYTES = 16 * 1024
        private const val SHA256_HEX_LENGTH = 64
        private const val MAX_SHARED_DIRECTORIES = 4
        private const val MAX_SHARED_INVENTORY_FILES = 20_000
        private val MAGIC = "MHGOBJ01".encodeToByteArray()
        private val RANDOM = kotlin.random.Random.Default
        private val filesystemMutex = Mutex()
        private val sharedDirectoryInventories = mutableListOf<DirectoryInventory>()
    }
}
