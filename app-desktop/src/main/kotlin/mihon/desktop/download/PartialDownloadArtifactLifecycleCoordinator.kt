package mihon.desktop.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import mihon.domain.reader.partial.PartialReaderPageCandidate
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface PartialPageReadLocation {
    data class FilePage(val file: File) : PartialPageReadLocation

    data class CbzEntry(
        val archive: File,
        val entryName: String,
    ) : PartialPageReadLocation
}

interface PartialPageReadLeaseSource {
    fun acquire(candidate: PartialReaderPageCandidate): PartialPageReadLease?
}

interface PartialPageReadLease : AutoCloseable {
    val initialLocation: PartialPageReadLocation

    /** Returns one alternate location after a page or chapter publish race. */
    fun reProbeLocation(): PartialPageReadLocation?
}

object DirectPartialPageReadLeaseSource : PartialPageReadLeaseSource {
    override fun acquire(candidate: PartialReaderPageCandidate): PartialPageReadLease = object : PartialPageReadLease {
        private val initialFile = File(candidate.opaqueLocation)

        override val initialLocation = PartialPageReadLocation.FilePage(initialFile)

        override fun reProbeLocation(): PartialPageReadLocation? {
            val stagingDirectory = initialFile.parentFile ?: return null
            if (!stagingDirectory.name.endsWith(DesktopDownloadProvider.TMP_DIR_SUFFIX)) return null
            val finalDirectory = File(
                stagingDirectory.parentFile,
                stagingDirectory.name.removeSuffix(DesktopDownloadProvider.TMP_DIR_SUFFIX),
            )
            val finalPage = File(finalDirectory, initialFile.name)
            if (finalPage.isFile) return PartialPageReadLocation.FilePage(finalPage)
            val archive = CbzCreator.defaultOutputFile(finalDirectory)
            return if (archive.isFile) {
                PartialPageReadLocation.CbzEntry(archive, initialFile.name)
            } else {
                PartialPageReadLocation.FilePage(finalPage)
            }
        }

        override fun close() = Unit
    }
}

internal data class PartialDirectoryPublishToken(
    val chapterId: Long,
    val attemptGeneration: Long,
    val sequence: Long,
    val stagingDirectory: File,
    val finalDirectory: File,
)

class PartialDownloadArtifactLifecycleCoordinator : PartialPageReadLeaseSource {
    private data class AttemptKey(
        val chapterId: Long,
        val attemptGeneration: Long,
    )

    private data class CandidateKey(
        val attemptGeneration: Long,
        val readerOrdinal: Int,
        val artifactSourcePageIndex: Int,
        val committedRevision: Long,
        val originalLocation: String,
    )

    private enum class AttemptStatus {
        ACTIVE,
        COMPLETED,
        RETIRED,
    }

    private class PageRecord(
        val candidateKey: CandidateKey,
        val stagingDirectory: File,
        var currentLocation: PartialPageReadLocation,
        var descriptorIssued: Boolean = false,
        var publishFallbackLocation: PartialPageReadLocation? = null,
        var publishSequence: Long? = null,
    )

    private class AttemptState(
        val key: AttemptKey,
        val pages: MutableMap<CandidateKey, PageRecord> = mutableMapOf(),
        var status: AttemptStatus = AttemptStatus.ACTIVE,
        var activeLeases: Int = 0,
        var drained: CompletableDeferred<Unit> = completedSignal(),
        var removeWhenDrained: Boolean = false,
        var publishToken: PartialDirectoryPublishToken? = null,
    )

    private class ArtifactLeaseState(
        var activeLeases: Int = 0,
        var drained: CompletableDeferred<Unit> = completedSignal(),
    )

    private val lock = Any()
    private val publishSequence = AtomicLong()
    private val attempts = mutableMapOf<AttemptKey, AttemptState>()
    private val pages = mutableMapOf<CandidateKey, Pair<AttemptState, PageRecord>>()
    private val publishRecords = mutableMapOf<Long, List<PageRecord>>()
    private val artifactLeases = mutableMapOf<String, ArtifactLeaseState>()

    fun registerCommittedPage(
        chapterId: Long,
        candidate: PartialReaderPageCandidate,
    ): Boolean = synchronized(lock) {
        if (candidate.artifactSourcePageIndex < 0) return@synchronized false
        val attemptKey = AttemptKey(chapterId, candidate.attemptGeneration)
        val attempt = attempts.getOrPut(attemptKey) { AttemptState(attemptKey) }
        if (attempt.status != AttemptStatus.ACTIVE) return@synchronized false
        val candidateKey = candidate.key()
        val existing = pages[candidateKey]
        if (existing != null) return@synchronized existing.first === attempt
        val originalFile = File(candidate.opaqueLocation)
        val record = PageRecord(
            candidateKey = candidateKey,
            stagingDirectory = originalFile.parentFile,
            currentLocation = PartialPageReadLocation.FilePage(originalFile),
        )
        attempt.pages[candidateKey] = record
        pages[candidateKey] = attempt to record
        true
    }

    internal fun unregisterCommittedPage(
        chapterId: Long,
        candidate: PartialReaderPageCandidate,
    ) = synchronized(lock) {
        val candidateKey = candidate.key()
        val (attempt, _) = pages[candidateKey] ?: return@synchronized
        if (attempt.key != AttemptKey(chapterId, candidate.attemptGeneration)) return@synchronized
        attempt.pages.remove(candidateKey)
        pages.remove(candidateKey)
        if (attempt.pages.isEmpty() && attempt.activeLeases == 0) attempts.remove(attempt.key, attempt)
    }

    override fun acquire(candidate: PartialReaderPageCandidate): PartialPageReadLease? = synchronized(lock) {
        val (attempt, record) = pages[candidate.key()] ?: return@synchronized null
        if (attempt.status == AttemptStatus.RETIRED) return@synchronized null
        record.descriptorIssued = true
        if (attempt.activeLeases == 0) attempt.drained = CompletableDeferred()
        attempt.activeLeases++
        val initialLocation = record.currentLocation
        val artifactDependency = (initialLocation as? PartialPageReadLocation.FilePage)
            ?.file
            ?.takeIf { file -> file.isWithin(record.stagingDirectory) }
            ?.let { record.stagingDirectory }
        artifactDependency?.let(::acquireArtifactLeaseLocked)
        CoordinatedPartialPageReadLease(
            coordinator = this,
            attempt = attempt,
            record = record,
            initialLocation = initialLocation,
            artifactDependency = artifactDependency,
        )
    }

    fun markDescriptorIssued(chapterId: Long, candidate: PartialReaderPageCandidate): Boolean = synchronized(lock) {
        val (attempt, record) = pages[candidate.key()] ?: return@synchronized false
        if (
            attempt.key != AttemptKey(chapterId, candidate.attemptGeneration) ||
            attempt.status == AttemptStatus.RETIRED
        ) {
            return@synchronized false
        }
        record.descriptorIssued = true
        true
    }

    internal fun prepareDirectoryPublish(
        chapterId: Long,
        attemptGeneration: Long,
        stagingDirectory: File,
        finalDirectory: File,
    ): PartialDirectoryPublishToken = synchronized(lock) {
        val attempt = attempts[AttemptKey(chapterId, attemptGeneration)]
            ?: throw IllegalStateException("Partial attempt is not registered")
        check(attempt.status == AttemptStatus.ACTIVE) { "Partial attempt cannot publish in ${attempt.status}" }
        check(attempt.publishToken == null) { "A chapter publish is already in progress" }
        val token = PartialDirectoryPublishToken(
            chapterId = chapterId,
            attemptGeneration = attemptGeneration,
            sequence = publishSequence.incrementAndGet(),
            stagingDirectory = stagingDirectory,
            finalDirectory = finalDirectory,
        )
        val affected = attempts.values
            .asSequence()
            .filter { state -> state.key.chapterId == chapterId }
            .flatMap { state -> state.pages.values.asSequence() }
            .mapNotNull { record ->
                val file = (record.currentLocation as? PartialPageReadLocation.FilePage)?.file ?: return@mapNotNull null
                val relative = file.relativePathWithin(stagingDirectory) ?: return@mapNotNull null
                record.publishFallbackLocation = PartialPageReadLocation.FilePage(File(finalDirectory, relative))
                record.publishSequence = token.sequence
                record
            }
            .toList()
        publishRecords[token.sequence] = affected
        attempt.publishToken = token
        token
    }

    internal fun commitDirectoryPublish(token: PartialDirectoryPublishToken): Boolean = synchronized(lock) {
        val attempt = attempts[AttemptKey(token.chapterId, token.attemptGeneration)] ?: return@synchronized false
        if (attempt.publishToken != token) return@synchronized false
        publishRecords.remove(token.sequence).orEmpty().forEach { record ->
            if (record.publishSequence != token.sequence) return@forEach
            record.publishFallbackLocation?.let { record.currentLocation = it }
            record.publishFallbackLocation = null
            record.publishSequence = null
        }
        attempt.publishToken = null
        true
    }

    internal fun abortDirectoryPublish(token: PartialDirectoryPublishToken) = synchronized(lock) {
        val attempt = attempts[AttemptKey(token.chapterId, token.attemptGeneration)] ?: return@synchronized
        if (attempt.publishToken != token) return@synchronized
        publishRecords.remove(token.sequence).orEmpty().forEach { record ->
            if (record.publishSequence != token.sequence) return@forEach
            record.publishFallbackLocation = null
            record.publishSequence = null
        }
        attempt.publishToken = null
    }

    internal fun commitCbzPublish(
        chapterId: Long,
        attemptGeneration: Long,
        stagingDirectory: File,
        archive: File,
    ): Boolean = synchronized(lock) {
        val current = attempts[AttemptKey(chapterId, attemptGeneration)] ?: return@synchronized false
        if (current.status == AttemptStatus.RETIRED) return@synchronized false
        attempts.values
            .asSequence()
            .filter { state -> state.key.chapterId == chapterId }
            .flatMap { state -> state.pages.values.asSequence() }
            .forEach { record ->
                val file = (record.currentLocation as? PartialPageReadLocation.FilePage)?.file ?: return@forEach
                if (file.relativePathWithin(stagingDirectory) == null) return@forEach
                val published = PartialPageReadLocation.CbzEntry(archive, file.name)
                record.currentLocation = published
                record.publishFallbackLocation = published
            }
        true
    }

    fun markAttemptCompleted(chapterId: Long, attemptGeneration: Long): Boolean = synchronized(lock) {
        val attempt = attempts[AttemptKey(chapterId, attemptGeneration)] ?: return@synchronized false
        if (attempt.status == AttemptStatus.RETIRED) return@synchronized false
        attempt.status = AttemptStatus.COMPLETED
        attempt.pages.values
            .filterNot(PageRecord::descriptorIssued)
            .map(PageRecord::candidateKey)
            .forEach { candidateKey ->
                attempt.pages.remove(candidateKey)
                if (pages[candidateKey]?.first === attempt) pages.remove(candidateKey)
            }
        if (attempt.pages.isEmpty() && attempt.activeLeases == 0) attempts.remove(attempt.key, attempt)
        true
    }

    fun retireAttempt(chapterId: Long, attemptGeneration: Long): Deferred<Unit> = synchronized(lock) {
        val attempt = attempts[AttemptKey(chapterId, attemptGeneration)] ?: return@synchronized completedSignal()
        attempt.status = AttemptStatus.RETIRED
        attempt.drained
    }

    fun removeAttempt(chapterId: Long, attemptGeneration: Long) = synchronized(lock) {
        val key = AttemptKey(chapterId, attemptGeneration)
        val attempt = attempts[key] ?: return@synchronized
        attempt.status = AttemptStatus.RETIRED
        if (attempt.activeLeases == 0) {
            removeAttemptLocked(key, attempt)
        } else {
            attempt.removeWhenDrained = true
        }
    }

    fun awaitArtifactDrain(directory: File): Deferred<Unit> = synchronized(lock) {
        artifactLeases[directory.artifactKey()]?.drained ?: completedSignal()
    }

    internal fun holdsLockByCurrentThread(): Boolean = Thread.holdsLock(lock)

    internal fun activeLeaseCount(chapterId: Long, attemptGeneration: Long): Int = synchronized(lock) {
        attempts[AttemptKey(chapterId, attemptGeneration)]?.activeLeases ?: 0
    }

    private fun reProbeLocation(
        record: PageRecord,
        initialLocation: PartialPageReadLocation,
    ): PartialPageReadLocation? = synchronized(lock) {
        listOfNotNull(record.currentLocation, record.publishFallbackLocation)
            .firstOrNull { it != initialLocation }
    }

    private fun acquireArtifactLeaseLocked(directory: File) {
        val state = artifactLeases.getOrPut(directory.artifactKey(), ::ArtifactLeaseState)
        if (state.activeLeases == 0) state.drained = CompletableDeferred()
        state.activeLeases++
    }

    private fun release(attempt: AttemptState, artifactDependency: File?) = synchronized(lock) {
        check(attempt.activeLeases > 0) { "Partial page lease underflow" }
        attempt.activeLeases--
        artifactDependency?.let(::releaseArtifactLeaseLocked)
        if (attempt.activeLeases == 0) {
            attempt.drained.complete(Unit)
            if (attempt.removeWhenDrained) removeAttemptLocked(attempt.key, attempt)
        }
    }

    private fun releaseArtifactLeaseLocked(directory: File) {
        val key = directory.artifactKey()
        val state = artifactLeases[key] ?: error("Partial artifact lease underflow")
        check(state.activeLeases > 0) { "Partial artifact lease underflow" }
        state.activeLeases--
        if (state.activeLeases == 0) {
            state.drained.complete(Unit)
            artifactLeases.remove(key, state)
        }
    }

    private fun removeAttemptLocked(key: AttemptKey, attempt: AttemptState) {
        if (attempts[key] !== attempt) return
        attempt.pages.keys.forEach { candidateKey ->
            if (pages[candidateKey]?.first === attempt) pages.remove(candidateKey)
        }
        attempt.publishToken?.let { token -> publishRecords.remove(token.sequence) }
        attempts.remove(key)
    }

    private class CoordinatedPartialPageReadLease(
        private val coordinator: PartialDownloadArtifactLifecycleCoordinator,
        private val attempt: AttemptState,
        private val record: PageRecord,
        override val initialLocation: PartialPageReadLocation,
        private val artifactDependency: File?,
    ) : PartialPageReadLease {
        private val closed = AtomicBoolean()

        override fun reProbeLocation(): PartialPageReadLocation? = coordinator.reProbeLocation(record, initialLocation)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            coordinator.release(attempt, artifactDependency)
        }
    }

    private fun PartialReaderPageCandidate.key() = CandidateKey(
        attemptGeneration = attemptGeneration,
        readerOrdinal = readerOrdinal,
        artifactSourcePageIndex = artifactSourcePageIndex,
        committedRevision = committedRevision,
        originalLocation = File(opaqueLocation).absolutePath,
    )

    private companion object {
        fun completedSignal() = CompletableDeferred<Unit>().apply { complete(Unit) }

        fun File.artifactKey(): String = absoluteFile.normalize().path

        fun File.isWithin(directory: File): Boolean = relativePathWithin(directory) != null

        fun File.relativePathWithin(directory: File): String? = runCatching {
            val relative = absoluteFile.normalize().relativeTo(directory.absoluteFile.normalize())
            relative.path.takeUnless { it.startsWith("..") }
        }.getOrNull()
    }
}
