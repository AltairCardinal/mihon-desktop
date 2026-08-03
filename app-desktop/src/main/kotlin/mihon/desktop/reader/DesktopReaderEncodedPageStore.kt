package mihon.desktop.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.storage.ByteBudgetEncodedPageStoreIndex
import mihon.domain.reader.storage.EncodedPageEvictionResult
import mihon.domain.reader.storage.EncodedPageStoreDiagnostics
import mihon.domain.reader.storage.EncodedPageStoreEntry
import mihon.domain.reader.storage.EncodedPageStoreLifecycleResult
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import mihon.domain.reader.storage.ReaderEncodedPageStore
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class DesktopReaderEncodedPageStoreCoordinator(
    cacheDirectory: File,
    maxBytes: Long = DesktopReaderEncodedPageStore.DEFAULT_MAX_BYTES,
) {
    private val sharedState = DesktopReaderEncodedPageStoreSharedState(cacheDirectory, maxBytes)

    fun openSessionStore(): DesktopReaderEncodedPageStore = DesktopReaderEncodedPageStore(sharedState)
}

internal class DesktopReaderEncodedPageStoreSharedState(
    val cacheDirectory: File,
    val maxBytes: Long,
) {
    val lock = Any()
    val index = ByteBudgetEncodedPageStoreIndex(maxBytes)
    val writeGates = mutableMapOf<EncodedPageRef, DesktopReaderEncodedPageWriteGate>()
    val activeWriteFiles = mutableMapOf<EncodedPageRef, File>()
    val leases = mutableMapOf<Any, MutableSet<EncodedPageRef>>()
}

internal class DesktopReaderEncodedPageWriteGate(
    val mutex: Mutex = Mutex(),
    var users: Int = 0,
)

class DesktopReaderEncodedPageStore internal constructor(
    private val sharedState: DesktopReaderEncodedPageStoreSharedState,
) : ReaderEncodedPageStore {
    constructor(
        cacheDirectory: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ) : this(DesktopReaderEncodedPageStoreSharedState(cacheDirectory, maxBytes))

    private val leaseId = Any()
    private var leaseActive = false

    override suspend fun beginSession(retainedRefs: Set<EncodedPageRef>): EncodedPageStoreLifecycleResult =
        synchronized(sharedState.lock) {
            prepareDirectoryLocked()
            if (leaseActive) return@synchronized currentLifecycleLocked(retainedRefs)

            val result = if (sharedState.leases.isEmpty()) {
                val availableEntries = scanAvailableEntriesLocked(retainedRefs)
                val availableRefs = availableEntries.mapTo(mutableSetOf(), EncodedPageStoreEntry::ref)
                sharedState.index.beginSession(
                    availableEntries = availableEntries,
                    missingRefs = retainedRefs.filterTo(mutableSetOf()) { it !in availableRefs },
                ).also { lifecycle -> lifecycle.evictedRefs.forEach(::deleteOwnedFile) }
            } else {
                currentLifecycleLocked(retainedRefs)
            }
            sharedState.leases[leaseId] = retainedRefs
                .filterTo(mutableSetOf()) { ref -> ref.fileOrNull()?.hasEncodedBytes() == true }
            leaseActive = true
            result
        }

    override suspend fun contains(ref: EncodedPageRef): Boolean = synchronized(sharedState.lock) {
        val file = ref.fileOrNull()
        val exists = file?.hasEncodedBytes() == true
        if (file?.isOwnedCacheFile() == true) {
            sharedState.index.recordLookup(ref, exists, file.length().takeIf { exists })
            if (exists) activeLeaseLocked().add(ref)
        }
        exists
    }

    override suspend fun store(
        ref: EncodedPageRef,
        writer: suspend () -> Long,
    ): EncodedPageStoreWriteResult {
        require(ref.fileOrNull()?.isOwnedCacheFile() == true) { "Desktop encoded writes must target the reader cache" }
        synchronized(sharedState.lock) { activeLeaseLocked() }
        val gate = acquireWriteGate(ref)
        try {
            return gate.mutex.withLock { storeAtomically(ref, writer) }
        } finally {
            releaseWriteGate(ref, gate)
        }
    }

    override suspend fun evict(ref: EncodedPageRef): EncodedPageEvictionResult = synchronized(sharedState.lock) {
        sharedState.index.evict(ref).also { result ->
            if (result is EncodedPageEvictionResult.Evicted) {
                sharedState.leases.values.forEach { it.remove(ref) }
                deleteOwnedFile(ref)
            }
        }
    }

    override fun diagnostics(): EncodedPageStoreDiagnostics =
        synchronized(sharedState.lock) { sharedState.index.diagnostics() }

    override fun endSession(): EncodedPageStoreDiagnostics = synchronized(sharedState.lock) {
        if (!leaseActive) return@synchronized sharedState.index.diagnostics()
        sharedState.leases.remove(leaseId)
        leaseActive = false
        if (sharedState.leases.isEmpty()) {
            sharedState.index.endSession()
        } else {
            sharedState.index.diagnostics()
        }
    }

    fun cacheRef(pageId: ReaderPageId, discriminator: String): EncodedPageRef {
        sharedState.cacheDirectory.mkdirs()
        val digest = discriminator.hashCode().toUInt().toString(16)
        val file = sharedState.cacheDirectory.resolve(
            "chapter-${pageId.chapterId.value}-page-${pageId.sourcePageIndex}-$digest.encoded",
        )
        return EncodedPageRef(file.toURI().toString())
    }

    fun read(ref: EncodedPageRef): ByteArray? = ref.fileOrNull()
        ?.takeIf { it.hasEncodedBytes() }
        ?.readBytes()

    /** Resolves the private staging file while inside [store], otherwise the committed file. */
    internal fun destinationFile(ref: EncodedPageRef): File = synchronized(sharedState.lock) {
        sharedState.activeWriteFiles[ref]
    } ?: committedFile(ref)

    internal fun sharesCoordinatorWith(other: DesktopReaderEncodedPageStore): Boolean =
        sharedState === other.sharedState

    private fun prepareDirectoryLocked() {
        sharedState.cacheDirectory.mkdirs()
        val activePartials = sharedState.activeWriteFiles.values.toSet()
        sharedState.cacheDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) && it !in activePartials }
            .forEach(File::delete)
        sharedState.cacheDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(ENCODED_SUFFIX) && it.length() <= 0L }
            .forEach(File::delete)
    }

    private fun scanAvailableEntriesLocked(retainedRefs: Set<EncodedPageRef>): List<EncodedPageStoreEntry> {
        val availableEntries = linkedMapOf<EncodedPageRef, EncodedPageStoreEntry>()
        sharedState.cacheDirectory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(ENCODED_SUFFIX) && it.length() > 0L }
            .sortedWith(compareBy<File>(File::lastModified).thenBy(File::getName))
            .forEach { file ->
                val ref = EncodedPageRef(file.toURI().toString())
                availableEntries[ref] = EncodedPageStoreEntry(ref, file.length())
            }
        retainedRefs.forEach { ref ->
            ref.fileOrNull()
                ?.takeIf { it.isOwnedCacheFile() && it.hasEncodedBytes() }
                ?.let { file ->
                    availableEntries.remove(ref)
                    availableEntries[ref] = EncodedPageStoreEntry(ref, file.length())
                }
        }
        return availableEntries.values.toList()
    }

    private fun currentLifecycleLocked(retainedRefs: Set<EncodedPageRef>): EncodedPageStoreLifecycleResult {
        val availableRetained = retainedRefs.filterTo(mutableSetOf()) { ref ->
            val file = ref.fileOrNull()
            val exists = file?.hasEncodedBytes() == true
            if (file?.isOwnedCacheFile() == true) {
                sharedState.index.recordLookup(ref, exists, file.length().takeIf { exists })
            }
            exists
        }
        return EncodedPageStoreLifecycleResult(
            availableRefs = sharedState.index.diagnostics().refs,
            missingRefs = retainedRefs - availableRetained,
            evictedRefs = emptySet(),
        )
    }

    private suspend fun storeAtomically(
        ref: EncodedPageRef,
        writer: suspend () -> Long,
    ): EncodedPageStoreWriteResult {
        sharedState.cacheDirectory.mkdirs()
        val destination = committedFile(ref)
        val partial = File.createTempFile("${destination.name}.", PARTIAL_SUFFIX, sharedState.cacheDirectory)
        synchronized(sharedState.lock) { sharedState.activeWriteFiles[ref] = partial }
        try {
            val reportedByteCount = writer()
            val actualByteCount = partial.length().takeIf { partial.isFile } ?: 0L
            check(reportedByteCount > 0L && actualByteCount > 0L) { "Encoded page write produced no bytes" }
            check(reportedByteCount == actualByteCount) {
                "Encoded page writer reported $reportedByteCount bytes but staged $actualByteCount"
            }
            val entry = EncodedPageStoreEntry(ref, actualByteCount)
            return synchronized(sharedState.lock) {
                val activeLease = activeLeaseLocked()
                when (val plan = sharedState.index.planCommit(entry)) {
                    is EncodedPageStoreWriteResult.RejectedQuota -> plan
                    is EncodedPageStoreWriteResult.Stored -> {
                        val leasedRefs = sharedState.leases.values.flatten().toSet()
                        if (plan.evictedRefs.any(leasedRefs::contains)) {
                            EncodedPageStoreWriteResult.RejectedQuota(entry, sharedState.maxBytes)
                        } else {
                            replaceCommittedFile(partial, destination)
                            val committed = sharedState.index.commit(entry) as EncodedPageStoreWriteResult.Stored
                            activeLease.add(ref)
                            committed.evictedRefs.forEach(::deleteOwnedFile)
                            committed
                        }
                    }
                }
            }
        } finally {
            synchronized(sharedState.lock) { sharedState.activeWriteFiles.remove(ref, partial) }
            partial.delete()
        }
    }

    private fun activeLeaseLocked(): MutableSet<EncodedPageRef> =
        checkNotNull(sharedState.leases[leaseId].takeIf { leaseActive }) { "Encoded page store session is not active" }

    private fun committedFile(ref: EncodedPageRef): File = requireNotNull(ref.fileOrNull()) {
        "Encoded page reference is not a file URI: ${ref.value}"
    }

    private fun replaceCommittedFile(partial: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun acquireWriteGate(ref: EncodedPageRef): DesktopReaderEncodedPageWriteGate =
        synchronized(sharedState.lock) {
            sharedState.writeGates.getOrPut(ref, ::DesktopReaderEncodedPageWriteGate).also { it.users++ }
        }

    private fun releaseWriteGate(ref: EncodedPageRef, gate: DesktopReaderEncodedPageWriteGate) =
        synchronized(sharedState.lock) {
            gate.users--
            if (gate.users == 0) sharedState.writeGates.remove(ref, gate)
        }

    private fun deleteOwnedFile(ref: EncodedPageRef) {
        ref.fileOrNull()?.takeIf { it.isOwnedCacheFile() }?.delete()
    }

    private fun EncodedPageRef.fileOrNull(): File? = runCatching {
        val uri = URI(value)
        if (uri.scheme.equals("file", ignoreCase = true)) File(uri) else null
    }.getOrNull()

    private fun File.hasEncodedBytes(): Boolean = isFile && length() > 0L

    private fun File.isOwnedCacheFile(): Boolean = runCatching {
        canonicalFile.toPath().startsWith(sharedState.cacheDirectory.canonicalFile.toPath())
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L * 1024L
        private const val ENCODED_SUFFIX = ".encoded"
        private const val PARTIAL_SUFFIX = ".part"
    }
}
