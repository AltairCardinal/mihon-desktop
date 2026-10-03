package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import mihon.desktop.domain.DesktopSystemNotifier
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.resolveSourceImageUrl
import mihon.desktop.extension.safeSourceCall
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStateMachine
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.download.DownloadRepository
import mihon.domain.error.AppError
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.PartialCommittedPage
import mihon.domain.reader.partial.PartialDownloadSnapshot
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTableCompleteness
import mihon.domain.reader.partial.PartialPageTableEntry
import mihon.domain.reader.partial.PartialPageTablePolicy
import mihon.domain.reader.partial.PartialPageTableValidation
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.domain.task.NotificationEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.data.download.PersistentDownloadStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.toSourceChapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun interface ChapterPackager {
    fun packageChapter(sourceDirectory: File, outputFile: File, committedPages: List<File>): Boolean
}

/**
 * Manages the chapter download queue.
 *
 * Follows Android Mihon's download lifecycle:
 * 1. Downloads go into a `_tmp` directory
 * 2. On success, `_tmp` is renamed to the final directory
 * 3. On cancel/error, `_tmp` is cleaned up
 * 4. Only final directories (without `_tmp`) are considered "downloaded"
 */
class DesktopDownloadManager(
    private val provider: DesktopDownloadProvider,
    private val networkHelper: NetworkHelper? = null, // null = inject lazily at runtime
    private val downloadPreferences: DesktopDownloadPreferences? = null, // null = inject lazily
    /** Injectable scope for testing. Production uses Dispatchers.IO. */
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val store: PersistentDownloadStore? = null,
    private val stateMachine: DownloadQueueStateMachine = DownloadQueueStateMachine(),
    private val httpClient: OkHttpClient? = null,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val chapterPackager: ChapterPackager = ChapterPackager { source, output, committedPages ->
        CbzCreator.create(
            sourceDir = source,
            outputFile = output,
            expectedImageFiles = committedPages,
        )
    },
    private val artifactCleaner: (File) -> Boolean = { artifact -> artifact.deleteRecursively() },
    private val queuePersister: ((List<DownloadQueueEntry>) -> Unit)? = null,
    private val retirementAwaitObserver: (Long) -> Unit = {},
    private val retirementCleanupAwaitObserver: (Long) -> Unit = {},
    private val retirementCleanupFinishedObserver: (Long, Boolean) -> Unit = { _, _ -> },
    private val fileOperations: DownloadFileOperations = DefaultDownloadFileOperations,
    private val taskNotifier: DesktopSystemNotifier? = null,
    private val sourceResolver: (Long) -> CatalogueSource? = { sourceId ->
        Injekt.get<SourceManager>().getCatalogueSources().find { it.id == sourceId }
    },
    private val sourceCallTimeoutMs: Long = 30_000L,
    private val downloadIdentityResolver: suspend (DownloadItem) -> DownloadChapterIdentity? = { null },
    private val partialIndexFileOperations: PartialDownloadIndexFileOperations =
        DefaultPartialDownloadIndexFileOperations,
    private val enqueueFileOperations: DownloadEnqueueFileOperations = DefaultDownloadEnqueueFileOperations,
    private val ioProbe: DownloadIoProbe = DownloadIoProbe.None,
    private val partialArtifactLifecycleCoordinator: PartialDownloadArtifactLifecycleCoordinator =
        PartialDownloadArtifactLifecycleCoordinator(),
    private val chapterCleanupDiagnosticObserver: (ChapterCleanupDiagnostic) -> Unit = {},
    private val queueEntryPersister: ((DownloadQueueEntry) -> Unit)? = null,
    private val chapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository? = null,
) : DownloadRepository, DesktopDownloadQueuePort, PartialDownloadSnapshotLookup {
    private data class DownloadAttempt(
        val item: DownloadItem,
        val generation: Long,
    ) {
        val key = DownloadAttemptKey(item.chapterId, generation)
    }

    private data class DownloadAttemptKey(
        val chapterId: Long,
        val generation: Long,
    )

    private data class CommittedPageRecord(
        val readerOrdinal: Int,
        val sourcePageIndex: Int,
        val file: File,
        val committedRevision: Long,
    )

    private class CommittedChapterIndex(
        val key: DownloadAttemptKey,
        val identity: DownloadChapterIdentity?,
        val pageTable: PartialPageTable,
        val directory: File,
        val pages: MutableCommittedPageIndex<CommittedPageRecord>,
        val rejectedFilesByOrdinal: ConcurrentHashMap<Int, List<File>> = ConcurrentHashMap(),
        val rejectedUnmappedFiles: List<File> = emptyList(),
    ) {
        val pageEntriesByOrdinal = pageTable.entries.associateBy(PartialPageTableEntry::readerOrdinal)
    }

    private data class PagePublishToken(
        val key: DownloadAttemptKey,
        val readerOrdinal: Int,
        val stagingFile: File,
        val finalFile: File,
    )

    private data class ResolvedPageTable(
        val pageTable: PartialPageTable,
        val source: CatalogueSource?,
    )

    private data class ChapterRetirement(
        val key: DownloadAttemptKey,
        val item: DownloadItem,
        val artifacts: List<File>,
        val producerDone: CompletableDeferred<Unit>,
        val completion: CompletableDeferred<RetirementCleanupResult> = CompletableDeferred(),
    )

    private data class RetirementCleanupResult(
        val success: Boolean,
        val error: Throwable? = null,
    )

    private data class CancelResult(
        val retirement: ChapterRetirement,
        val startCleanup: Boolean,
    )

    private val lifecycleLock = Any()
    private val queueStateLock = Any()
    private var stopped = false
    private var workerJob: Job? = null
    private val activeJobs = mutableSetOf<Job>()
    private val generationSequence = AtomicLong()
    private val committedRevisionSequence = AtomicLong()
    private val recoveredItems = store?.recover()?.map { it.toItem() } ?: emptyList()
    private val queueGenerations = recoveredItems.associate { it.chapterId to nextGeneration() }.toMutableMap()
    private val currentGenerations = ConcurrentHashMap(queueGenerations)
    private val currentStatuses = ConcurrentHashMap(
        recoveredItems.associate { item ->
            item.chapterId to DownloadQueueStatus.valueOf(item.status.name.replace("DONE", "COMPLETED"))
        },
    )
    private val resolvedDownloadIdentities = mutableMapOf<DownloadAttemptKey, DownloadChapterIdentity>()
    private val committedIndexes = ConcurrentHashMap<DownloadAttemptKey, CommittedChapterIndex>()
    private val reconcileCompletions = ConcurrentHashMap<DownloadAttemptKey, CompletableDeferred<Unit>>()
    private val pagePublishTokens = ConcurrentHashMap<Pair<DownloadAttemptKey, Int>, PagePublishToken>()
    private val chapterDirectoryPublisher = ChapterDirectoryPublisher(fileOperations)
    private val activeProducers = mutableMapOf<DownloadAttemptKey, CompletableDeferred<Unit>>()
    private val retirementsByChapter = mutableMapOf<Long, ChapterRetirement>()
    private val enqueuePreflights = mutableSetOf<Long>()
    private val capturedGenerations =
        mutableMapOf<Long, MutableList<java.lang.ref.WeakReference<CapturedDownloadGeneration>>>()
    private val _queue = MutableStateFlow(recoveredItems)
    override val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()
    private val _failures = MutableStateFlow(
        recoveredItems.mapNotNull { item ->
            item.failure?.let {
                item.chapterId to
                    it
            }
        }.toMap(),
    )
    val failures: StateFlow<Map<Long, AppError>> = _failures.asStateFlow()
    private val partialIndexRecoveryJob = workerScope.launch(start = CoroutineStart.LAZY) {
        recoveredItems.forEach { item ->
            try {
                if (item.pageTable.entries.isEmpty()) return@forEach
                if (
                    PartialPageTablePolicy.validate(item.pageTable) !in setOf(
                        PartialPageTableValidation.COMPLETE,
                        PartialPageTableValidation.PARTIAL,
                        PartialPageTableValidation.LEGACY_UNPROVEN,
                    )
                ) {
                    return@forEach
                }
                val generation = currentGenerations[item.chapterId] ?: return@forEach
                val attempt = DownloadAttempt(item, generation)
                val identity = item.downloadIdentity ?: downloadIdentityResolver(item) ?: return@forEach
                if (item.downloadIdentity == null && !registerIdentity(attempt, identity)) return@forEach
                val directory = firstExistingPartialDirectory(item, identity) ?: return@forEach
                reconcilePartialIndex(attempt, item.pageTable, identity, directory)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }
    internal val activeJobCount: Int
        get() = workerScope.coroutineContext[Job]?.children?.count() ?: 0
    override val queueEntries = queue.map { items -> items.mapIndexed { index, item -> item.toEntry(index.toLong()) } }

    private val _isPaused = MutableStateFlow(false)

    init {
        partialIndexRecoveryJob.start()
    }

    /** True when downloads are paused by the user. */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** Reserves only this directory operation's existing chapter identities. */
    suspend fun <T> withDirectoryChanges(chapterIds: Set<Long>, operation: suspend () -> T): T {
        return withDirectoryReservation(chapterIds, {}, operation)
    }

    private suspend fun <T> withDirectoryReservation(
        chapterIds: Set<Long>,
        validate: () -> Unit,
        operation: suspend () -> T,
    ): T {
        synchronized(queueStateLock) {
            validate()
            if (_queue.value.any { it.chapterId in chapterIds } ||
                chapterIds.any { it in enqueuePreflights || it in retirementsByChapter } ||
                activeProducers.keys.any { it.chapterId in chapterIds }
            ) {
                throw tachiyomi.domain.chapter.service.ChapterDirectoryDownloadConflictException()
            }
            enqueuePreflights.addAll(chapterIds)
        }
        try {
            return operation()
        } finally {
            synchronized(queueStateLock) { enqueuePreflights.removeAll(chapterIds) }
        }
    }

    internal suspend fun <T> withMigrationRollback(
        chapterIds: Set<Long>,
        artifacts: List<File>,
        operation: suspend () -> T,
    ): T =
        withDirectoryChanges(chapterIds) {
            partialArtifactLifecycleCoordinator.reserveMigrationArtifacts(artifacts).use { operation() }
        }

    internal suspend fun <T> withMigrationArtifacts(
        chapterIds: Set<Long>,
        artifacts: List<File>,
        acceptedGenerations: Map<Long, CapturedDownloadGeneration> = emptyMap(),
        operation: suspend () -> T,
    ): T = withDirectoryReservation(chapterIds, {
        check(acceptedGenerations.keys == chapterIds && acceptedGenerations.values.none { it.replaced }) {
            "A later download generation replaced the migration's accepted files"
        }
    }) {
        partialArtifactLifecycleCoordinator.reserveMigrationArtifacts(artifacts).use { operation() }
    }

    internal fun captureMigrationGenerations(chapterIds: Set<Long>): Map<Long, CapturedDownloadGeneration> =
        synchronized(queueStateLock) {
            capturedGenerations.entries.removeAll { (_, handles) ->
                handles.removeAll { it.get() == null }
                handles.isEmpty()
            }
            chapterIds.associateWith { id ->
                val generation = queueGenerations[id] ?: Long.MIN_VALUE
                val handles = capturedGenerations.getOrPut(id) { mutableListOf() }
                handles.firstNotNullOfOrNull { it.get()?.takeIf { value -> value.generation == generation } }
                    ?: CapturedDownloadGeneration(generation).also { handles.add(java.lang.ref.WeakReference(it)) }
            }
        }

    /** Add a chapter to the download queue (no-op if already queued or downloaded). */
    fun enqueue(item: DownloadItem): Boolean {
        val hasRetiringAttempt = synchronized(queueStateLock) {
            if (_queue.value.any { it.chapterId == item.chapterId } ||
                !enqueuePreflights.add(item.chapterId)
            ) {
                return false
            }
            retirementsByChapter.containsKey(item.chapterId)
        }
        try {
            // Probe outside the state lock; the reservation prevents duplicate path cleanup.
            if (!hasRetiringAttempt) {
                emitIo(DownloadIoOperation.ENQUEUE_DOWNLOADED_PROBE)
                if (enqueueFileOperations.isChapterDownloaded(provider, item)) return false
                emitIo(DownloadIoOperation.ENQUEUE_TMP_CLEANUP)
                enqueueFileOperations.cleanupTemporaryDirectory(provider, item)
            }
            return synchronized(queueStateLock) {
                if (_queue.value.any { it.chapterId == item.chapterId }) return@synchronized false
                val previous = _queue.value
                val next = previous + item
                try {
                    persistQueue(next)
                } catch (failure: Exception) {
                    try {
                        persistQueue(previous)
                    } catch (
                        restoreFailure: Exception,
                    ) {
                        failure.addSuppressed(restoreFailure)
                    }
                    throw failure
                }
                val generation = nextGeneration()
                markCapturedGenerationReplaced(item.chapterId)
                queueGenerations[item.chapterId] = generation
                currentGenerations[item.chapterId] = generation
                currentStatuses[item.chapterId] =
                    DownloadQueueStatus.valueOf(item.status.name.replace("DONE", "COMPLETED"))
                _queue.value = next
                true
            }
        } finally {
            synchronized(queueStateLock) { enqueuePreflights.remove(item.chapterId) }
        }
    }

    override fun enqueue(entry: DownloadQueueEntry) {
        enqueue(entry.toItem())
    }

    /** Remove a queued item by chapter ID and clean up its _tmp directory. */
    override fun cancel(chapterId: Long): Boolean = cancelForRetirement(chapterId) != null

    /** Removes a queued item and waits until its producer and artifact leases are retired. */
    suspend fun cancelAndAwaitRetirement(chapterId: Long): Boolean =
        cancelAndAwaitRetirements(listOf(chapterId))

    /** Executes only the fixed artifacts and original producer generations of this confirmation. */
    suspend fun deleteCapturedDownloadFiles(files: CapturedDownloadFiles): CapturedDownloadDeletionResult {
        val existed = files.pendingArtifacts.filterTo(mutableSetOf()) { it.exists() }
        val refused = cancelCapturedDownloadAttempts(files.pendingAttempts)
        val retired = files.pendingAttempts.filter { it.item.chapterId !in refused }
        val retiredPaths = retired.flatMap { files.queuedArtifacts[it.item.chapterId].orEmpty() }.toSet()
        // The manager already cleaned these original attempts. Do not delete their
        // aliases again after a replacement producer is allowed to start.
        val completedPaths = files.pendingArtifacts.intersect(retiredPaths)
        files.pendingAttempts.removeAll(retired.toSet())
        files.pendingArtifacts.removeAll(completedPaths)
        val unsafe = refused.flatMap { files.queuedArtifacts[it].orEmpty() }.toSet()
        val protectedPaths = files.pendingArtifacts.intersect(unsafe)
        val remaining = provider.deleteCapturedDownloadArtifacts(files.pendingArtifacts - unsafe) + protectedPaths
        val succeeded = (completedPaths + (files.pendingArtifacts - remaining.toSet())).count { it in existed }
        val skipped = (completedPaths + (files.pendingArtifacts - remaining.toSet())).count { it !in existed }
        return CapturedDownloadDeletionResult(remaining, succeeded, skipped, refused)
    }

    fun captureDownloadAttempts(chapterIds: Collection<Long>): List<CapturedDownloadAttempt> = synchronized(
        queueStateLock,
    ) {
        capturedGenerations.entries.removeAll { (_, handles) ->
            handles.removeAll { it.get() == null }
            handles.isEmpty()
        }
        chapterIds.distinct().mapNotNull { id ->
            val item = _queue.value.firstOrNull { it.chapterId == id }
            val generation = queueGenerations[id]
            if (item != null && generation != null) {
                val handles = capturedGenerations.getOrPut(id) { mutableListOf() }
                val marker =
                    handles.firstNotNullOfOrNull { it.get()?.takeIf { value -> value.generation == generation } }
                        ?: CapturedDownloadGeneration(generation).also { handles.add(java.lang.ref.WeakReference(it)) }
                CapturedDownloadAttempt(item, generation, marker)
            } else {
                null
            }
        }
    }

    suspend fun cancelCapturedDownloadAttempts(targets: List<CapturedDownloadAttempt>): Set<Long> {
        // Start every original retirement before awaiting an active producer or reader lease.
        val completions = targets.map { target ->
            target to synchronized(queueStateLock) {
                if (target.capturedGeneration.replaced) {
                    null
                } else {
                    target.awaitCompletion ?: run {
                        val key = DownloadAttemptKey(target.item.chapterId, target.generation)
                        val current = queueGenerations[key.chapterId]
                        val original = retirementsByChapter[key.chapterId]?.takeIf { it.key == key }
                        if (current != null && current != key.generation) {
                            null // The opened operation cannot cancel a replacement generation.
                        } else {
                            val retirement = original ?: if (current == key.generation) {
                                cancelForRetirement(key.chapterId)
                            } else {
                                // A normally completed attempt has no queued generation. Its recorded
                                // producer and the existing lease coordinator still provide the barrier.
                                ChapterRetirement(
                                    key,
                                    target.item,
                                    retirementArtifacts(target.item, target.item.downloadIdentity),
                                    activeProducers[key] ?: completedProducerSignal(),
                                ).also {
                                    retirementsByChapter[key.chapterId] = it
                                    launchRetirementCleanup(it)
                                }
                            }
                            retirement?.let { retained ->
                                val await = capturedRetirementCompletion(target, retained)
                                target.awaitCompletion = await
                                await
                            }
                        }
                    }
                }
            }
        }
        return completions.mapNotNullTo(mutableSetOf()) { (target, completion) ->
            if (completion?.invoke() == true) null else target.item.chapterId
        }
    }

    private fun capturedRetirementCompletion(
        target: CapturedDownloadAttempt,
        retained: ChapterRetirement,
    ): suspend () -> Boolean = {
        val initial = retained.completion.await()
        if (initial.success) {
            true
        } else {
            val safe = synchronized(queueStateLock) {
                val replacement = queueGenerations[target.item.chapterId]
                !target.capturedGeneration.replaced &&
                    (replacement == null || replacement == target.generation) &&
                    enqueuePreflights.add(target.item.chapterId)
            }
            if (!safe) {
                false
            } else {
                try {
                    val retried = cleanupRetirementArtifacts(retained)
                    if (retried.success) {
                        synchronized(queueStateLock) {
                            if (retirementsByChapter[target.item.chapterId] === retained) {
                                retirementsByChapter.remove(target.item.chapterId)
                            }
                        }
                    }
                    retried.success
                } finally {
                    synchronized(queueStateLock) { enqueuePreflights.remove(target.item.chapterId) }
                }
            }
        }
    }

    /** Starts every requested retirement before waiting, so later targets cannot escape while an earlier one drains. */
    suspend fun cancelAndAwaitRetirements(chapterIds: Collection<Long>): Boolean {
        var startedSuccessfully = true
        val retirements = chapterIds.distinct().mapNotNull { chapterId ->
            val queued = synchronized(queueStateLock) { _queue.value.any { it.chapterId == chapterId } }
            if (!queued) {
                return@mapNotNull synchronized(queueStateLock) { retirementsByChapter[chapterId] }
            }
            cancelForRetirement(chapterId) ?: synchronized(queueStateLock) {
                if (_queue.value.any { it.chapterId == chapterId }) startedSuccessfully = false
                retirementsByChapter[chapterId]
            }
        }.distinct()
        val cleanupSucceeded = retirements.map { it.completion.await().success }.all { it }
        return startedSuccessfully && cleanupSucceeded
    }

    private fun cancelForRetirement(chapterId: Long): ChapterRetirement? {
        val result = synchronized(queueStateLock) {
            val item = _queue.value.find { it.chapterId == chapterId }
            val generation = queueGenerations[chapterId]
            if (
                item == null ||
                generation == null ||
                stateMachine.transition(item.toEntry(0), DownloadQueueStatus.CANCELLED) == null
            ) {
                null
            } else {
                val nextQueue = _queue.value.filterNot { it.chapterId == chapterId }
                try {
                    persistQueue(nextQueue)
                } catch (_: Exception) {
                    return@synchronized null
                }
                val key = DownloadAttemptKey(chapterId, generation)
                val identity = resolvedDownloadIdentities.remove(key) ?: item.downloadIdentity
                val indexedDirectory = committedIndexes[key]?.directory
                val existingRetirement = retirementsByChapter[chapterId]
                val retirement = if (existingRetirement?.completion?.isCompleted == false) {
                    existingRetirement
                } else {
                    ChapterRetirement(
                        key = key,
                        item = item,
                        artifacts = (
                            existingRetirement?.artifacts.orEmpty() +
                                retirementArtifacts(item, identity, indexedDirectory)
                            )
                            .distinctBy(File::getAbsolutePath),
                        producerDone = activeProducers[key] ?: completedProducerSignal(),
                    ).also { retirementsByChapter[chapterId] = it }
                }
                _queue.value = nextQueue
                queueGenerations.remove(chapterId)
                currentGenerations.remove(chapterId, generation)
                currentStatuses.remove(chapterId)
                committedIndexes.remove(key)
                reconcileCompletions.remove(key)
                _failures.update { it - chapterId }
                CancelResult(
                    retirement = retirement,
                    startCleanup = retirement !== existingRetirement,
                )
            }
        } ?: return null

        partialArtifactLifecycleCoordinator.retireAttempt(
            result.retirement.key.chapterId,
            result.retirement.key.generation,
        )
        // Cancellation commits queue state promptly. Disk cleanup is serialized behind the old
        // producer and never runs on the caller/UI thread.
        if (result.startCleanup) launchRetirementCleanup(result.retirement)
        return result.retirement
    }

    /** Cancel and clear the entire queue, cleaning up all _tmp directories. */
    fun cancelAll() {
        _queue.value.map { it.chapterId }.forEach(::cancel)
    }

    /** Remove all items with ERROR status and clean up their _tmp directories. */
    fun clearErrors() {
        _queue.value.filter { it.status == DownloadStatus.ERROR }.map { it.chapterId }.forEach(::cancel)
    }

    /** Reset all ERROR items back to QUEUED so they will be retried. */
    fun retryErrors() {
        _queue.value.filter { it.status == DownloadStatus.ERROR }.forEach { retry(it.chapterId) }
    }

    /** Reset a single ERROR item back to QUEUED. */
    fun retryItem(chapterId: Long): Boolean = retry(chapterId)

    override fun retry(chapterId: Long): Boolean = transition(chapterId, DownloadQueueStatus.QUEUED)

    override fun transition(chapterId: Long, target: DownloadQueueStatus): Boolean {
        var retiredGeneration: Long? = null
        val changed = synchronized(queueStateLock) {
            val previousGeneration = queueGenerations[chapterId]
            transitionLocked(chapterId, target, expectedGeneration = null).also { transitioned ->
                if (transitioned && target == DownloadQueueStatus.QUEUED) retiredGeneration = previousGeneration
            }
        }
        retiredGeneration?.let { generation ->
            partialArtifactLifecycleCoordinator.retireAttempt(chapterId, generation)
            partialArtifactLifecycleCoordinator.removeAttempt(chapterId, generation)
        }
        return changed
    }

    private fun transitionLocked(
        chapterId: Long,
        target: DownloadQueueStatus,
        expectedGeneration: Long?,
    ): Boolean {
        val currentGeneration = queueGenerations[chapterId] ?: return false
        if (expectedGeneration != null && currentGeneration != expectedGeneration) return false
        var changed = false
        val nextQueue = _queue.value.map { item ->
            if (item.chapterId != chapterId) {
                item
            } else {
                stateMachine.transition(item.toEntry(0), target)?.toItem()
                    ?.let { transitioned ->
                        if (target ==
                            DownloadQueueStatus.QUEUED
                        ) {
                            transitioned.copy(failure = null, retryCount = 0)
                        } else {
                            transitioned
                        }
                    }
                    ?.also { changed = true } ?: item
            }
        }
        if (changed) {
            try {
                persistQueue(nextQueue)
            } catch (error: Exception) {
                // A persistent storage outage must not leave an already-finished producer stuck
                // as DOWNLOADING for the rest of the process. ERROR remains retryable and recovery
                // normalizes any older persisted DOWNLOADING row after restart.
                if (target != DownloadQueueStatus.ERROR) throw error
            }
            _queue.value = nextQueue
            currentStatuses[chapterId] = target
            if (target == DownloadQueueStatus.QUEUED) {
                val oldKey = DownloadAttemptKey(chapterId, currentGeneration)
                resolvedDownloadIdentities.remove(oldKey)
                committedIndexes.remove(oldKey)
                reconcileCompletions.remove(oldKey)
                val nextGeneration = nextGeneration()
                markCapturedGenerationReplaced(chapterId)
                queueGenerations[chapterId] = nextGeneration
                currentGenerations[chapterId] = nextGeneration
                _failures.update { it - chapterId }
            }
        }
        return changed
    }

    override fun recover(): List<DownloadQueueEntry> = synchronized(queueStateLock) {
        stateMachine.recover(
            _queue.value.mapIndexed { index, item -> item.toEntry(index.toLong()) },
        )
    }

    /**
     * Move a queue item from [from] index to [to] index.
     * DOWNLOADING items are included in the index space so drag handles
     * feel contiguous. Does nothing if indices are equal or out of bounds.
     */
    fun reorderItem(from: Int, to: Int) = synchronized(queueStateLock) {
        if (from == to) return@synchronized
        _queue.value = _queue.value.let { items ->
            if (from !in items.indices || to !in items.indices) return@let items
            val sourceId = items[from].sourceId
            if (items[to].sourceId != sourceId) return@let items
            val sourceIndices = items.indices.filter { items[it].sourceId == sourceId }
            val fromInSource = sourceIndices.indexOf(from)
            val toInSource = sourceIndices.indexOf(to)
            if (fromInSource < 0 || toInSource < 0) return@let items
            val reorderedSource = sourceIndices.map(items::get).toMutableList().apply {
                add(toInSource, removeAt(fromInSource))
            }
            val mutable = items.toMutableList()
            sourceIndices.forEachIndexed { index, queueIndex -> mutable[queueIndex] = reorderedSource[index] }
            mutable
        }
        persistQueue()
    }

    /** Sort each source group by a key selector while preserving source group order. */
    fun <R : Comparable<R>> sortQueue(selector: (DownloadItem) -> R) = synchronized(queueStateLock) {
        _queue.value = _queue.value.let { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.sortedBy(selector)
            }
        }
        persistQueue()
    }

    /** Sort each source group by a comparator while preserving source group order. */
    fun sortQueue(comparator: Comparator<DownloadItem>) = synchronized(queueStateLock) {
        _queue.value = _queue.value.let { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.sortedWith(comparator)
            }
        }
        persistQueue()
    }

    /** Reverse items inside each source group while preserving source group order. */
    fun reverseQueue() = synchronized(queueStateLock) {
        _queue.value = _queue.value.let { items ->
            items.groupBy(DownloadItem::sourceId).values.flatMap { sourceItems ->
                val (downloading, pending) = sourceItems.partition { it.status == DownloadStatus.DOWNLOADING }
                downloading + pending.reversed()
            }
        }
        persistQueue()
    }

    /** Prioritize pending work within its source without replacing an active producer. */
    fun startDownloadNow(chapterId: Long): Boolean = synchronized(queueStateLock) {
        val items = _queue.value
        val target = items.firstOrNull { it.chapterId == chapterId } ?: return@synchronized false
        if (target.status != DownloadStatus.QUEUED) return@synchronized false
        val indices = items.indices.filter {
            items[it].sourceId == target.sourceId &&
                items[it].status != DownloadStatus.DOWNLOADING
        }
        val pending = indices.map(items::get).filterNot { it.chapterId == chapterId }
        val prioritized = listOf(target) + pending
        val next = items.toMutableList()
        indices.forEachIndexed { index, queueIndex -> next[queueIndex] = prioritized[index] }
        try {
            persistQueue(next)
        } catch (failure: Exception) {
            try {
                persistQueue(items)
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
        _queue.value = next
        resumeAll()
        true
    }

    /** Pause the download worker (no new downloads will start). */
    fun pauseAll() {
        _isPaused.value = true
    }

    /** Resume the download worker. */
    fun resumeAll() {
        _isPaused.value = false
    }

    /** Delete the on-disk files for a downloaded chapter. */
    fun deleteDownload(sourceId: Long, mangaTitle: String, chapterName: String) {
        provider.deleteChapterDownload(sourceId, mangaTitle, chapterName)
    }

    fun deleteDownload(sourceId: Long, identity: DownloadChapterIdentity) {
        provider.deleteChapterDownload(sourceId, identity)
    }

    val availabilityRevision get() = provider.availabilityRevision

    /** Delegates to [DesktopDownloadProvider]. */
    override fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean =
        provider.isChapterDownloaded(sourceId, mangaTitle, chapterName)

    override fun isDownloaded(sourceId: Long, identity: DownloadChapterIdentity): Boolean =
        provider.isChapterDownloaded(sourceId, identity)

    /**
     * Start the background worker. Call once at app startup.
     *
     * Watches the queue StateFlow. Whenever the queue transitions from
     * "no QUEUED items" → "has QUEUED items", triggers [drainQueue].
     *
     * Returns the [Job] so callers (e.g. tests) can cancel it when done.
     * In production the job runs for the lifetime of the process.
     */
    fun start(): Job = synchronized(lifecycleLock) {
        workerJob?.let { return@synchronized it }
        if (stopped) return@synchronized Job().apply { cancel() }
        workerScope.launch(start = CoroutineStart.LAZY) {
            _queue
                .map { items -> items.any { it.status == DownloadStatus.QUEUED } }
                .distinctUntilChanged()
                .filter { hasQueued -> hasQueued }
                .collect { drainQueue() }
        }.also { job ->
            workerJob = job
            job.invokeOnCompletion { synchronized(lifecycleLock) { if (workerJob === job) workerJob = null } }
            job.start()
        }
    }

    /** Stops the worker and active downloads without waiting for cancellation cleanup. */
    fun stop() {
        val jobs = synchronized(lifecycleLock) {
            stopped = true
            (listOfNotNull(workerJob, partialIndexRecoveryJob) + activeJobs).distinct()
        }
        jobs.forEach { it.cancel() }
    }

    /** Stops the worker and every active download, waiting until no old task can mutate the queue. */
    suspend fun stopAndJoin() {
        val jobs = snapshotJobsForStop()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    private fun snapshotJobsForStop(): List<Job> = synchronized(lifecycleLock) {
        stopped = true
        (listOfNotNull(workerJob, partialIndexRecoveryJob) + activeJobs).distinct().also {
            workerJob = null
            activeJobs.clear()
        }
    }

    /** Process every QUEUED item until none remain, respecting parallel download limit. */
    private suspend fun drainQueue() {
        val limit = (downloadPreferences ?: runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull())
            ?.parallelDownloadLimit?.get()?.coerceIn(1, 5) ?: 1
        while (true) {
            if (_isPaused.value) break
            val batch = synchronized(queueStateLock) {
                stateMachine.schedule(
                    _queue.value.mapIndexed { index, queued -> queued.toEntry(index.toLong()) },
                    limit = limit,
                ).mapNotNull { entry ->
                    queueGenerations[entry.chapterId]?.let { generation ->
                        DownloadAttempt(entry.toItem(), generation)
                    }
                }
            }
            if (batch.isEmpty()) break
            val jobs = batch.mapNotNull { attempt ->
                val job = workerScope.launch(start = CoroutineStart.LAZY) {
                    val success = downloadChapter(attempt)
                    if (!success && !isStopped()) {
                        setStatus(attempt, DownloadStatus.ERROR)
                    }
                }
                synchronized(lifecycleLock) {
                    if (stopped || _isPaused.value || !startAttempt(attempt)) {
                        job.cancel()
                        null
                    } else {
                        activeJobs += job
                        job.invokeOnCompletion {
                            finishProducer(attempt)
                            synchronized(lifecycleLock) { activeJobs -= job }
                        }
                        job.start()
                        job
                    }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /**
     * Downloads a chapter using the _tmp directory pattern (mirrors Android Downloader):
     * 1. Create/reuse _tmp directory
     * 2. Download each page as {page}.tmp, then rename to {page}.{ext}
     * 3. On success, rename _tmp dir to final dir
     * 4. On failure, _tmp remains for retry/restart reconciliation; explicit cancellation cleans it
     */
    private suspend fun downloadChapter(attempt: DownloadAttempt): Boolean {
        val item = attempt.item
        return try {
            if (!awaitRetirement(attempt)) return false
            val downloadIdentity = item.downloadIdentity ?: downloadIdentityResolver(item)
            if (downloadIdentity != null && !registerIdentity(attempt, downloadIdentity)) return false
            if (!isCurrentAttempt(attempt)) return false
            if (downloadIdentity != null && provider.isChapterDownloaded(item.sourceId, downloadIdentity)) {
                return completeAttempt(attempt)
            }
            val client = httpClient
                ?: networkHelper?.clientForSource(item.sourceId)
                ?: Injekt.get<NetworkHelper>().clientForSource(item.sourceId)
            val resolvedPages = resolvePageTable(attempt, item) ?: return false
            val pageTable = resolvedPages.pageTable
            if (pageTable.entries.isEmpty()) {
                return fail(
                    attempt,
                    AppError.MalformedData(IllegalStateException("Source returned no downloadable pages")),
                )
            }
            var sourceForMissingImageUrls = resolvedPages.source
            val compatibilityUrls = pageTable.entries
                .sortedBy(PartialPageTableEntry::readerOrdinal)
                .map { entry -> entry.imageUrl?.takeIf(String::isNotBlank) ?: entry.pageUrl }
            if (
                !updateAttempt(attempt) {
                    it.copy(
                        pageUrls = compatibilityUrls,
                        pageTable = pageTable,
                        downloadIdentity = downloadIdentity ?: it.downloadIdentity,
                    )
                }
            ) {
                return false
            }

            // Use _tmp directory for in-progress download (Android pattern)
            val tmpDir = committedIndexes[attempt.key]?.directory
                ?: downloadIdentity?.let { identity ->
                    firstExistingPartialDirectory(item, identity)
                        ?: provider.canonicalChapterTmpDir(identity)
                }
                ?: provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
            if (!isCurrentAttempt(attempt)) return false
            tmpDir.mkdirs()
            reconcilePartialIndex(attempt, pageTable, downloadIdentity, tmpDir)
            if (!isCurrentAttempt(attempt)) return false

            pageTable.entries.sortedBy(PartialPageTableEntry::readerOrdinal).forEach { pageEntry ->
                if (!isCurrentAttempt(attempt)) return false
                val alreadyDownloaded = committedIndexes[attempt.key]?.pages?.contains(pageEntry.readerOrdinal) == true
                if (!alreadyDownloaded) {
                    if (!cleanupRejectedFilesForOrdinal(attempt, pageEntry.readerOrdinal)) return false
                    val imageUrl = pageEntry.imageUrl?.takeIf(String::isNotBlank) ?: run {
                        val source = sourceForMissingImageUrls
                            ?: sourceResolver(item.sourceId)?.also { sourceForMissingImageUrls = it }
                            ?: return fail(
                                attempt,
                                AppError.MalformedData(
                                    IllegalStateException("Source ${item.sourceId} cannot resolve missing image URLs"),
                                ),
                            )
                        resolveImageUrl(attempt, source, pageEntry) ?: return false
                    }
                    if (!downloadAndPublishPage(
                            attempt,
                            client,
                            tmpDir,
                            pageTable,
                            downloadIdentity,
                            pageEntry,
                            imageUrl,
                        )
                    ) {
                        return false
                    }
                }

                if (!updateAttempt(attempt) {
                        it.copy(progress = pageEntry.readerOrdinal + 1, retryCount = 0)
                    }
                ) {
                    return false
                }
                clearFailure(attempt)
            }

            if (!cleanupAllRejectedFiles(attempt)) return false

            // All pages are committed. Directory and CBZ modes publish through separate atomic paths.
            val finalDir = downloadIdentity?.let(provider::canonicalChapterDownloadDir)
                ?: provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
            val prefs = downloadPreferences ?: runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull()
            if (!isCurrentAttempt(attempt)) return false
            finalDir.parentFile?.mkdirs()
            if (prefs?.downloadAsCbz?.get() == true) {
                finalizeCbzChapter(attempt, tmpDir, finalDir)
            } else {
                finalizeDirectoryChapter(attempt, tmpDir, finalDir)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordFailure(attempt, error.toAppError())
            // _tmp directory remains on disk but won't be counted as "downloaded"
            false
        }
    }

    private suspend fun finalizeDirectoryChapter(
        attempt: DownloadAttempt,
        tmpDir: File,
        finalDir: File,
    ): Boolean {
        val publishToken = try {
            partialArtifactLifecycleCoordinator.prepareDirectoryPublish(
                chapterId = attempt.key.chapterId,
                attemptGeneration = attempt.key.generation,
                stagingDirectory = tmpDir,
                finalDirectory = finalDir,
            )
        } catch (error: Exception) {
            recordFailure(attempt, AppError.Storage(error))
            return false
        }
        var outcome: ChapterDirectoryPublishOutcome? = null
        var tokenCommitted = false
        var publishAttempt = 0
        var publishError: Throwable? = null
        try {
            while (outcome == null) {
                if (!isCurrentAttempt(attempt)) return false
                try {
                    emitIo(DownloadIoOperation.CHAPTER_MOVE)
                    outcome = chapterDirectoryPublisher.publish(tmpDir, finalDir)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    publishError = error
                    if (
                        error is ChapterPublishConflictException ||
                        (
                            error is ChapterAtomicPublishException &&
                                error.cause is java.nio.file.AtomicMoveNotSupportedException
                            )
                    ) {
                        break
                    }
                    val wait = stateMachine.retryDelayMillis(publishAttempt++) ?: break
                    if (!updateRetryCount(attempt, publishAttempt)) return false
                    retryDelay(wait)
                }
            }
            if (outcome == null) {
                recordFailure(attempt, AppError.Storage(publishError))
                return false
            }
            if (!partialArtifactLifecycleCoordinator.commitDirectoryPublish(publishToken)) {
                recordFailure(
                    attempt,
                    AppError.Storage(
                        ChapterAtomicPublishException(tmpDir, finalDir, IOException("Stale publish token")),
                    ),
                )
                return false
            }
            tokenCommitted = true
            if (!isCurrentAttempt(attempt)) return false
            if (outcome == ChapterDirectoryPublishOutcome.ADOPTED_IDENTICAL) {
                if (!cleanupPrivateDirectoryAfterPublish(attempt, tmpDir)) return false
            }
            return isCurrentAttempt(attempt) && completeAttempt(attempt)
        } finally {
            if (!tokenCommitted) partialArtifactLifecycleCoordinator.abortDirectoryPublish(publishToken)
            provider.notifyAvailabilityChanged()
        }
    }

    private suspend fun finalizeCbzChapter(
        attempt: DownloadAttempt,
        tmpDir: File,
        finalDir: File,
    ): Boolean {
        if (finalDir.exists()) {
            recordFailure(attempt, AppError.Storage(ChapterPublishConflictException(tmpDir, finalDir)))
            return false
        }
        val cbzFile = CbzCreator.defaultOutputFile(finalDir)
        return try {
            if (!isCurrentAttempt(attempt)) return false
            val committedPages = committedIndexes[attempt.key]
                ?.pages
                ?.valuesSnapshot()
                ?.sortedBy(CommittedPageRecord::readerOrdinal)
                ?.map(CommittedPageRecord::file)
                .orEmpty()
            if (committedPages.isEmpty()) {
                throw CbzPackagingException("Private chapter has no committed pages")
            }
            emitIo(DownloadIoOperation.CBZ_PACKAGE)
            if (!chapterPackager.packageChapter(tmpDir, cbzFile, committedPages)) {
                throw CbzPackagingException("Private chapter directory contains no packageable pages")
            }
            emitIo(DownloadIoOperation.CBZ_VALIDATE)
            emitIo(DownloadIoOperation.CBZ_PUBLISH)
            if (!isCurrentAttempt(attempt)) return false
            if (
                !partialArtifactLifecycleCoordinator.commitCbzPublish(
                    chapterId = attempt.key.chapterId,
                    attemptGeneration = attempt.key.generation,
                    stagingDirectory = tmpDir,
                    archive = cbzFile,
                )
            ) {
                throw CbzPackagingException("Published CBZ could not be handed off to the current Reader generation")
            }
            if (!cleanupPrivateDirectoryAfterPublish(attempt, tmpDir)) return false
            completeAttempt(attempt)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recordFailure(attempt, error.toAppError())
            false
        } finally {
            provider.notifyAvailabilityChanged()
        }
    }

    private suspend fun cleanupPrivateDirectoryAfterPublish(
        attempt: DownloadAttempt,
        tmpDir: File,
    ): Boolean {
        partialArtifactLifecycleCoordinator.awaitArtifactDrain(tmpDir).await()
        if (!isCurrentAttempt(attempt)) return false
        emitIo(DownloadIoOperation.CHAPTER_CLEANUP)
        val cleanupError = try {
            if (!tmpDir.exists() || (artifactCleaner(tmpDir) && !tmpDir.exists())) {
                null
            } else {
                IOException("Unable to remove published private chapter directory: ${tmpDir.absolutePath}")
            }
        } catch (error: Exception) {
            error
        }
        if (cleanupError != null) {
            runCatching { chapterCleanupDiagnosticObserver(ChapterCleanupDiagnostic(tmpDir, cleanupError)) }
        }
        return true
    }

    private suspend fun resolvePageTable(
        attempt: DownloadAttempt,
        item: DownloadItem,
    ): ResolvedPageTable? {
        val normalizedTable = PartialPageTablePolicy.normalizeLegacyPageUrls(item.pageTable, item.pageUrls)
        val validation = PartialPageTablePolicy.validate(normalizedTable)
        if (
            normalizedTable.entries.isNotEmpty() &&
            validation in setOf(PartialPageTableValidation.COMPLETE, PartialPageTableValidation.LEGACY_UNPROVEN)
        ) {
            return ResolvedPageTable(normalizedTable, null)
        }
        if (item.chapterUrl.isBlank()) {
            fail(attempt, AppError.MalformedData(IllegalArgumentException("Chapter URL is missing")))
            return null
        }
        val source = sourceResolver(item.sourceId)
        if (source == null) {
            fail(attempt, AppError.Unknown(IllegalStateException("Source ${item.sourceId} is unavailable")))
            return null
        }
        val storedChapter = chapterRepository?.getChapterById(item.chapterId)
            ?.takeIf { it.url == item.chapterUrl && (item.mangaId == 0L || it.mangaId == item.mangaId) }
        val chapter = storedChapter?.toSourceChapter() ?: SChapter.create().apply {
            url = item.chapterUrl
            name = item.chapterName
        }
        emitIo(DownloadIoOperation.SOURCE_PAGE_LIST)
        return when (val result = safeSourceCall(timeoutMs = sourceCallTimeoutMs) { source.getPageList(chapter) }) {
            is SourceCallResult.Success -> ResolvedPageTable(
                pageTable = PartialPageTable.complete(
                    result.value.mapIndexed { readerOrdinal, page ->
                        PartialPageTableEntry(
                            readerOrdinal = readerOrdinal,
                            sourcePageIndex = page.index,
                            pageUrl = page.url,
                            imageUrl = page.imageUrl,
                        )
                    },
                ),
                source = source,
            )
            is SourceCallResult.Timeout -> {
                fail(attempt, result.error)
                null
            }
            is SourceCallResult.Error -> {
                fail(attempt, result.error)
                null
            }
        }
    }

    private suspend fun resolveImageUrl(
        attempt: DownloadAttempt,
        source: CatalogueSource,
        entry: PartialPageTableEntry,
    ): String? {
        val page = Page(entry.sourcePageIndex, entry.pageUrl, entry.imageUrl)
        emitIo(DownloadIoOperation.SOURCE_IMAGE_URL)
        return when (
            val result = safeSourceCall(timeoutMs = sourceCallTimeoutMs) {
                resolveSourceImageUrl(source, page)
            }
        ) {
            is SourceCallResult.Success -> result.value?.takeIf(String::isNotBlank) ?: run {
                fail(
                    attempt,
                    AppError.MalformedData(IllegalStateException("Source returned an empty page image URL")),
                )
                null
            }
            is SourceCallResult.Timeout -> {
                fail(attempt, result.error)
                null
            }
            is SourceCallResult.Error -> {
                fail(attempt, result.error)
                null
            }
        }
    }

    private suspend fun downloadAndPublishPage(
        attempt: DownloadAttempt,
        client: OkHttpClient,
        tmpDir: File,
        pageTable: PartialPageTable,
        identity: DownloadChapterIdentity?,
        entry: PartialPageTableEntry,
        imageUrl: String,
    ): Boolean {
        val stagingFile = File(
            tmpDir,
            DownloadPageFileNamingPolicy.stagingFileName(entry.readerOrdinal, attempt.generation),
        )
        val finalFile = File(
            tmpDir,
            DownloadPageFileNamingPolicy.committedFileName(entry.readerOrdinal, extensionFromUrl(imageUrl)),
        )
        var retryAttempt = 0
        var lastError: Throwable? = null
        while (true) {
            var token: PagePublishToken? = null
            var moved = false
            try {
                emitIo(DownloadIoOperation.NETWORK_REQUEST)
                val bytes = fileOperations.execute(client, imageUrl).use { response ->
                    emitIo(DownloadIoOperation.BODY_READ)
                    fileOperations.readBody(response)
                }
                if (!isCurrentAttempt(attempt)) {
                    cleanupStaging(stagingFile)
                    return false
                }
                emitIo(DownloadIoOperation.PAGE_WRITE)
                fileOperations.writePage(stagingFile, bytes)
                if (!isCurrentAttempt(attempt)) {
                    cleanupStaging(stagingFile)
                    return false
                }
                token = reservePagePublishToken(attempt, entry.readerOrdinal, stagingFile, finalFile)
                if (token == null) {
                    cleanupStaging(stagingFile)
                    return false
                }
                emitIo(DownloadIoOperation.PAGE_MOVE)
                fileOperations.renamePage(stagingFile, finalFile)
                moved = true
                if (!isCurrentAttempt(attempt)) {
                    releasePagePublishToken(token, deleteFinal = true)
                    return false
                }
                emitIo(DownloadIoOperation.PAGE_HEADER_PROBE)
                if (!partialIndexFileOperations.isValidCommittedPage(provider, finalFile)) {
                    throw java.io.IOException("Downloaded page failed validation")
                }
                if (!publishCommittedPage(token, pageTable, identity, entry)) {
                    releasePagePublishToken(token, deleteFinal = true)
                    return false
                }
                return true
            } catch (error: Exception) {
                if (error is CancellationException) {
                    token?.let { releasePagePublishToken(it, deleteFinal = moved) } ?: cleanupStaging(stagingFile)
                    throw error
                }
                lastError = error
                token?.let { releasePagePublishToken(it, deleteFinal = moved) } ?: cleanupStaging(stagingFile)
            }
            if (!isCurrentAttempt(attempt)) return false
            val wait = stateMachine.retryDelayMillis(retryAttempt++) ?: break
            if (!updateRetryCount(attempt, retryAttempt)) return false
            retryDelay(wait)
        }
        recordFailure(attempt, lastError.toAppError())
        cleanupStaging(stagingFile)
        return false
    }

    private fun reservePagePublishToken(
        attempt: DownloadAttempt,
        readerOrdinal: Int,
        stagingFile: File,
        finalFile: File,
    ): PagePublishToken? = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) return@synchronized null
        val token = PagePublishToken(attempt.key, readerOrdinal, stagingFile, finalFile)
        token.takeIf { pagePublishTokens.putIfAbsent(attempt.key to readerOrdinal, token) == null }
    }

    private fun publishCommittedPage(
        token: PagePublishToken,
        pageTable: PartialPageTable,
        identity: DownloadChapterIdentity?,
        entry: PartialPageTableEntry,
    ): Boolean {
        val record = CommittedPageRecord(
            readerOrdinal = entry.readerOrdinal,
            sourcePageIndex = entry.sourcePageIndex,
            file = token.finalFile,
            committedRevision = committedRevisionSequence.incrementAndGet(),
        )
        val candidate = record.toCandidate(token.key.generation)
        if (!partialArtifactLifecycleCoordinator.registerCommittedPage(token.key.chapterId, candidate)) return false
        val published = synchronized(queueStateLock) {
            val tokenKey = token.key to token.readerOrdinal
            if (
                queueGenerations[token.key.chapterId] != token.key.generation ||
                pagePublishTokens[tokenKey] !== token
            ) {
                return@synchronized false
            }
            val index = committedIndexes[token.key] ?: CommittedChapterIndex(
                key = token.key,
                identity = identity,
                pageTable = pageTable,
                directory = token.finalFile.parentFile,
                pages = MutableCommittedPageIndex(),
            ).also { committedIndexes[token.key] = it }
            index.pages.put(entry.readerOrdinal, record)
            index.rejectedFilesByOrdinal.remove(entry.readerOrdinal)
            pagePublishTokens.remove(tokenKey, token)
            true
        }
        if (!published) {
            partialArtifactLifecycleCoordinator.unregisterCommittedPage(token.key.chapterId, candidate)
        }
        return published
    }

    private fun releasePagePublishToken(token: PagePublishToken, deleteFinal: Boolean) {
        val stillOwned = pagePublishTokens.remove(token.key to token.readerOrdinal, token)
        cleanupStaging(token.stagingFile)
        if (deleteFinal && stillOwned) {
            emitIo(DownloadIoOperation.STAGING_CLEANUP)
            token.finalFile.delete()
        }
    }

    private fun cleanupStaging(file: File) {
        emitIo(DownloadIoOperation.STAGING_CLEANUP)
        file.delete()
    }

    private suspend fun reconcilePartialIndex(
        attempt: DownloadAttempt,
        pageTable: PartialPageTable,
        identity: DownloadChapterIdentity?,
        directory: File,
    ) {
        val completion = CompletableDeferred<Unit>()
        val existing = reconcileCompletions.putIfAbsent(attempt.key, completion)
        if (existing != null) {
            existing.await()
            if (reconcileCompletions[attempt.key] === existing) return
            return reconcilePartialIndex(attempt, pageTable, identity, directory)
        }
        try {
            val validation = PartialPageTablePolicy.validate(pageTable)
            if (
                validation !in setOf(
                    PartialPageTableValidation.COMPLETE,
                    PartialPageTableValidation.PARTIAL,
                    PartialPageTableValidation.LEGACY_UNPROVEN,
                )
            ) {
                return
            }
            if (!isCurrentAttempt(attempt)) return
            emitIo(DownloadIoOperation.INDEX_DIRECTORY_LIST)
            val files = partialIndexFileOperations.listFiles(directory)
            val tableEntries = pageTable.entries.associateBy(PartialPageTableEntry::readerOrdinal)
            val rejectedByOrdinal = mutableMapOf<Int, MutableList<File>>()
            val rejectedUnmapped = mutableListOf<File>()
            val candidates = mutableMapOf<Int, MutableList<File>>()
            files.forEach { file ->
                if (file.extension.equals("tmp", ignoreCase = true)) {
                    val readerOrdinal = DownloadPageFileNamingPolicy.stagingReaderOrdinal(file.name)
                    if (readerOrdinal != null && tableEntries.containsKey(readerOrdinal)) {
                        rejectedByOrdinal.getOrPut(readerOrdinal, ::mutableListOf).add(file)
                    } else {
                        rejectedUnmapped += file
                    }
                    return@forEach
                }
                val readerOrdinal = DownloadPageFileNamingPolicy.readerOrdinal(file.name)
                if (readerOrdinal == null) {
                    rejectedUnmapped += file
                    return@forEach
                }
                if (!tableEntries.containsKey(readerOrdinal)) {
                    rejectedUnmapped += file
                } else {
                    candidates.getOrPut(readerOrdinal, ::mutableListOf).add(file)
                }
            }
            val reconciledPages = MutableCommittedPageIndex<CommittedPageRecord>()
            candidates.entries.sortedBy(
                Map.Entry<Int, MutableList<File>>::key,
            ).forEach { (readerOrdinal, candidateFiles) ->
                if (candidateFiles.size != 1) {
                    rejectedByOrdinal.getOrPut(readerOrdinal, ::mutableListOf).addAll(candidateFiles)
                    return@forEach
                }
                val file = candidateFiles.single()
                emitIo(DownloadIoOperation.PAGE_HEADER_PROBE)
                if (!partialIndexFileOperations.isValidCommittedPage(provider, file)) {
                    rejectedByOrdinal.getOrPut(readerOrdinal, ::mutableListOf).add(file)
                    return@forEach
                }
                val entry = tableEntries.getValue(readerOrdinal)
                reconciledPages.put(
                    readerOrdinal,
                    CommittedPageRecord(
                        readerOrdinal = readerOrdinal,
                        sourcePageIndex = entry.sourcePageIndex,
                        file = file,
                        committedRevision = committedRevisionSequence.incrementAndGet(),
                    ),
                )
            }
            val reconciledRecords = reconciledPages.valuesSnapshot()
            val registeredCandidates = mutableListOf<PartialReaderPageCandidate>()
            for (record in reconciledRecords) {
                val candidate = record.toCandidate(attempt.generation)
                if (!partialArtifactLifecycleCoordinator.registerCommittedPage(attempt.item.chapterId, candidate)) {
                    registeredCandidates.forEach { registered ->
                        partialArtifactLifecycleCoordinator.unregisterCommittedPage(attempt.item.chapterId, registered)
                    }
                    return
                }
                registeredCandidates += candidate
            }
            val published = synchronized(queueStateLock) {
                if (isCurrentAttemptLocked(attempt)) {
                    committedIndexes[attempt.key] = CommittedChapterIndex(
                        key = attempt.key,
                        identity = identity,
                        pageTable = pageTable,
                        directory = directory,
                        pages = reconciledPages,
                        rejectedFilesByOrdinal = ConcurrentHashMap(
                            rejectedByOrdinal.mapValues { (_, rejectedFiles) ->
                                rejectedFiles.distinctBy(File::getAbsolutePath)
                            },
                        ),
                        rejectedUnmappedFiles = rejectedUnmapped.distinctBy(File::getAbsolutePath),
                    )
                    true
                } else {
                    false
                }
            }
            if (!published) {
                registeredCandidates.forEach { candidate ->
                    partialArtifactLifecycleCoordinator.unregisterCommittedPage(attempt.item.chapterId, candidate)
                }
            }
        } catch (error: Exception) {
            reconcileCompletions.remove(attempt.key, completion)
            throw error
        } finally {
            completion.complete(Unit)
        }
    }

    private fun cleanupRejectedFilesForOrdinal(attempt: DownloadAttempt, readerOrdinal: Int): Boolean {
        val index = committedIndexes[attempt.key] ?: return isCurrentAttempt(attempt)
        val rejectedFiles = index.rejectedFilesByOrdinal[readerOrdinal].orEmpty()
        if (!cleanupExactRejectedFiles(attempt, rejectedFiles)) return false
        index.rejectedFilesByOrdinal.remove(readerOrdinal, rejectedFiles)
        return true
    }

    private fun cleanupAllRejectedFiles(attempt: DownloadAttempt): Boolean {
        val index = committedIndexes[attempt.key] ?: return isCurrentAttempt(attempt)
        val rejectedFiles = (
            index.rejectedFilesByOrdinal.values.flatten() + index.rejectedUnmappedFiles
            ).distinctBy(File::getAbsolutePath)
        if (!cleanupExactRejectedFiles(attempt, rejectedFiles)) return false
        index.rejectedFilesByOrdinal.clear()
        return true
    }

    private fun cleanupExactRejectedFiles(attempt: DownloadAttempt, files: List<File>): Boolean {
        files.forEach { file ->
            if (!isCurrentAttempt(attempt)) return false
            try {
                emitIo(DownloadIoOperation.STAGING_CLEANUP)
                if (file.exists() && (!file.delete() || file.exists())) {
                    throw java.io.IOException("Unable to remove rejected partial page: ${file.absolutePath}")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordFailure(attempt, AppError.Storage(error))
                return false
            }
        }
        return isCurrentAttempt(attempt)
    }

    private fun firstExistingPartialDirectory(
        item: DownloadItem,
        identity: DownloadChapterIdentity,
    ): File? = (
        provider.partialTmpDirectoryCandidates(item.sourceId, identity) +
            provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
        )
        .distinctBy { directory -> directory.absoluteFile.normalize().path }
        .firstOrNull { directory ->
            emitIo(DownloadIoOperation.INDEX_DIRECTORY_PROBE)
            partialIndexFileOperations.isDirectory(directory)
        }

    internal suspend fun awaitPartialIndexRecovery() {
        partialIndexRecoveryJob.join()
    }

    override fun snapshot(chapterId: Long, identity: DownloadChapterIdentity): PartialDownloadSnapshot? {
        val generation = currentGenerations[chapterId] ?: return null
        val key = DownloadAttemptKey(chapterId, generation)
        val index = committedIndexes[key]?.takeIf { it.identity == identity } ?: return null
        val status = currentStatuses[chapterId] ?: return null
        if (currentGenerations[chapterId] != generation) return null
        val committedPages = index.pages.valuesSnapshot()
            .sortedBy(CommittedPageRecord::readerOrdinal)
        if (
            committedPages.any { page ->
                !partialArtifactLifecycleCoordinator.markDescriptorIssued(chapterId, page.toCandidate(generation))
            }
        ) {
            return null
        }
        return PartialDownloadSnapshot(
            chapterId = chapterId,
            identity = identity,
            attemptGeneration = generation,
            queueStatus = status,
            pageTable = index.pageTable,
            committedPages = committedPages.map { page ->
                PartialCommittedPage(
                    readerOrdinal = page.readerOrdinal,
                    sourcePageIndex = page.sourcePageIndex,
                    opaqueLocation = page.file.absolutePath,
                    committedRevision = page.committedRevision,
                )
            },
        )
    }

    override fun committedPageCandidate(
        chapterId: Long,
        identity: DownloadChapterIdentity,
        readerOrdinal: Int,
        sourcePageIndex: Int,
    ): PartialReaderPageCandidate? {
        val generation = currentGenerations[chapterId] ?: return null
        val index = committedIndexes[DownloadAttemptKey(chapterId, generation)]
            ?.takeIf { it.identity == identity }
            ?: return null
        if (index.pageTable.completeness != PartialPageTableCompleteness.COMPLETE) return null
        val entry = index.pageEntriesByOrdinal[readerOrdinal]
            ?.takeIf { it.sourcePageIndex == sourcePageIndex }
            ?: return null
        val page = index.pages.get(readerOrdinal)
            ?.takeIf {
                it.sourcePageIndex == entry.sourcePageIndex &&
                    it.committedRevision > 0L
            }
            ?: return null
        if (currentGenerations[chapterId] != generation || currentStatuses[chapterId] == null) return null
        val candidate = PartialReaderPageCandidate(
            attemptGeneration = generation,
            readerOrdinal = readerOrdinal,
            sourcePageIndex = sourcePageIndex,
            opaqueLocation = page.file.absolutePath,
            committedRevision = page.committedRevision,
        )
        return candidate.takeIf { partialArtifactLifecycleCoordinator.markDescriptorIssued(chapterId, it) }
    }

    private fun CommittedPageRecord.toCandidate(attemptGeneration: Long) = PartialReaderPageCandidate(
        attemptGeneration = attemptGeneration,
        readerOrdinal = readerOrdinal,
        sourcePageIndex = sourcePageIndex,
        opaqueLocation = file.absolutePath,
        committedRevision = committedRevision,
    )

    val partialPageReadLeaseSource: PartialPageReadLeaseSource
        get() = partialArtifactLifecycleCoordinator

    private fun emitIo(operation: DownloadIoOperation) {
        if (!ioProbe.enabled) return
        ioProbe.onIo(
            DownloadIoEvent(
                operation = operation,
                locks = DownloadLockState(
                    queueStateLocked = Thread.holdsLock(queueStateLock),
                    indexLocked = false,
                    coordinatorLocked = partialArtifactLifecycleCoordinator.holdsLockByCurrentThread(),
                    lifecycleLocked = Thread.holdsLock(lifecycleLock),
                ),
            ),
        )
    }

    private fun isStopped(): Boolean = synchronized(lifecycleLock) { stopped }

    private fun nextGeneration(): Long = generationSequence.incrementAndGet()

    // Only opened operations are observed. Copies retaining a frozen identity share the
    // marker, and weak references never retain a closed page or removal session.
    private fun markCapturedGenerationReplaced(chapterId: Long) {
        capturedGenerations.remove(chapterId)?.forEach { it.get()?.replaced = true }
    }

    private fun isCurrentAttempt(attempt: DownloadAttempt): Boolean = synchronized(queueStateLock) {
        isCurrentAttemptLocked(attempt)
    }

    private fun isCurrentAttemptLocked(attempt: DownloadAttempt): Boolean =
        queueGenerations[attempt.item.chapterId] == attempt.generation

    private fun launchRetirementCleanup(retirement: ChapterRetirement) {
        val job = workerScope.launch(start = CoroutineStart.LAZY) {
            retirementCleanupAwaitObserver(retirement.item.chapterId)
            retirement.producerDone.await()
            settleRetirement(retirement)
        }
        val shouldStart = synchronized(lifecycleLock) {
            if (stopped) {
                false
            } else {
                activeJobs += job
                job.invokeOnCompletion { synchronized(lifecycleLock) { activeJobs -= job } }
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
            retirement.completion.complete(
                RetirementCleanupResult(
                    success = false,
                    error = CancellationException("Download manager is stopped"),
                ),
            )
        }
    }

    private suspend fun awaitRetirement(attempt: DownloadAttempt): Boolean {
        val retirement = synchronized(queueStateLock) { retirementsByChapter[attempt.item.chapterId] }
            ?: return true
        retirementAwaitObserver(attempt.item.chapterId)
        val initialResult = retirement.completion.await()
        if (!isCurrentAttempt(attempt)) return false
        val result = if (initialResult.success) {
            initialResult
        } else {
            cleanupRetirementArtifacts(retirement)
        }
        if (result.success) {
            synchronized(queueStateLock) {
                if (retirementsByChapter[attempt.item.chapterId] === retirement) {
                    retirementsByChapter.remove(attempt.item.chapterId)
                }
            }
            return isCurrentAttempt(attempt)
        }
        recordFailure(attempt, AppError.Storage(result.error))
        return false
    }

    private suspend fun settleRetirement(retirement: ChapterRetirement) {
        val result = cleanupRetirementArtifacts(retirement)
        if (result.success) {
            synchronized(queueStateLock) {
                if (retirementsByChapter[retirement.item.chapterId] === retirement) {
                    retirementsByChapter.remove(retirement.item.chapterId)
                }
            }
        }
        retirement.completion.complete(result)
        retirementCleanupFinishedObserver(retirement.item.chapterId, result.success)
    }

    private fun retirementArtifacts(
        item: DownloadItem,
        identity: DownloadChapterIdentity?,
        indexedDirectory: File? = null,
    ): List<File> {
        val legacyFinal = provider.chapterDownloadDir(item.sourceId, item.mangaTitle, item.chapterName)
        return buildList {
            indexedDirectory?.let(::add)
            add(provider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName))
            add(legacyFinal)
            add(CbzCreator.defaultOutputFile(legacyFinal))
            identity?.let {
                val canonicalFinal = provider.canonicalChapterDownloadDir(identity)
                addAll(provider.partialTmpDirectoryCandidates(item.sourceId, identity))
                add(canonicalFinal)
                add(CbzCreator.defaultOutputFile(canonicalFinal))
            }
        }.distinctBy(File::getAbsolutePath)
    }

    private suspend fun cleanupRetirementArtifacts(retirement: ChapterRetirement): RetirementCleanupResult {
        partialArtifactLifecycleCoordinator.retireAttempt(
            retirement.key.chapterId,
            retirement.key.generation,
        ).await()
        var cleanupError: Throwable? = null
        retirement.artifacts.forEach { artifact ->
            if (!artifact.exists()) return@forEach
            val removed = try {
                emitIo(DownloadIoOperation.CHAPTER_CLEANUP)
                artifactCleaner(artifact) && !artifact.exists()
            } catch (error: Exception) {
                cleanupError = cleanupError ?: error
                false
            }
            if (!removed && cleanupError == null) {
                cleanupError =
                    java.io.IOException("Unable to remove retired download artifact: ${artifact.absolutePath}")
            }
        }
        if (cleanupError == null) {
            partialArtifactLifecycleCoordinator.removeAttempt(
                retirement.key.chapterId,
                retirement.key.generation,
            )
        }
        return RetirementCleanupResult(success = cleanupError == null, error = cleanupError)
    }

    private fun registerIdentity(
        attempt: DownloadAttempt,
        identity: DownloadChapterIdentity,
    ): Boolean = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) return@synchronized false
        val currentQueue = _queue.value
        val itemIndex = currentQueue.indexOfFirst { item -> item.chapterId == attempt.item.chapterId }
        if (itemIndex < 0) return@synchronized false
        if (currentQueue[itemIndex].downloadIdentity == identity) {
            resolvedDownloadIdentities[attempt.key] = identity
            return@synchronized true
        }
        val nextQueue = currentQueue.toMutableList().apply {
            this[itemIndex] = currentQueue[itemIndex].copy(downloadIdentity = identity)
        }
        persistIdentityEntry(nextQueue, itemIndex)
        resolvedDownloadIdentities[attempt.key] = identity
        _queue.value = nextQueue
        true
    }

    private fun completeAttempt(attempt: DownloadAttempt): Boolean {
        val completed = synchronized(queueStateLock) {
            if (!isCurrentAttemptLocked(attempt)) {
                resolvedDownloadIdentities.remove(attempt.key)
                return@synchronized false
            }
            val item = _queue.value.first { it.chapterId == attempt.item.chapterId }
            if (stateMachine.transition(item.toEntry(0), DownloadQueueStatus.COMPLETED) == null) {
                return@synchronized false
            }
            val nextQueue = _queue.value.filterNot { it.chapterId == attempt.item.chapterId }
            // Persist first: a storage failure must leave the current generation and its published
            // artifact available for an explicit retry instead of committing a partial completion.
            persistQueue(nextQueue)
            resolvedDownloadIdentities.remove(attempt.key)
            queueGenerations.remove(attempt.item.chapterId)
            currentGenerations.remove(attempt.item.chapterId, attempt.generation)
            currentStatuses.remove(attempt.item.chapterId)
            committedIndexes.remove(attempt.key)
            reconcileCompletions.remove(attempt.key)
            _queue.value = nextQueue
            _failures.update { it - attempt.item.chapterId }
            true
        }
        if (completed) {
            partialArtifactLifecycleCoordinator.markAttemptCompleted(attempt.key.chapterId, attempt.key.generation)
        }
        return completed
    }

    private fun setStatus(attempt: DownloadAttempt, status: DownloadStatus): Boolean = synchronized(queueStateLock) {
        transitionLocked(
            chapterId = attempt.item.chapterId,
            target = DownloadQueueStatus.valueOf(status.name.replace("DONE", "COMPLETED")),
            expectedGeneration = attempt.generation,
        )
    }

    private fun startAttempt(attempt: DownloadAttempt): Boolean = synchronized(queueStateLock) {
        if (
            !transitionLocked(
                chapterId = attempt.item.chapterId,
                target = DownloadQueueStatus.DOWNLOADING,
                expectedGeneration = attempt.generation,
            )
        ) {
            return@synchronized false
        }
        activeProducers[attempt.key] = CompletableDeferred()
        true
    }

    private fun finishProducer(attempt: DownloadAttempt) {
        val completion = synchronized(queueStateLock) { activeProducers.remove(attempt.key) }
        completion?.complete(Unit)
    }

    private fun completedProducerSignal() = CompletableDeferred<Unit>().apply { complete(Unit) }

    private fun persistQueue(items: List<DownloadItem> = _queue.value) {
        val entries = items.mapIndexed { index, item -> item.toEntry(index.toLong()) }
        queuePersister?.invoke(entries) ?: store?.replaceAll(entries)
    }

    private fun persistIdentityEntry(items: List<DownloadItem>, itemIndex: Int) {
        val entry = items[itemIndex].toEntry(itemIndex.toLong())
        when {
            queueEntryPersister != null -> queueEntryPersister.invoke(entry)
            queuePersister != null -> persistQueue(items)
            else -> store?.upsert(entry)
        }
    }

    private fun updateAttempt(
        attempt: DownloadAttempt,
        transform: (DownloadItem) -> DownloadItem,
    ): Boolean = synchronized(queueStateLock) {
        if (!isCurrentAttemptLocked(attempt)) return@synchronized false
        val nextQueue = _queue.value.map { item ->
            if (item.chapterId == attempt.item.chapterId) transform(item) else item
        }
        persistQueue(nextQueue)
        _queue.value = nextQueue
        true
    }

    private fun updateRetryCount(attempt: DownloadAttempt, retryCount: Int): Boolean =
        updateAttempt(attempt) { it.copy(retryCount = retryCount) }

    private fun clearFailure(attempt: DownloadAttempt) = synchronized(queueStateLock) {
        if (isCurrentAttemptLocked(attempt)) _failures.update { it - attempt.item.chapterId }
    }

    private fun recordFailure(attempt: DownloadAttempt, error: AppError) {
        val chapterId = attempt.item.chapterId
        val notifier = taskNotifier ?: runCatching { Injekt.get<DesktopSystemNotifier>() }.getOrNull()
        val shouldNotify = synchronized(queueStateLock) {
            if (!isCurrentAttemptLocked(attempt)) return@synchronized false
            var changed = false
            val nextQueue = _queue.value.map { item ->
                if (item.chapterId == chapterId && item.status != DownloadStatus.CANCELLED) {
                    changed = true
                    item.copy(failure = error)
                } else {
                    item
                }
            }
            if (changed) {
                // Reporting the storage error itself must remain visible even when the same
                // persistence backend is unavailable.
                runCatching { persistQueue(nextQueue) }
                _queue.value = nextQueue
                _failures.update { it + (chapterId to error) }
            }
            changed
        }
        if (shouldNotify) {
            notifier?.notify(
                NotificationEvent.Failure(
                    "download:$chapterId:${attempt.generation}",
                    "下载失败",
                    error.notificationMessage(),
                ),
            )
        }
    }

    private fun fail(attempt: DownloadAttempt, error: AppError): Boolean {
        recordFailure(attempt, error)
        return false
    }

    private fun Throwable?.toAppError(): AppError {
        val error = this ?: return AppError.Unknown()
        if (error is DownloadHttpException) {
            return when (error.statusCode) {
                401, 403 -> AppError.Authentication(error)
                429 -> AppError.RateLimited(error.retryAfterSeconds, error)
                in 500..599 -> AppError.Server(error.statusCode, error)
                else -> AppError.Network(error)
            }
        }
        return when (error) {
            is NetworkDownloadException -> AppError.Network(error.cause ?: error)
            is java.nio.file.AccessDeniedException, is SecurityException -> AppError.Permission(error)
            is java.io.IOException -> AppError.Storage(error)
            else -> AppError.Unknown(error)
        }
    }

    private fun DownloadItem.toEntry(position: Long) = DownloadQueueEntry(
        chapterId = chapterId,
        mangaId = mangaId,
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = chapterName,
        chapterUrl = chapterUrl,
        pageUrls = pageUrls,
        status = DownloadQueueStatus.valueOf(status.name.replace("DONE", "COMPLETED")),
        progress = progress,
        position = position,
        retryCount = retryCount,
        failure = failure,
        pageTable = PartialPageTablePolicy.normalizeLegacyPageUrls(pageTable, pageUrls),
        downloadIdentity = downloadIdentity,
    )

    private fun DownloadQueueEntry.toItem() = DownloadItem(
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = chapterName,
        chapterId = chapterId,
        mangaId = mangaId,
        chapterUrl = chapterUrl,
        pageUrls = pageUrls,
        status = DownloadStatus.valueOf(status.name.replace("COMPLETED", "DONE")),
        progress = progress,
        retryCount = retryCount,
        failure = failure,
        pageTable = PartialPageTablePolicy.normalizeLegacyPageUrls(pageTable, pageUrls),
        downloadIdentity = downloadIdentity,
    )

    private fun extensionFromUrl(url: String): String {
        val ext = url.substringAfterLast('/').substringAfterLast('.').substringBefore('?').lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "avif" -> ext
            else -> "jpg"
        }
    }
}

private fun AppError.notificationMessage(): String = when (this) {
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Authentication -> "服务器拒绝访问（HTTP 403），请检查登录或源设置后重试"
    is AppError.RateLimited -> retryAfterSeconds?.let { "请求过于频繁，请在 $it 秒后重试" } ?: "请求过于频繁，请稍后重试"
    is AppError.Server -> "服务器错误（HTTP $statusCode），请稍后重试"
    is AppError.Permission -> "没有写入权限，请检查下载路径后重试"
    is AppError.Storage -> "磁盘空间不足或无法写入，请检查下载路径后重试"
    else -> "下载失败，请重试"
}

class DownloadHttpException(val statusCode: Int, val retryAfterSeconds: Long? = null) : java.io.IOException(
    "HTTP $statusCode",
)
class NetworkDownloadException(cause: Throwable) : java.io.IOException(cause)

interface DownloadFileOperations {
    fun execute(client: OkHttpClient, url: String): Response
    suspend fun readBody(response: Response): ByteArray
    fun writePage(tmp: File, bytes: ByteArray)
    fun renamePage(tmp: File, final: File)
    fun renameChapter(tmpDir: File, finalDir: File): Boolean
}

class DefaultDownloadFileOperations internal constructor(
    private val atomicPageFileMove: AtomicPageFileMove = AtomicPageFileMove(),
) : DownloadFileOperations {

    companion object : DownloadFileOperations by DefaultDownloadFileOperations()

    override fun execute(client: OkHttpClient, url: String): Response = try {
        client.newCall(Request.Builder().url(url).build()).execute().also { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val status = response.code
                response.close()
                throw DownloadHttpException(status, retryAfter)
            }
        }
    } catch (error: DownloadHttpException) {
        throw error
    } catch (error: java.io.IOException) {
        throw NetworkDownloadException(error)
    }

    override suspend fun readBody(response: Response): ByteArray = runInterruptible { response.body.bytes() }

    override fun writePage(tmp: File, bytes: ByteArray) {
        tmp.outputStream().use { it.write(bytes) }
    }

    override fun renamePage(tmp: File, final: File) {
        atomicPageFileMove.publish(tmp, final)
    }

    override fun renameChapter(tmpDir: File, finalDir: File): Boolean {
        Files.move(tmpDir.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return true
    }
}

/** An opened operation's local reference to the manager's existing attempt generation. */
data class CapturedDownloadAttempt internal constructor(
    val item: DownloadItem,
    internal val generation: Long,
    internal val capturedGeneration: CapturedDownloadGeneration = CapturedDownloadGeneration(generation),
) {
    internal var awaitCompletion: (suspend () -> Boolean)? = null
}

internal class CapturedDownloadGeneration(val generation: Long) {
    // Accessed only under the owning manager's queueStateLock.
    var replaced = false
}
