package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorArchiveV2Policy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryCommitPlan
import tachiyomi.domain.creator.model.DiscoveryRun
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.DueWatchSource
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LeaseAcquireResult
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceCheckpointResult
import tachiyomi.domain.creator.model.SourceCheckpointUpdate
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.model.SourceDiscoveryObservation
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WatchBaselineState
import tachiyomi.domain.creator.model.WatchSourceBaseline
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.source.service.SourceMangaSearchService

/**
 * Shared discovery executor.
 *
 * The archive-backed path owns the whole AA2-02 state machine: due selection, lease, run records,
 * per-source baseline, discovery+outbox commits, typed partial/failed summaries and backoff. The
 * legacy candidate path remains only as a temporary compatibility shim for callers that cannot yet
 * resolve an archive repository (removed by AA2-03); it never writes run/checkpoint/discovery state.
 */
class CreatorDiscoveryService(
    private val creatorRepository: CreatorRepository,
    private val sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val schedule: CreatorDiscoverySchedule = CreatorDiscoverySchedule(),
) {
    private var sourcePort: CreatorDiscoverySourcePort? = null
    private var archiveRepository: CreatorArchiveRepository? = creatorRepository as? CreatorArchiveRepository
    private var bounds: CreatorDiscoveryBounds = CreatorDiscoveryBounds()
    private var backoffJitterMillis: () -> Long = { 0L }

    constructor(
        creatorRepository: CreatorRepository,
        archiveRepository: CreatorArchiveRepository,
        sourcePort: CreatorDiscoverySourcePort,
        bounds: CreatorDiscoveryBounds = CreatorDiscoveryBounds(),
        clock: () -> Long = { System.currentTimeMillis() },
        backoffJitterMillis: () -> Long = { 0L },
        schedule: CreatorDiscoverySchedule = CreatorDiscoverySchedule(),
    ) : this(creatorRepository, SourceMangaSearchService(), clock, schedule) {
        this.archiveRepository = archiveRepository
        this.sourcePort = sourcePort
        this.bounds = bounds
        this.backoffJitterMillis = backoffJitterMillis
    }

    suspend fun discoverDueWatches(): CreatorDiscoveryResult {
        return discoverDueWatches(requireNotNull(sourcePort) { "Creator discovery source port is not configured" })
    }

    suspend fun discoverCreator(
        creatorId: Long,
        sourceIds: Set<Long> = emptySet(),
        languageTags: Set<String> = emptySet(),
    ): CreatorDiscoveryResult {
        return discoverCreator(
            creatorId = creatorId,
            sourceIds = sourceIds,
            languageTags = languageTags,
            port = requireNotNull(sourcePort) { "Creator discovery source port is not configured" },
        )
    }

    /** Temporary Android compatibility entry; Desktop production uses the enabled-source port. */
    suspend fun discoverDueWatches(sources: List<CatalogueSource>): CreatorDiscoveryResult {
        return discoverDueWatches(legacyPort(sources))
    }

    /** Temporary Android/test compatibility entry; it is still bounded and identity-gated. */
    suspend fun discoverCreator(
        creatorId: Long,
        sources: List<CatalogueSource>,
        sourceIds: List<Long> = emptyList(),
        languageTags: List<String> = emptyList(),
    ): CreatorDiscoveryResult = discoverCreator(
        creatorId = creatorId,
        sourceIds = sourceIds.toSet(),
        languageTags = languageTags.toSet(),
        port = legacyPort(sources),
        permissiveLegacyResultPolicy = true,
    )

    private suspend fun discoverDueWatches(port: CreatorDiscoverySourcePort): CreatorDiscoveryResult {
        val archive = archiveRepository
        return if (archive == null) {
            discoverDueWatchesLegacy(port)
        } else {
            discoverDueWatchesArchive(port, archive)
        }
    }

    private suspend fun discoverDueWatchesArchive(
        port: CreatorDiscoverySourcePort,
        archive: CreatorArchiveRepository,
    ): CreatorDiscoveryResult {
        val results = mutableListOf<CreatorDiscoveryResult>()
        // Resume interrupted runs first so a crashed run is re-driven before new due work.
        archive.getRecoverableDiscoveryRuns().forEach { run ->
            val creator = creatorRepository.getCreator(run.creatorId) ?: return@forEach
            val policy = archive.getWatchPolicy(run.creatorId)?.takeIf { it.enabled } ?: return@forEach
            results += runCreatorScan(
                creator = creator,
                policy = policy,
                dueSources = null,
                port = port,
                archive = archive,
                runKey = run.runKey,
                maxSources = run.totalSources.toInt(),
            )
        }
        val now = clock()
        val due = archive.getDueWatchSources(now, DUE_WATCH_ROW_LIMIT)
        due.groupBy(DueWatchSource::creatorId).forEach { (creatorId, sources) ->
            val creator = creatorRepository.getCreator(creatorId) ?: return@forEach
            val policy = archive.getWatchPolicy(creatorId)?.takeIf { it.enabled } ?: return@forEach
            results += runCreatorScan(
                creator = creator,
                policy = policy,
                dueSources = sources,
                port = port,
                archive = archive,
                runKey = "auto:${creator.id}:${clock()}",
            )
        }
        return results.merge()
    }

    private suspend fun discoverDueWatchesLegacy(port: CreatorDiscoverySourcePort): CreatorDiscoveryResult {
        val enabledSources = port.enabledSourcesSnapshot()
        val results = creatorRepository.getFollowedCreators()
            .filter { it.enabled }
            .mapNotNull { watch ->
                val creator = creatorRepository.getCreator(watch.creatorId) ?: return@mapNotNull null
                val policy = ArchiveWatchPolicy(
                    creatorId = creator.id,
                    enabled = true,
                    periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
                    sourceIds = watch.sourceIds.toSet(),
                    readingLanguageTags = watch.languageTags.toSet(),
                    includeProbable = true,
                    includeUnknown = true,
                )
                val result = discoverForCreatorLegacy(creator, policy, enabledSources, port)
                creatorRepository.updateWatchCheckResult(
                    creatorId = creator.id,
                    checkedAt = clock(),
                    success = result.errorCount == 0,
                    error = result.sourceResults.firstNotNullOfOrNull { it.failure?.safeCode() },
                )
                result
            }
        return results.merge()
    }

    private suspend fun discoverCreator(
        creatorId: Long,
        sourceIds: Set<Long>,
        languageTags: Set<String>,
        port: CreatorDiscoverySourcePort,
        permissiveLegacyResultPolicy: Boolean = false,
    ): CreatorDiscoveryResult {
        val creator = creatorRepository.getCreator(creatorId) ?: return CreatorDiscoveryResult.Empty
        val archive = archiveRepository
        if (archive == null) {
            return discoverCreatorLegacy(creatorId, sourceIds, languageTags, port, permissiveLegacyResultPolicy)
        }
        val storedPolicy = archive.getWatchPolicy(creatorId)
        val policy = (
            storedPolicy ?: ArchiveWatchPolicy(
                creatorId = creatorId,
                enabled = true,
                periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
                sourceIds = emptySet(),
                readingLanguageTags = emptySet(),
                includeProbable = permissiveLegacyResultPolicy,
                includeUnknown = permissiveLegacyResultPolicy,
            )
            ).copy(
            enabled = true,
            sourceIds = sourceIds.takeIf(Set<Long>::isNotEmpty) ?: storedPolicy?.sourceIds.orEmpty(),
            readingLanguageTags = languageTags.takeIf(Set<String>::isNotEmpty)
                ?: storedPolicy?.readingLanguageTags.orEmpty(),
        )
        // Manual force bypasses due/backoff but never the concurrent lease. A disabled or missing
        // watch has no run/checkpoint state to update, so it gets a bounded archive-only scan.
        return if (storedPolicy != null && storedPolicy.enabled) {
            runCreatorScan(
                creator = creator,
                policy = policy,
                dueSources = null,
                port = port,
                archive = archive,
                runKey = "manual:${creator.id}:${clock()}",
            )
        } else {
            runBareScan(creator, policy, port, archive)
        }
    }

    private suspend fun discoverCreatorLegacy(
        creatorId: Long,
        sourceIds: Set<Long>,
        languageTags: Set<String>,
        port: CreatorDiscoverySourcePort,
        permissiveLegacyResultPolicy: Boolean,
    ): CreatorDiscoveryResult {
        val creator = creatorRepository.getCreator(creatorId) ?: return CreatorDiscoveryResult.Empty
        val policy = ArchiveWatchPolicy(
            creatorId = creatorId,
            enabled = true,
            periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
            sourceIds = sourceIds,
            readingLanguageTags = languageTags,
            includeProbable = permissiveLegacyResultPolicy,
            includeUnknown = permissiveLegacyResultPolicy,
        )
        return discoverForCreatorLegacy(creator, policy, port.enabledSourcesSnapshot(), port)
    }

    /**
     * Full archive-backed run for one watch. Acquires the lease, creates/updates the typed run,
     * processes the planned sources with independent per-source checkpoints, writes the typed
     * terminal state and always releases the lease (including cancellation).
     */
    private suspend fun runCreatorScan(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        dueSources: List<DueWatchSource>?,
        port: CreatorDiscoverySourcePort,
        archive: CreatorArchiveRepository,
        runKey: String,
        maxSources: Int = bounds.maxSourcesPerWatch,
    ): CreatorDiscoveryResult {
        val startedAt = clock()
        val enabledSources = port.enabledSourcesSnapshot()
        val plan = CreatorDiscoveryQueryPlanner(bounds).plan(
            creatorId = creator.id,
            aliases = listOf(creator.displayName) + creator.aliases,
            enabledSources = enabledSources,
            watchPolicy = policy,
            startedAtMillis = startedAt,
        )
        val baselines = if (dueSources != null) {
            dueSources.associate {
                it.sourceId to WatchSourceBaseline(it.sourceId, it.baselineState, it.baselineGeneration)
            }
        } else {
            archive.getWatchSourceBaselines(creator.id).associateBy(WatchSourceBaseline::sourceId)
        }
        val plannedSources = plan.sources
            .filter { dueSources == null || it.source.sourceId in baselines }
            .take(maxSources)
        if (plannedSources.isEmpty()) {
            return CreatorDiscoveryResult(0, 0, emptyList(), skipped = true)
        }
        val now = clock()
        val leaseResult = archive.acquireWatchLease(
            creatorId = creator.id,
            ownerToken = "$runKey:$now",
            expiresAt = now + LEASE_DURATION_MILLIS,
            now = now,
        )
        if (leaseResult is LeaseAcquireResult.Busy) {
            return CreatorDiscoveryResult(0, 0, emptyList(), leaseBusy = true)
        }
        val ownerToken = (leaseResult as LeaseAcquireResult.Acquired).lease.ownerToken
        try {
            return executeScanWithRun(
                creator = creator,
                policy = policy,
                plan = plan,
                plannedSources = plannedSources,
                baselines = baselines,
                port = port,
                archive = archive,
                runKey = runKey,
                ownerToken = ownerToken,
                startedAt = startedAt,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    archive.updateDiscoveryRun(
                        runKey = runKey,
                        state = DiscoveryRunState.CANCELLED,
                        completedSources = 0,
                        truncated = false,
                        errorCode = null,
                        errorMessage = "cancelled",
                        occurredAt = clock(),
                    )
                }
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                runCatching { archive.releaseWatchLease(creator.id, ownerToken, clock()) }
            }
        }
    }

    /**
     * Bounded archive-only scan for a disabled or missing watch: no lease, no run and no checkpoint
     * writes. Relations are archived but events are never committed for an inactive watch.
     */
    private suspend fun runBareScan(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        port: CreatorDiscoverySourcePort,
        archive: CreatorArchiveRepository,
    ): CreatorDiscoveryResult {
        val startedAt = clock()
        val enabledSources = port.enabledSourcesSnapshot()
        val plan = CreatorDiscoveryQueryPlanner(bounds).plan(
            creatorId = creator.id,
            aliases = listOf(creator.displayName) + creator.aliases,
            enabledSources = enabledSources,
            watchPolicy = policy,
            startedAtMillis = startedAt,
        )
        val baselines = archive.getWatchSourceBaselines(creator.id).associateBy(WatchSourceBaseline::sourceId)
        val plannedSources = plan.sources.take(bounds.maxSourcesPerWatch)
        if (plannedSources.isEmpty()) {
            return CreatorDiscoveryResult(0, 0, emptyList(), skipped = true)
        }
        val semaphore = Semaphore(plan.maxConcurrentSources)
        val sourceResults = coroutineScope {
            plannedSources.map { sourcePlan ->
                async {
                    semaphore.withPermit {
                        val baseline = baselines[sourcePlan.source.sourceId]
                            ?: WatchSourceBaseline(
                                sourcePlan.source.sourceId,
                                WatchBaselineState.NEEDS_BASELINE,
                                0L,
                            )
                        try {
                            discoverSource(
                                creator = creator,
                                policy = policy,
                                plan = plan,
                                sourcePlan = sourcePlan,
                                port = port,
                                archive = archive,
                                baseline = baseline,
                                commitEvents = false,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            CreatorDiscoverySourceResult(
                                sourceId = sourcePlan.source.sourceId,
                                pageCount = 0,
                                matchedCount = 0,
                                possibleCount = 0,
                                insertedVerifiedCount = 0,
                                notificationEligibleCount = 0,
                                truncated = false,
                                failure = CreatorSourceFailure.Network(error.safeMessage()),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        return CreatorDiscoveryResult(
            newCandidateCount = sourceResults.sumOf(CreatorDiscoverySourceResult::eventCount),
            errorCount = sourceResults.count { it.failure != null },
            candidates = creatorRepository.getDiscoveryCandidatesForCreator(creator.id),
            sourceResults = sourceResults,
        )
    }

    private suspend fun executeScanWithRun(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        plan: CreatorDiscoveryQueryPlan,
        plannedSources: List<CreatorSourceQueryPlan>,
        baselines: Map<Long, WatchSourceBaseline>,
        port: CreatorDiscoverySourcePort,
        archive: CreatorArchiveRepository,
        runKey: String,
        ownerToken: String,
        startedAt: Long,
    ): CreatorDiscoveryResult {
        val createdRun = archive.createDiscoveryRun(runKey, creator.id, plannedSources.size.toLong(), startedAt)
        archive.updateDiscoveryRun(runKey, DiscoveryRunState.RUNNING, 0, false, null, null, startedAt)
        val previousCheckpoints = archive.getSourceCheckpoints(creator.id).associateBy(SourceCheckpoint::sourceId)
        val semaphore = Semaphore(plan.maxConcurrentSources)
        val sourceResults = coroutineScope {
            plannedSources.map { sourcePlan ->
                async {
                    semaphore.withPermit {
                        val baseline = baselines[sourcePlan.source.sourceId]
                            ?: WatchSourceBaseline(
                                sourcePlan.source.sourceId,
                                WatchBaselineState.NEEDS_BASELINE,
                                0L,
                            )
                        val previous = previousCheckpoints[sourcePlan.source.sourceId]
                        try {
                            val result = discoverSource(
                                creator = creator,
                                policy = policy,
                                plan = plan,
                                sourcePlan = sourcePlan,
                                port = port,
                                archive = archive,
                                baseline = baseline,
                                commitEvents = true,
                            )
                            commitSourceCheckpoint(archive, creator.id, policy, result, baseline, previous)
                            result
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            val failed = CreatorDiscoverySourceResult(
                                sourceId = sourcePlan.source.sourceId,
                                pageCount = 0,
                                matchedCount = 0,
                                possibleCount = 0,
                                insertedVerifiedCount = 0,
                                notificationEligibleCount = 0,
                                truncated = false,
                                failure = CreatorSourceFailure.Network(error.safeMessage()),
                            )
                            commitSourceCheckpoint(archive, creator.id, policy, failed, baseline, previous)
                            failed
                        }
                    }
                }
            }.awaitAll()
        }
        val completedSources = sourceResults.size.toLong()
        val failed = sourceResults.count { it.failure != null }
        val truncated = sourceResults.any(CreatorDiscoverySourceResult::truncated)
        val runState = when {
            failed == 0 -> DiscoveryRunState.SUCCEEDED
            failed == sourceResults.size -> DiscoveryRunState.FAILED
            else -> DiscoveryRunState.PARTIAL
        }
        val errorCode = sourceResults.firstNotNullOfOrNull { it.failure?.safeCode() }
        val errorMessage = if (failed > 0) "$failed of ${sourceResults.size} sources failed" else null
        archive.updateDiscoveryRun(
            runKey = runKey,
            state = runState,
            completedSources = completedSources.coerceAtMost(createdRun.totalSources),
            truncated = truncated,
            errorCode = errorCode,
            errorMessage = errorMessage,
            occurredAt = clock(),
        )
        return CreatorDiscoveryResult(
            newCandidateCount = sourceResults.sumOf(CreatorDiscoverySourceResult::eventCount),
            errorCount = failed,
            candidates = creatorRepository.getDiscoveryCandidatesForCreator(creator.id),
            sourceResults = sourceResults,
            runState = runState,
            completedSources = completedSources.toInt(),
            totalSources = plannedSources.size,
            truncated = truncated,
        )
    }

    /** Per-source typed checkpoint: baseline transition, backoff schedule and next due. */
    private suspend fun commitSourceCheckpoint(
        archive: CreatorArchiveRepository,
        creatorId: Long,
        policy: ArchiveWatchPolicy,
        result: CreatorDiscoverySourceResult,
        baseline: WatchSourceBaseline,
        previous: SourceCheckpoint?,
    ) {
        val now = clock()
        val success = result.failure == null
        val consecutiveFailures = if (success) 0L else (previous?.consecutiveFailures ?: 0L) + 1
        val backoffUntil = if (success) {
            null
        } else {
            CreatorDiscoveryBackoff.backoffUntilMillis(now, consecutiveFailures, backoffJitterMillis())
        }
        val nextDueAt = if (success) schedule.nextDue(now) else backoffUntil
        val completedBaseline = success && baseline.baselineState == WatchBaselineState.NEEDS_BASELINE
        val baselineState = if (completedBaseline) WatchBaselineState.BASELINED else baseline.baselineState
        val baselineGeneration = if (completedBaseline) baseline.baselineGeneration + 1 else baseline.baselineGeneration
        val resultState = when {
            !success -> SourceCheckpointResult.FAILED
            result.truncated -> SourceCheckpointResult.TRUNCATED
            result.matchedCount + result.possibleCount == 0 -> SourceCheckpointResult.EMPTY
            else -> SourceCheckpointResult.SUCCESS
        }
        archive.updateSourceCheckpoint(
            SourceCheckpointUpdate(
                creatorId = creatorId,
                sourceId = result.sourceId,
                cursor = null,
                result = resultState,
                consecutiveFailures = consecutiveFailures,
                backoffUntil = backoffUntil,
                checkedAt = now,
                successAt = if (success) now else null,
                errorCode = result.failure?.safeCode(),
                errorMessage = result.failure?.describe(),
                nextDueAt = nextDueAt,
                baselineState = baselineState,
                baselineGeneration = baselineGeneration,
            ),
        )
    }

    private suspend fun discoverForCreatorLegacy(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        enabledSources: List<EnabledCreatorSource>,
        port: CreatorDiscoverySourcePort,
    ): CreatorDiscoveryResult {
        val startedAt = clock()
        val plan = CreatorDiscoveryQueryPlanner(bounds).plan(
            creatorId = creator.id,
            aliases = listOf(creator.displayName) + creator.aliases,
            enabledSources = enabledSources,
            watchPolicy = policy,
            startedAtMillis = startedAt,
        )
        val semaphore = Semaphore(plan.maxConcurrentSources)
        val sourceResults = coroutineScope {
            plan.sources.map { sourcePlan ->
                async {
                    semaphore.withPermit {
                        discoverSourceLegacy(creator, policy, plan, sourcePlan, port)
                    }
                }
            }.awaitAll()
        }
        return CreatorDiscoveryResult(
            newCandidateCount = sourceResults.sumOf(CreatorDiscoverySourceResult::eventCount),
            errorCount = sourceResults.count { it.failure != null },
            candidates = creatorRepository.getDiscoveryCandidatesForCreator(creator.id),
            sourceResults = sourceResults,
        )
    }

    private suspend fun discoverSource(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        plan: CreatorDiscoveryQueryPlan,
        sourcePlan: CreatorSourceQueryPlan,
        port: CreatorDiscoverySourcePort,
        archive: CreatorArchiveRepository,
        baseline: WatchSourceBaseline,
        commitEvents: Boolean,
    ): CreatorDiscoverySourceResult {
        val works = linkedMapOf<SourceWorkNaturalKey, CreatorSourceWorkSnapshot>()
        var pageCount = 0
        var truncated = false
        for (alias in plan.aliases) {
            var aliasPage = 1
            while (aliasPage <= sourcePlan.maxPagesPerAlias && pageCount < sourcePlan.maxTotalPages) {
                val result = port.searchPage(
                    BoundedAuthorSearchPageRequest(
                        sourceId = sourcePlan.source.sourceId,
                        alias = alias,
                        page = aliasPage,
                        pageLimit = sourcePlan.maxPagesPerAlias,
                        deadlineAtMillis = plan.deadlineAtMillis,
                    ),
                )
                pageCount += 1
                when (result) {
                    is CreatorSourcePageResult.Content -> {
                        result.works.forEach { works.putIfAbsent(it.key, it) }
                        if (!result.hasNextPage) break
                        if (aliasPage == sourcePlan.maxPagesPerAlias || pageCount == sourcePlan.maxTotalPages) {
                            truncated = true
                            break
                        }
                    }
                    CreatorSourcePageResult.Empty -> break
                    is CreatorSourcePageResult.Failure -> return CreatorDiscoverySourceResult(
                        sourceId = sourcePlan.source.sourceId,
                        pageCount = pageCount,
                        matchedCount = 0,
                        possibleCount = 0,
                        insertedVerifiedCount = 0,
                        notificationEligibleCount = 0,
                        truncated = truncated,
                        failure = result.error,
                    )
                }
                aliasPage += 1
            }
            // Exhausted the page budget: truncated only when the last page promised more pages.
            if (truncated || pageCount == sourcePlan.maxTotalPages) break
        }

        var matched = 0
        var possible = 0
        var insertedVerified = 0
        var notificationEligible = 0
        var eventCount = 0
        for (listedWork in works.values) {
            val remainingMillis = plan.deadlineAtMillis - clock()
            if (remainingMillis <= 0) {
                truncated = true
                return CreatorDiscoverySourceResult(
                    sourcePlan.source.sourceId,
                    pageCount,
                    matched,
                    possible,
                    insertedVerified,
                    notificationEligible,
                    truncated,
                    CreatorSourceFailure.Timeout,
                )
            }
            val detailsResult = try {
                withTimeout(remainingMillis) { port.loadDetails(listedWork.key) }
            } catch (_: TimeoutCancellationException) {
                CreatorSourceDetailsResult.Failure(CreatorSourceFailure.Timeout)
            } catch (error: CancellationException) {
                throw error
            }
            val details = when (detailsResult) {
                is CreatorSourceDetailsResult.Content -> detailsResult.details
                is CreatorSourceDetailsResult.Failure -> return CreatorDiscoverySourceResult(
                    sourcePlan.source.sourceId,
                    pageCount,
                    matched,
                    possible,
                    insertedVerified,
                    notificationEligible,
                    truncated,
                    detailsResult.error,
                )
            }
            details.work.publishedDate?.let { date ->
                archive.recordSourceDateQualityObservations(
                    listOf(
                        SourceDateObservation(
                            identity = SourceDateQualityIdentity(
                                extensionPackage = sourcePlan.source.extensionPackage,
                                extensionVersion = sourcePlan.source.extensionVersion,
                                sourceId = details.work.key.sourceId,
                                field = tachiyomi.domain.creator.model.SourceDateField.WORK_PUBLISHED,
                            ),
                            workNaturalKey = details.work.key.stableSourceUrl,
                            rawValue = date.rawValue,
                            valueAt = date.valueAt,
                            precision = date.precision,
                            semanticConfirmed = date.semanticConfirmed,
                            observedAt = clock(),
                        ),
                    ),
                )
            }
            val identity = CreatorIdentityEvidenceEvaluator.evaluate(plan.aliases, details.work)
            if (identity.verification == CreatorRelationVerification.POSSIBLE) possible += 1 else matched += 1

            val languageAssertion = details.toReadingLanguageAssertion(sourcePlan.source)
            val originalLanguageAssertion = details.toOriginalLanguageAssertion()
            val languageProjection = CreatorArchiveV2Policy.projectLanguage(
                LanguageDimension.READING,
                listOf(languageAssertion),
            )
            val policyDecision = CreatorDiscoveryResultPolicy.evaluate(languageProjection, policy)
            if (!policyDecision.includeInArchive) continue
            if (policyDecision.notify && identity.verification == CreatorRelationVerification.VERIFIED) {
                notificationEligible += 1
            }

            val observationResult = archive.commitSourceDiscoveryObservation(
                SourceDiscoveryObservation(
                    sourceWork = details.work.key,
                    title = details.work.title,
                    authorText = details.work.authorText,
                    artistText = details.work.artistText,
                    thumbnailUrl = details.work.thumbnailUrl,
                    detailsFetchedAt = clock(),
                    creatorId = creator.id,
                    role = identity.role,
                    order = 0,
                    origin = CreatorRelationOrigin.AUTOMATIC,
                    verification = identity.verification,
                    sourceText = details.work.authorText ?: details.work.artistText,
                    confidence = identity.confidence,
                    relationEvidence = identity.evidence,
                    languageAssertion = languageAssertion,
                    languageActor = DecisionActor.ALGORITHM,
                    languageEvidencePayload = "creator discovery metadata",
                    languageAlgorithmVersion = DISCOVERY_ALGORITHM_VERSION,
                    languageAssertedAt = clock(),
                    languageIdempotencyKey = languageAssertion.idempotencyKey(details.work.key),
                    originalLanguageAssertion = originalLanguageAssertion,
                    originalLanguageIdempotencyKey = originalLanguageAssertion?.idempotencyKey(details.work.key),
                    notificationsEnabled = commitEvents && policyDecision.notify,
                    requiresActiveWatch = commitEvents,
                    baselineState = baseline.baselineState,
                    discoveryReason = "verified creator relation",
                    baselineGeneration = baseline.baselineGeneration,
                    discoveredAt = clock(),
                    outboxChannel = DEFAULT_OUTBOX_CHANNEL,
                    discoveryIdempotencyKey = discoveryIdempotencyKey(creator.id, details.work.key),
                ),
            )
            val relation = observationResult.relation
            if (identity.verification != CreatorRelationVerification.VERIFIED) continue
            if (relation is ArchiveUpsertOutcome.Inserted) insertedVerified += 1
            if (observationResult.discovery != null) {
                eventCount += 1
            }
        }
        return CreatorDiscoverySourceResult(
            sourceId = sourcePlan.source.sourceId,
            pageCount = pageCount,
            matchedCount = matched,
            possibleCount = possible,
            insertedVerifiedCount = insertedVerified,
            notificationEligibleCount = notificationEligible,
            truncated = truncated,
            failure = null,
            eventCount = eventCount,
            baselineState = baseline.baselineState,
            baselineGeneration = baseline.baselineGeneration,
        )
    }

    /** Temporary candidate-shaped path used only when no archive repository is resolvable. */
    private suspend fun discoverSourceLegacy(
        creator: Creator,
        policy: ArchiveWatchPolicy,
        plan: CreatorDiscoveryQueryPlan,
        sourcePlan: CreatorSourceQueryPlan,
        port: CreatorDiscoverySourcePort,
    ): CreatorDiscoverySourceResult {
        val works = linkedMapOf<SourceWorkNaturalKey, CreatorSourceWorkSnapshot>()
        var pageCount = 0
        var truncated = false
        for (alias in plan.aliases) {
            var aliasPage = 1
            while (aliasPage <= sourcePlan.maxPagesPerAlias && pageCount < sourcePlan.maxTotalPages) {
                val result = port.searchPage(
                    BoundedAuthorSearchPageRequest(
                        sourceId = sourcePlan.source.sourceId,
                        alias = alias,
                        page = aliasPage,
                        pageLimit = sourcePlan.maxPagesPerAlias,
                        deadlineAtMillis = plan.deadlineAtMillis,
                    ),
                )
                pageCount += 1
                when (result) {
                    is CreatorSourcePageResult.Content -> {
                        result.works.forEach { works.putIfAbsent(it.key, it) }
                        if (!result.hasNextPage) break
                        if (aliasPage == sourcePlan.maxPagesPerAlias || pageCount == sourcePlan.maxTotalPages) {
                            truncated = true
                            break
                        }
                    }
                    CreatorSourcePageResult.Empty -> break
                    is CreatorSourcePageResult.Failure -> return CreatorDiscoverySourceResult(
                        sourceId = sourcePlan.source.sourceId,
                        pageCount = pageCount,
                        matchedCount = 0,
                        possibleCount = 0,
                        insertedVerifiedCount = 0,
                        notificationEligibleCount = 0,
                        truncated = truncated,
                        failure = result.error,
                    )
                }
                aliasPage += 1
            }
            // Exhausted the page budget: truncated only when the last page promised more pages.
            if (truncated || pageCount == sourcePlan.maxTotalPages) break
        }

        // Pre-fetch the candidate key set so repeat scans never count the same candidate twice.
        val knownCandidateKeys = creatorRepository.getDiscoveryCandidatesForCreator(creator.id)
            .map { it.source to it.url }
            .toSet()
        var matched = 0
        var possible = 0
        var insertedVerified = 0
        var notificationEligible = 0
        for (listedWork in works.values) {
            val remainingMillis = plan.deadlineAtMillis - clock()
            if (remainingMillis <= 0) {
                truncated = true
                return CreatorDiscoverySourceResult(
                    sourcePlan.source.sourceId,
                    pageCount,
                    matched,
                    possible,
                    insertedVerified,
                    notificationEligible,
                    truncated,
                    CreatorSourceFailure.Timeout,
                )
            }
            val detailsResult = try {
                withTimeout(remainingMillis) { port.loadDetails(listedWork.key) }
            } catch (_: TimeoutCancellationException) {
                CreatorSourceDetailsResult.Failure(CreatorSourceFailure.Timeout)
            } catch (error: CancellationException) {
                throw error
            }
            val details = when (detailsResult) {
                is CreatorSourceDetailsResult.Content -> detailsResult.details
                is CreatorSourceDetailsResult.Failure -> return CreatorDiscoverySourceResult(
                    sourcePlan.source.sourceId,
                    pageCount,
                    matched,
                    possible,
                    insertedVerified,
                    notificationEligible,
                    truncated,
                    detailsResult.error,
                )
            }
            val identity = CreatorIdentityEvidenceEvaluator.evaluate(plan.aliases, details.work)
            if (identity.verification == CreatorRelationVerification.POSSIBLE) possible += 1 else matched += 1

            val languageAssertion = details.toReadingLanguageAssertion(sourcePlan.source)
            val languageProjection = CreatorArchiveV2Policy.projectLanguage(
                LanguageDimension.READING,
                listOf(languageAssertion),
            )
            val policyDecision = CreatorDiscoveryResultPolicy.evaluate(languageProjection, policy)
            if (!policyDecision.includeInArchive) continue
            if (policyDecision.notify && identity.verification == CreatorRelationVerification.VERIFIED) {
                notificationEligible += 1
            }

            persistLegacyCandidate(creator, details, identity, languageAssertion)
            if (
                identity.verification == CreatorRelationVerification.VERIFIED &&
                (details.work.key.sourceId to details.work.key.stableSourceUrl) !in knownCandidateKeys
            ) {
                insertedVerified += 1
            }
        }
        return CreatorDiscoverySourceResult(
            sourceId = sourcePlan.source.sourceId,
            pageCount = pageCount,
            matchedCount = matched,
            possibleCount = possible,
            insertedVerifiedCount = insertedVerified,
            notificationEligibleCount = notificationEligible,
            truncated = truncated,
            failure = null,
            eventCount = insertedVerified,
        )
    }

    private suspend fun persistLegacyCandidate(
        creator: Creator,
        details: CreatorSourceDetails,
        identity: CreatorIdentityEvidence,
        language: LanguageAssertionContract,
    ) {
        val candidate = creatorRepository.upsertDiscoveryCandidate(
            source = details.work.key.sourceId,
            url = details.work.key.stableSourceUrl,
            title = details.work.title,
            authorText = details.work.authorText,
            artistText = details.work.artistText,
            languageTag = language.tag,
            languageConfidence = language.confidence,
            languageEvidence = language.evidenceKind.name,
            thumbnailUrl = details.work.thumbnailUrl,
            detailsFetchedAt = clock(),
            state = DiscoveryCandidateState.NEW,
        )
        creatorRepository.linkDiscoveryCandidateCreator(
            candidateId = candidate.id,
            creatorId = creator.id,
            role = identity.role,
            sourceText = details.work.authorText ?: details.work.artistText,
            confidence = identity.confidence,
            evidence = identity.evidence,
        )
    }

    private fun CreatorSourceDetails.toReadingLanguageAssertion(
        source: EnabledCreatorSource,
    ): LanguageAssertionContract {
        val singleSourceLanguage = (source.readingLanguageProfile as? CreatorSourceReadingLanguageProfile.Single)
            ?.languageTag
        val genres = metadata["genres"]?.split('\u001f').orEmpty()
        val detection = MangaLanguageDetector.detect(
            sourceLang = singleSourceLanguage,
            explicitLanguage = readingLanguageTag,
            title = work.title,
            description = metadata["description"],
            genres = genres,
        )
        return LanguageAssertionContract(
            dimension = LanguageDimension.READING,
            tag = detection.tag.takeUnless { it == "unknown" } ?: "und",
            confidence = detection.confidence,
            evidenceKind = when (detection.evidence) {
                LanguageEvidence.EXPLICIT_METADATA -> LanguageEvidenceKind.STRUCTURED_METADATA
                LanguageEvidence.GENRE_TAG -> LanguageEvidenceKind.SOURCE_FILTER_OR_TAG
                LanguageEvidence.SOURCE_LANGUAGE -> LanguageEvidenceKind.SINGLE_LANGUAGE_SOURCE
                LanguageEvidence.TEXT_DETECTED -> LanguageEvidenceKind.TEXT_DETECTION
                LanguageEvidence.UNKNOWN -> LanguageEvidenceKind.UNKNOWN
            },
        )
    }

    private fun CreatorSourceDetails.toOriginalLanguageAssertion(): LanguageAssertionContract? {
        val explicitTag = originalLanguageTag ?: return null
        val detection = MangaLanguageDetector.detect(
            sourceLang = null,
            explicitLanguage = explicitTag,
            title = work.title,
            description = metadata["description"],
            genres = metadata["genres"]?.split('\u001f').orEmpty(),
        )
        return LanguageAssertionContract(
            dimension = LanguageDimension.ORIGINAL,
            tag = detection.tag,
            confidence = detection.confidence,
            evidenceKind = if (detection.evidence == LanguageEvidence.EXPLICIT_METADATA) {
                LanguageEvidenceKind.STRUCTURED_METADATA
            } else {
                LanguageEvidenceKind.UNKNOWN
            },
        )
    }

    private fun LanguageAssertionContract.idempotencyKey(key: SourceWorkNaturalKey): String {
        return listOf(
            "creator-discovery-language-v1",
            key.sourceId,
            key.stableSourceUrl,
            dimension,
            tag,
            confidence,
            evidenceKind,
        ).joinToString(":")
    }

    private fun discoveryIdempotencyKey(creatorId: Long, key: SourceWorkNaturalKey): String {
        return "creator-discovery:$creatorId:${key.sourceId}:${key.stableSourceUrl}"
    }

    private fun legacyPort(sources: List<CatalogueSource>): CreatorDiscoverySourcePort {
        val sourcesById = sources.distinctBy(CatalogueSource::id).associateBy(CatalogueSource::id)
        return CatalogueCreatorDiscoverySourceAdapter(
            enabledSourcesProvider = { sourcesById.values.toList() },
            sourceResolver = sourcesById::get,
            sourceMangaSearchService = sourceMangaSearchService,
            languageProfileProvider = { source ->
                source.lang.takeIf(String::isNotBlank)
                    ?.let(CreatorSourceReadingLanguageProfile::Single)
                    ?: CreatorSourceReadingLanguageProfile.Unknown
            },
            clock = clock,
        )
    }

    private fun List<CreatorDiscoveryResult>.merge(): CreatorDiscoveryResult = CreatorDiscoveryResult(
        newCandidateCount = sumOf(CreatorDiscoveryResult::newCandidateCount),
        errorCount = sumOf(CreatorDiscoveryResult::errorCount),
        candidates = flatMap(CreatorDiscoveryResult::candidates),
        sourceResults = flatMap(CreatorDiscoveryResult::sourceResults),
        runState = mapNotNull(CreatorDiscoveryResult::runState).maxByOrNull { it.worstRank() },
        completedSources = sumOf(CreatorDiscoveryResult::completedSources),
        totalSources = sumOf(CreatorDiscoveryResult::totalSources),
        truncated = any(CreatorDiscoveryResult::truncated),
        leaseBusy = any(CreatorDiscoveryResult::leaseBusy),
        skipped = isNotEmpty() && all(CreatorDiscoveryResult::skipped),
    )

    private fun DiscoveryRunState.worstRank(): Int = when (this) {
        DiscoveryRunState.SUCCEEDED -> 0
        DiscoveryRunState.QUEUED, DiscoveryRunState.RUNNING -> 1
        DiscoveryRunState.PARTIAL -> 2
        DiscoveryRunState.FAILED, DiscoveryRunState.CANCELLED -> 3
    }

    private fun CreatorSourceFailure.safeCode(): String = when (this) {
        is CreatorSourceFailure.AuthenticationRequired -> "AUTHENTICATION_REQUIRED"
        is CreatorSourceFailure.RateLimited -> "RATE_LIMITED"
        is CreatorSourceFailure.Http -> "HTTP_$statusCode"
        CreatorSourceFailure.Timeout -> "TIMEOUT"
        is CreatorSourceFailure.Network -> "NETWORK"
        is CreatorSourceFailure.MalformedResponse -> "MALFORMED_RESPONSE"
        CreatorSourceFailure.MissingSource -> "MISSING_SOURCE"
        CreatorSourceFailure.UnsupportedCapability -> "UNSUPPORTED_CAPABILITY"
    }

    private fun CreatorSourceFailure.describe(): String = when (this) {
        is CreatorSourceFailure.AuthenticationRequired -> "authentication required"
        is CreatorSourceFailure.RateLimited -> "rate limited"
        is CreatorSourceFailure.Http -> "http $statusCode"
        CreatorSourceFailure.Timeout -> "timeout"
        is CreatorSourceFailure.Network -> safeMessage ?: "network"
        is CreatorSourceFailure.MalformedResponse -> safeMessage ?: "malformed response"
        CreatorSourceFailure.MissingSource -> "missing source"
        CreatorSourceFailure.UnsupportedCapability -> "unsupported capability"
    }

    private fun Throwable.safeMessage(): String = message ?: "unexpected discovery failure"

    private companion object {
        const val DEFAULT_WATCH_PERIOD_MILLIS = 24 * 60 * 60 * 1_000L
        const val LEASE_DURATION_MILLIS = 30 * 60 * 1_000L
        const val DUE_WATCH_ROW_LIMIT = 80L
        const val DEFAULT_OUTBOX_CHANNEL = "DESKTOP"
        const val DISCOVERY_ALGORITHM_VERSION = "creator-discovery-v1"
    }
}

data class CreatorDiscoverySourceResult(
    val sourceId: Long,
    val pageCount: Int,
    val matchedCount: Int,
    val possibleCount: Int,
    val insertedVerifiedCount: Int,
    val notificationEligibleCount: Int,
    val truncated: Boolean,
    val failure: CreatorSourceFailure?,
    val eventCount: Int = 0,
    val baselineState: WatchBaselineState = WatchBaselineState.NEEDS_BASELINE,
    val baselineGeneration: Long = 0,
    val backoffUntil: Long? = null,
    val nextDueAt: Long? = null,
    val consecutiveFailures: Long = 0,
    val skipped: Boolean = false,
)

data class CreatorDiscoveryResult(
    val newCandidateCount: Int,
    val errorCount: Int,
    val candidates: List<DiscoveryCandidate>,
    val sourceResults: List<CreatorDiscoverySourceResult> = emptyList(),
    val runState: DiscoveryRunState? = null,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val truncated: Boolean = false,
    val leaseBusy: Boolean = false,
    val skipped: Boolean = false,
) {
    companion object {
        val Empty = CreatorDiscoveryResult(0, 0, emptyList())
    }
}
