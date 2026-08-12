package tachiyomi.data.creator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag
import tachiyomi.domain.creator.model.CreatorArchiveSubjectKey
import tachiyomi.domain.creator.model.CreatorArchiveV2Policy
import tachiyomi.domain.creator.model.CreatorIdentityOption
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorPortableKey
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateCreator
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryCommitPlan
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryLease
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.DiscoveryRun
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.DiscoveryStateVector
import tachiyomi.domain.creator.model.DueWatchSource
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.LeaseAcquireResult
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.MangaWorkMatch
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.NotificationOutboxItem
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceCheckpointResult
import tachiyomi.domain.creator.model.SourceCheckpointUpdate
import tachiyomi.domain.creator.model.SourceDiscoveryObservation
import tachiyomi.domain.creator.model.SourceDiscoveryObservationResult
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.StaleWorkDecisionException
import tachiyomi.domain.creator.model.WatchBaselineState
import tachiyomi.domain.creator.model.WatchSourceBaseline
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionProjection
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.repository.ReadyCreatorArchiveBootstrap
import tachiyomi.domain.creator.service.ChapterVariantRecord
import tachiyomi.domain.creator.service.ChapterVariantType
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.manga.model.Manga
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CreatorRepositoryImpl(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val portableKeyFactory: () -> String = { Uuid.random().toHexDashString() },
    private val bootstrap: CreatorArchiveBootstrap = ReadyCreatorArchiveBootstrap,
) : CreatorRepository, CreatorArchiveRepository {

    override suspend fun getCreatorWorkArchive(creatorId: Long): CreatorWorkArchive {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getCreatorWorkArchiveRows(creatorId, ::mapCreatorWorkArchiveRow)
        }
            .toCreatorWorkArchive()
    }

    override fun observeCreatorWorkArchive(creatorId: Long): Flow<CreatorWorkArchive> =
        handler.subscribeToList {
            author_archiveQueries.getCreatorWorkArchiveRows(creatorId, ::mapCreatorWorkArchiveRow)
        }
            .map(List<CreatorWorkArchiveRow>::toCreatorWorkArchive)
            .onStart { bootstrap.awaitReady() }

    override suspend fun replaceChapterVariants(
        sourceWork: SourceWorkNaturalKey,
        variants: List<ChapterVariantRecord>,
        now: Long,
    ) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            author_archiveQueries.deleteArchiveChapterVariants(sourceWork.sourceId, sourceWork.stableSourceUrl)
            variants.forEach { variant ->
                author_archiveQueries.insertArchiveChapterVariant(
                    chapterNaturalKey = variant.naturalKey,
                    volumeNumber = variant.volumeNumber,
                    chapterNumber = variant.chapterNumber,
                    partNumber = variant.partNumber,
                    variantType = variant.type.name,
                    rawName = variant.rawName,
                    evidence = variant.evidence,
                    createdAt = now,
                    lastModifiedAt = now,
                    sourceId = sourceWork.sourceId,
                    stableSourceUrl = sourceWork.stableSourceUrl,
                )
            }
        }
    }

    override suspend fun getChapterVariants(sourceWork: SourceWorkNaturalKey): List<ChapterVariantRecord> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveChapterVariants(
                sourceWork.sourceId,
                sourceWork.stableSourceUrl,
            ) { naturalKey, volume, chapter, part, type, rawName, evidence ->
                ChapterVariantRecord(
                    naturalKey = naturalKey,
                    rawName = rawName,
                    scanlator = null,
                    volumeNumber = volume,
                    chapterNumber = chapter,
                    partNumber = part,
                    type = ChapterVariantType.valueOf(type),
                    confidence = if (type == ChapterVariantType.UNKNOWN.name) 0.0 else 1.0,
                    evidence = evidence,
                )
            }
        }
    }
    override suspend fun upsertWatchPolicy(policy: ArchiveWatchPolicy, now: Long) {
        bootstrap.awaitReady()
        require(policy.periodMillis > 0) { "Watch period must be positive" }
        require(policy.sourceIds.all { it >= 0 }) { "Source IDs must not be negative" }
        val languageTags = policy.readingLanguageTags
            .map(CreatorArchiveLanguageTag::normalize)
            .filter { it != "und" }
            .toSet()
        handler.await(inTransaction = true) {
            author_archiveQueries.upsertArchiveWatchPolicyCommand(
                creatorId = policy.creatorId,
                enabled = policy.enabled,
                periodMillis = policy.periodMillis,
                now = now,
            )
            val watchId = author_archiveQueries.getArchiveWatchIdByCreator(policy.creatorId).executeAsOne()
            val existingSources = author_archiveQueries.getArchiveWatchSourceIds(watchId).executeAsList().toSet()
            (existingSources - policy.sourceIds).forEach { sourceId ->
                author_archiveQueries.deleteArchiveWatchSource(watchId, sourceId)
                author_archiveQueries.deleteArchiveSourceCheckpoint(watchId, sourceId)
            }
            policy.sourceIds.forEach { sourceId ->
                author_archiveQueries.upsertArchiveWatchSourceCommand(watchId, sourceId, now, now)
            }
            author_archiveQueries.upsertArchiveWatchPolicy(watchId, now, now)
            author_archiveQueries.updateArchiveWatchResultPolicyCommand(
                includeProbable = policy.includeProbable,
                includeUnknown = policy.includeUnknown,
                notifyProbable = policy.notifyProbable,
                notifyUnknown = policy.notifyUnknown,
                now = now,
                watchId = watchId,
            )
            val policyId = author_archiveQueries.getArchiveWatchPolicyId(watchId).executeAsOne()
            val existingLanguages = author_archiveQueries.getArchiveWatchLanguages(policyId).executeAsList().toSet()
            (existingLanguages - languageTags).forEach { language ->
                author_archiveQueries.deleteArchiveWatchLanguage(policyId, language)
            }
            (languageTags - existingLanguages).forEach { language ->
                author_archiveQueries.insertArchiveWatchLanguage(policyId, language)
            }
        }
    }

    override suspend fun getDueWatchSources(now: Long, limit: Long): List<DueWatchSource> {
        bootstrap.awaitReady()
        require(limit > 0) { "Due watch limit must be positive" }
        return handler.awaitList {
            author_archiveQueries.getArchiveDueWatchSources(now, limit, ::mapDueWatchSource)
        }
    }

    override suspend fun getWatchPolicy(creatorId: Long): ArchiveWatchPolicy? {
        bootstrap.awaitReady()
        return handler.awaitOneOrNull {
            author_archiveQueries.getArchiveWatchPolicyByCreator(creatorId) {
                    id,
                    enabled,
                    period,
                    includeProbable,
                    includeUnknown,
                    notifyProbable,
                    notifyUnknown,
                    sourceIds,
                    languageTags,
                ->
                ArchiveWatchPolicy(
                    creatorId = id,
                    enabled = enabled,
                    periodMillis = period,
                    sourceIds = decodeStrings(sourceIds).mapNotNull(String::toLongOrNull).toSet(),
                    readingLanguageTags = decodeStrings(languageTags).toSet(),
                    includeProbable = includeProbable,
                    includeUnknown = includeUnknown,
                    notifyProbable = notifyProbable,
                    notifyUnknown = notifyUnknown,
                )
            }
        }
    }

    override suspend fun acquireWatchLease(
        creatorId: Long,
        ownerToken: String,
        expiresAt: Long,
        now: Long,
    ): LeaseAcquireResult {
        bootstrap.awaitReady()
        require(ownerToken.isNotBlank()) { "Lease owner must not be blank" }
        require(expiresAt > now) { "Lease expiry must be in the future" }
        return handler.await(inTransaction = true) {
            author_archiveQueries.tryAcquireArchiveWatchLease(ownerToken, expiresAt, now, creatorId)
            val row = author_archiveQueries.getArchiveWatchLease(creatorId).executeAsOne()
            check(row.enabled) { "Disabled watch cannot be leased" }
            val lease = DiscoveryLease(checkNotNull(row.lease_owner), checkNotNull(row.lease_expires_at))
            if (lease.ownerToken == ownerToken && lease.expiresAtMillis == expiresAt) {
                LeaseAcquireResult.Acquired(lease)
            } else {
                LeaseAcquireResult.Busy(lease)
            }
        }
    }

    override suspend fun releaseWatchLease(creatorId: Long, ownerToken: String, now: Long) {
        bootstrap.awaitReady()
        require(ownerToken.isNotBlank()) { "Lease owner must not be blank" }
        handler.await { author_archiveQueries.releaseArchiveWatchLease(now, creatorId, ownerToken) }
    }

    override suspend fun createDiscoveryRun(
        runKey: String,
        creatorId: Long,
        totalSources: Long,
        queuedAt: Long,
    ): DiscoveryRun {
        bootstrap.awaitReady()
        require(runKey.isNotBlank()) { "Run key must not be blank" }
        require(totalSources >= 0) { "Total sources must not be negative" }
        return handler.await(inTransaction = true) {
            author_archiveQueries.insertArchiveDiscoveryRun(runKey, totalSources, queuedAt, creatorId)
            author_archiveQueries.getArchiveDiscoveryRunByKey(runKey, ::mapDiscoveryRun).executeAsOne()
        }
    }

    override suspend fun getRecoverableDiscoveryRuns(): List<DiscoveryRun> {
        bootstrap.awaitReady()
        return handler.awaitList { author_archiveQueries.getArchiveRecoverableDiscoveryRuns(::mapDiscoveryRun) }
    }

    override suspend fun updateDiscoveryRun(
        runKey: String,
        state: DiscoveryRunState,
        completedSources: Long,
        truncated: Boolean,
        errorCode: String?,
        errorMessage: String?,
        occurredAt: Long,
    ) {
        bootstrap.awaitReady()
        require(completedSources >= 0) { "Completed sources must not be negative" }
        handler.await(inTransaction = true) {
            val current = author_archiveQueries.getArchiveDiscoveryRunByKey(runKey, ::mapDiscoveryRun).executeAsOne()
            if (current.state == state) return@await
            require(CreatorArchiveV2Policy.canTransitionRun(current.state, state)) {
                "Invalid discovery run transition: ${current.state} -> $state"
            }
            require(completedSources <= current.totalSources) { "Completed sources exceed the run total" }
            author_archiveQueries.updateArchiveDiscoveryRun(
                state = state.name,
                completedSources = completedSources,
                truncated = truncated,
                errorCode = errorCode,
                errorMessage = errorMessage,
                occurredAt = occurredAt,
                runKey = runKey,
            )
        }
    }

    override suspend fun updateSourceCheckpoint(update: SourceCheckpointUpdate) {
        bootstrap.awaitReady()
        require(update.consecutiveFailures >= 0) { "Consecutive failures must not be negative" }
        require(update.baselineGeneration >= 0) { "Baseline generation must not be negative" }
        handler.await(inTransaction = true) {
            val currentBaseline = author_archiveQueries
                .getArchiveWatchSourceState(update.sourceId, update.creatorId)
                .executeAsOne()
            val currentBaselineState = WatchBaselineState.valueOf(currentBaseline.baseline_state)
            if (currentBaselineState != update.baselineState) {
                require(
                    CreatorArchiveV2Policy.canTransitionBaseline(
                        currentBaselineState,
                        update.baselineState,
                        sourceScopeChanged = false,
                    ),
                ) {
                    "Invalid watch baseline transition: $currentBaselineState -> ${update.baselineState}"
                }
            }
            author_archiveQueries.upsertArchiveSourceCheckpoint(
                sourceId = update.sourceId,
                cursor = update.cursor,
                resultState = update.result.name,
                consecutiveFailures = update.consecutiveFailures,
                backoffUntil = update.backoffUntil,
                checkedAt = update.checkedAt,
                successAt = update.successAt,
                errorCode = update.errorCode,
                errorMessage = update.errorMessage,
                creatorId = update.creatorId,
            )
            author_archiveQueries.updateArchiveWatchSourceAfterCheckpoint(
                baselineState = update.baselineState.name,
                baselineGeneration = update.baselineGeneration,
                nextDueAt = update.nextDueAt,
                checkedAt = update.checkedAt,
                sourceId = update.sourceId,
                creatorId = update.creatorId,
            )
            author_archiveQueries.updateArchiveWatchAfterCheckpoint(
                checkedAt = update.checkedAt,
                successAt = update.successAt,
                errorMessage = update.errorMessage,
                creatorId = update.creatorId,
            )
        }
    }

    override suspend fun getSourceCheckpoints(creatorId: Long): List<SourceCheckpoint> {
        bootstrap.awaitReady()
        return handler.awaitList { author_archiveQueries.getArchiveSourceCheckpoints(creatorId, ::mapSourceCheckpoint) }
    }

    override suspend fun getWatchSourceBaselines(creatorId: Long): List<WatchSourceBaseline> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveWatchSourceBaselines(creatorId, ::mapWatchSourceBaseline)
        }
    }

    override suspend fun sourceWorkIsInLibraryOrHistory(key: SourceWorkNaturalKey): Boolean {
        bootstrap.awaitReady()
        return handler.await {
            author_archiveQueries.sourceWorkInLibraryOrHistoryCount(
                sourceId = key.sourceId,
                stableSourceUrl = key.stableSourceUrl.trim(),
            ).executeAsOne() > 0L
        }
    }

    override suspend fun sourceWorkHasConfirmedCanonicalVersion(key: SourceWorkNaturalKey): Boolean {
        bootstrap.awaitReady()
        return handler.await {
            author_archiveQueries.sourceWorkHasConfirmedCanonicalVersionCount(
                sourceId = key.sourceId,
                stableSourceUrl = key.stableSourceUrl.trim(),
            ).executeAsOne() > 0L
        }
    }

    override suspend fun commitSourceDiscoveryObservation(
        observation: SourceDiscoveryObservation,
    ): SourceDiscoveryObservationResult {
        bootstrap.awaitReady()
        require(observation.order >= 0) { "Creator order must not be negative" }
        require(observation.confidence in 0.0..1.0) {
            "Creator relation confidence must be between 0 and 1"
        }
        require(observation.languageIdempotencyKey.isNotBlank()) {
            "Language assertion idempotency key must not be blank"
        }
        return handler.await(inTransaction = true) {
            val work = upsertSourceWorkRecord(
                sourceId = observation.sourceWork.sourceId,
                stableSourceUrl = observation.sourceWork.stableSourceUrl,
                mangaId = null,
                title = observation.title,
                authorText = observation.authorText,
                artistText = observation.artistText,
                thumbnailUrl = observation.thumbnailUrl,
                detailsFetchedAt = observation.detailsFetchedAt,
                reviewState = null,
                now = observation.detailsFetchedAt,
            )
            appendObservationLanguageAssertion(observation)
            val relation = upsertObservationCreatorRelation(work.sourceWorkId, observation)
            val plan = if (observation.notificationsEnabled) {
                CreatorArchiveV2Policy.planDiscoveryCommit(
                    baselineState = observation.baselineState,
                    relationVerification = observation.verification,
                    watchRelationOutcome = relation,
                    alreadyInLibraryOrHistory = author_archiveQueries.sourceWorkInLibraryOrHistoryCount(
                        observation.sourceWork.sourceId,
                        observation.sourceWork.stableSourceUrl.trim(),
                    ).executeAsOne() > 0L,
                    confirmedCanonicalWork = author_archiveQueries
                        .sourceWorkHasConfirmedCanonicalVersionCount(
                            observation.sourceWork.sourceId,
                            observation.sourceWork.stableSourceUrl.trim(),
                        )
                        .executeAsOne() > 0L,
                    idempotencyKey = observation.discoveryIdempotencyKey,
                )
            } else {
                null
            }
            val discovery = if (plan is DiscoveryCommitPlan.EventWithOutbox) {
                commitDiscoveryRecord(
                    DiscoveryCommit(
                        creatorId = observation.creatorId,
                        sourceWork = observation.sourceWork,
                        kind = plan.kind,
                        reason = observation.discoveryReason,
                        baselineGeneration = observation.baselineGeneration,
                        discoveredAt = observation.discoveredAt,
                        outboxChannel = observation.outboxChannel,
                        idempotencyKey = plan.idempotencyKey,
                    ),
                )
            } else {
                null
            }
            SourceDiscoveryObservationResult(relation, discovery)
        }
    }

    override fun observeSourceCheckpoints(creatorId: Long): Flow<List<SourceCheckpoint>> =
        handler.subscribeToList { author_archiveQueries.getArchiveSourceCheckpoints(creatorId, ::mapSourceCheckpoint) }
            .onStart { bootstrap.awaitReady() }

    override suspend fun commitDiscovery(commit: DiscoveryCommit): ArchiveDiscovery {
        bootstrap.awaitReady()
        require(commit.reason.isNotBlank()) { "Discovery reason must not be blank" }
        require(commit.outboxChannel.isNotBlank()) { "Outbox channel must not be blank" }
        require(commit.idempotencyKey.isNotBlank()) { "Discovery idempotency key must not be blank" }
        return handler.await(inTransaction = true) { commitDiscoveryRecord(commit) }
    }

    private fun Database.commitDiscoveryRecord(commit: DiscoveryCommit): ArchiveDiscovery {
        val existingId = author_archiveQueries.getArchiveDiscoveryByNaturalKey(
            commit.creatorId,
            commit.sourceWork.sourceId,
            commit.sourceWork.stableSourceUrl.trim(),
        ).executeAsOneOrNull()
        author_archiveQueries.insertArchiveDiscovery(
            kind = commit.kind.name,
            reason = commit.reason,
            baselineGeneration = commit.baselineGeneration,
            discoveredAt = commit.discoveredAt,
            sourceId = commit.sourceWork.sourceId,
            stableSourceUrl = commit.sourceWork.stableSourceUrl.trim(),
            creatorId = commit.creatorId,
        )
        val discoveryId = author_archiveQueries.getArchiveDiscoveryByNaturalKey(
            commit.creatorId,
            commit.sourceWork.sourceId,
            commit.sourceWork.stableSourceUrl.trim(),
        ).executeAsOne()
        if (existingId == null) {
            author_archiveQueries.insertArchiveNotificationOutbox(
                discoveryId = discoveryId,
                channel = commit.outboxChannel,
                idempotencyKey = commit.idempotencyKey,
                createdAt = commit.discoveredAt,
            )
            val outbox = author_archiveQueries
                .getArchiveNotificationOutboxByIdempotencyKey(commit.idempotencyKey)
                .executeAsOne()
            check(outbox.discovery_id == discoveryId && outbox.channel == commit.outboxChannel) {
                "Discovery idempotency key conflicts with another outbox payload"
            }
        }
        return author_archiveQueries
            .getArchiveDiscoveryProjectionById(discoveryId, ::mapArchiveDiscovery)
            .executeAsOne()
    }

    private fun Database.appendObservationLanguageAssertion(observation: SourceDiscoveryObservation) {
        val normalized = observation.languageAssertion.copy(
            tag = CreatorArchiveLanguageTag.normalize(observation.languageAssertion.tag),
        )
        if (normalized.evidenceKind == LanguageEvidenceKind.MANUAL) {
            require(observation.languageActor != DecisionActor.ALGORITHM) {
                "Manual language evidence requires USER or RESTORE actor"
            }
        }
        if (observation.languageActor == DecisionActor.ALGORITHM) {
            require(!observation.languageAlgorithmVersion.isNullOrBlank()) {
                "Algorithm language assertions require an algorithm version"
            }
        }
        val attempted = LanguageAssertionEvent(
            subjectType = "SOURCE_WORK",
            subjectKey = sourceWorkSubjectKey(
                observation.sourceWork.sourceId,
                observation.sourceWork.stableSourceUrl.trim(),
            ),
            assertion = normalized,
            actor = observation.languageActor,
            evidencePayload = observation.languageEvidencePayload,
            algorithmVersion = observation.languageAlgorithmVersion,
            assertedAt = observation.languageAssertedAt,
        )
        val existing = author_archiveQueries
            .getArchiveLanguageAssertionByIdempotencyKey(
                observation.languageIdempotencyKey,
                ::mapLanguageAssertionEvent,
            )
            .executeAsOneOrNull()
        check(existing == null || existing.copy(assertedAt = attempted.assertedAt) == attempted) {
            "Language assertion idempotency key conflicts with another observation"
        }
        if (existing == null) {
            author_archiveQueries.upsertArchiveLanguageAssertion(
                subjectType = attempted.subjectType,
                subjectKey = attempted.subjectKey,
                dimension = attempted.assertion.dimension.name,
                languageTag = attempted.assertion.tag,
                confidence = attempted.assertion.confidence,
                evidenceKind = attempted.assertion.evidenceKind.name,
                evidencePayload = attempted.evidencePayload,
                actor = attempted.actor.name,
                algorithmVersion = attempted.algorithmVersion,
                withdrawn = attempted.assertion.withdrawn,
                assertedAt = attempted.assertedAt,
                idempotencyKey = observation.languageIdempotencyKey,
            )
        }
    }

    private fun Database.upsertObservationCreatorRelation(
        sourceWorkId: Long,
        observation: SourceDiscoveryObservation,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey> {
        val before = author_archiveQueries
            .getArchiveSourceWorkCreator(sourceWorkId, observation.creatorId, ::mapRelationSnapshot)
            .executeAsOneOrNull()
        val requested = RelationSnapshot(
            role = observation.role.name,
            order = observation.order,
            origin = observation.origin.name,
            verification = observation.verification.name,
            sourceText = observation.sourceText,
            confidence = observation.confidence,
            evidence = observation.relationEvidence,
        )
        if (before == requested) return ArchiveUpsertOutcome.Unchanged(observation.sourceWork)
        author_archiveQueries.upsertArchiveSourceWorkCreator(
            sourceWorkId = sourceWorkId,
            creatorId = observation.creatorId,
            role = requested.role,
            creatorOrder = requested.order,
            origin = requested.origin,
            verification = requested.verification,
            sourceText = requested.sourceText,
            confidence = requested.confidence,
            evidence = requested.evidence,
            createdAt = observation.detailsFetchedAt,
            lastModifiedAt = observation.detailsFetchedAt,
        )
        val after = author_archiveQueries
            .getArchiveSourceWorkCreator(sourceWorkId, observation.creatorId, ::mapRelationSnapshot)
            .executeAsOne()
        return when {
            before == null -> ArchiveUpsertOutcome.Inserted(observation.sourceWork)
            before == after -> ArchiveUpsertOutcome.Unchanged(observation.sourceWork)
            else -> ArchiveUpsertOutcome.Updated(observation.sourceWork)
        }
    }

    override suspend fun getUnreadDiscoveries(limit: Long): List<ArchiveDiscovery> {
        bootstrap.awaitReady()
        require(limit > 0) { "Discovery limit must be positive" }
        return handler.awaitList { author_archiveQueries.getArchiveUnreadDiscoveries(limit, ::mapArchiveDiscovery) }
    }

    override fun observeUnreadDiscoveries(limit: Long): Flow<List<ArchiveDiscovery>> {
        require(limit > 0) { "Discovery limit must be positive" }
        return handler.subscribeToList {
            author_archiveQueries.getArchiveUnreadDiscoveries(limit, ::mapArchiveDiscovery)
        }
            .onStart { bootstrap.awaitReady() }
    }

    override suspend fun getDiscoveries(limit: Long): List<ArchiveDiscovery> {
        bootstrap.awaitReady()
        require(limit > 0) { "Discovery limit must be positive" }
        return handler.awaitList { author_archiveQueries.getArchiveDiscoveries(limit, ::mapArchiveDiscovery) }
    }

    override fun observeDiscoveries(limit: Long): Flow<List<ArchiveDiscovery>> {
        require(limit > 0) { "Discovery limit must be positive" }
        return handler.subscribeToList {
            author_archiveQueries.getArchiveDiscoveries(limit, ::mapArchiveDiscovery)
        }.onStart { bootstrap.awaitReady() }
    }

    override suspend fun getDiscovery(discoveryId: Long): ArchiveDiscovery? {
        bootstrap.awaitReady()
        return handler.awaitOneOrNull {
            author_archiveQueries.getArchiveDiscoveryProjectionById(discoveryId, ::mapArchiveDiscovery)
        }
    }

    override suspend fun markDiscoverySeen(discoveryId: Long, now: Long) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            val state = author_archiveQueries.getArchiveDiscoveryState(discoveryId).executeAsOne()
            if (state.read_state == DiscoveryReadState.SEEN.name) return@await
            require(
                CreatorArchiveV2Policy.canTransitionRead(
                    DiscoveryReadState.valueOf(state.read_state),
                    DiscoveryReadState.SEEN,
                ),
            )
            author_archiveQueries.markArchiveDiscoverySeen(now, discoveryId)
        }
    }

    override suspend fun markDiscoveriesSeen(discoveryIds: Set<Long>, now: Long) {
        bootstrap.awaitReady()
        if (discoveryIds.isEmpty()) return
        handler.await(inTransaction = true) {
            author_archiveQueries.markArchiveDiscoveriesSeen(now, discoveryIds)
        }
    }

    override suspend fun setDiscoveryReview(discoveryId: Long, disposition: ReviewDisposition, now: Long) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            val state = author_archiveQueries.getArchiveDiscoveryState(discoveryId).executeAsOne()
            val current = ReviewDisposition.valueOf(state.review_disposition)
            if (current == disposition) return@await
            require(CreatorArchiveV2Policy.canTransitionReview(current, disposition, explicitUserAction = true)) {
                "Invalid discovery review transition: $current -> $disposition"
            }
            author_archiveQueries.updateArchiveDiscoveryReview(disposition.name, now, discoveryId)
        }
    }

    override suspend fun deleteReviewedDiscoveries(before: Long) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            author_archiveQueries.deleteArchiveReviewedDiscoveries(before)
        }
    }

    override suspend fun getPendingNotificationOutbox(now: Long, limit: Long): List<NotificationOutboxItem> {
        bootstrap.awaitReady()
        require(limit > 0) { "Outbox limit must be positive" }
        return handler.awaitList {
            author_archiveQueries.getArchivePendingNotificationOutbox(now, limit, ::mapOutboxItem)
        }
    }

    override fun observePendingNotificationOutbox(now: Long, limit: Long): Flow<List<NotificationOutboxItem>> {
        require(limit > 0) { "Outbox limit must be positive" }
        return handler.subscribeToList {
            author_archiveQueries.getArchivePendingNotificationOutbox(now, limit, ::mapOutboxItem)
        }.onStart { bootstrap.awaitReady() }
    }

    override suspend fun updateNotificationDelivery(
        outboxId: Long,
        state: NotificationDeliveryState,
        error: String?,
        nextAttemptAt: Long?,
        occurredAt: Long,
    ) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            val current = NotificationDeliveryState.valueOf(
                author_archiveQueries.getArchiveNotificationOutboxState(outboxId).executeAsOne(),
            )
            if (current == state) return@await
            require(CreatorArchiveV2Policy.canTransitionDelivery(current, state)) {
                "Invalid notification delivery transition: $current -> $state"
            }
            author_archiveQueries.updateArchiveNotificationDelivery(
                state = state.name,
                error = error,
                nextAttemptAt = nextAttemptAt,
                occurredAt = occurredAt,
                outboxId = outboxId,
            )
        }
    }

    override suspend fun upsertCreator(displayName: String, aliases: List<String>): Creator {
        bootstrap.awaitReady()
        val trimmedName = displayName.trim()
        val normalizedName = CreatorNameNormalizer.normalize(trimmedName)
        require(normalizedName.isNotBlank()) { "Creator name must not be blank" }
        val now = clock()
        return handler.await(inTransaction = true) {
            val matches = author_archiveQueries
                .getArchiveCreatorsByAlias(normalizedName, ::mapCreator)
                .executeAsList()
            check(matches.size <= 1) {
                "Ambiguous creator identity for normalized alias '$normalizedName'; " +
                    "explicit identity selection is required"
            }
            val creatorId = matches.singleOrNull()?.id ?: run {
                author_archiveQueries.insertArchiveCreator(
                    portableKey = portableKeyFactory(),
                    displayName = trimmedName,
                    normalizedName = normalizedName,
                    sortName = trimmedName,
                    createdAt = now,
                    lastModifiedAt = now,
                )
                author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
            }
            author_archiveQueries.updateArchiveCreator(
                displayName = trimmedName,
                normalizedName = normalizedName,
                sortName = trimmedName,
                lastModifiedAt = now,
                id = creatorId,
            )
            (listOf(trimmedName) + aliases)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(CreatorNameNormalizer::normalize)
                .forEachIndexed { index, alias ->
                    author_archiveQueries.upsertArchiveAlias(
                        creatorId = creatorId,
                        rawAlias = alias,
                        normalizedAlias = CreatorNameNormalizer.normalize(alias),
                        source = if (index == 0) "PRIMARY" else "LEGACY_COMPAT",
                        evidence = if (index == 0) "creator display name" else "CreatorRepository.upsertCreator alias",
                        confidence = 1.0,
                        isManual = false,
                        createdAt = now,
                        lastModifiedAt = now,
                    )
                }
            author_archiveQueries.getArchiveCreator(creatorId, ::mapCreator).executeAsOne()
        }
    }

    override suspend fun getCreator(id: Long): Creator? {
        bootstrap.awaitReady()
        return handler.awaitOneOrNull { author_archiveQueries.getArchiveCreator(id, ::mapCreator) }
    }

    override fun getCreatorsAsFlow(): Flow<List<Creator>> {
        return handler.subscribeToList { author_archiveQueries.getArchiveCreators(::mapCreator) }
            .onStart { bootstrap.awaitReady() }
    }

    override suspend fun indexLibraryManga(manga: Manga, mentions: List<CreatorMention>) {
        indexLibraryMangaBatch(listOf(CreatorLibraryIndexEntry(manga, mentions)))
    }

    override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) {
        bootstrap.awaitReady()
        if (entries.isEmpty()) return
        handler.await(inTransaction = true) {
            val now = clock()
            entries.filterNot { it.manga.favorite }.forEach { entry ->
                removeLibraryMangaIndexRecord(entry.manga.id)
            }
            reconcileLibraryMangaBatch(entries.filter { it.manga.favorite }, now)
        }
    }

    override suspend fun removeLibraryMangaIndex(mangaId: Long) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            removeLibraryMangaIndexRecord(mangaId)
        }
    }

    override suspend fun removeStaleLibraryMangaIndexes() {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            author_archiveQueries.deleteStaleAutomaticArchiveMangaLinks()
            author_archiveQueries.deleteStaleAutomaticArchiveSourceWorkCreators()
            author_archiveQueries.detachStaleArchiveSourceWorksFromLibrary()
        }
    }

    override suspend fun getCreatorIdentityOptions(
        mangaId: Long,
        mention: CreatorMention,
    ): List<CreatorIdentityOption> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveCreatorIdentityOptions(
                mangaId = mangaId,
                normalizedAlias = mention.normalizedName,
                mapper = ::mapCreatorIdentityOption,
            )
        }
    }

    override suspend fun bindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
        creatorId: Long,
    ) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            bindMangaCreatorIdentity(manga, mention, creatorId, clock())
        }
    }

    override suspend fun createAndBindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
    ): Long {
        bootstrap.awaitReady()
        return handler.await(inTransaction = true) {
            val now = clock()
            val creatorId = createCreatorIdentity(
                displayName = mention.displayName,
                normalizedName = mention.normalizedName,
                needsReview = false,
                now = now,
                aliasSource = "USER",
                aliasEvidence = "explicit manga identity creation",
                aliasManual = true,
            )
            bindMangaCreatorIdentity(manga, mention, creatorId, now)
            creatorId
        }
    }

    override suspend fun addManualCreatorAlias(creatorId: Long, alias: String) {
        bootstrap.awaitReady()
        val rawAlias = alias.trim()
        val normalizedAlias = CreatorNameNormalizer.normalize(rawAlias)
        require(normalizedAlias.isNotBlank()) { "Creator alias must not be blank" }
        handler.await(inTransaction = true) {
            val creator = author_archiveQueries.getArchiveCreatorIdentityRecord(creatorId).executeAsOneOrNull()
                ?: error("Creator identity does not exist: $creatorId")
            check(creator.status == "ACTIVE") { "Creator identity is not active: $creatorId" }
            val now = clock()
            author_archiveQueries.upsertArchiveAlias(
                creatorId = creatorId,
                rawAlias = rawAlias,
                normalizedAlias = normalizedAlias,
                source = "USER",
                evidence = "manual alias",
                confidence = 1.0,
                isManual = true,
                createdAt = now,
                lastModifiedAt = now,
            )
        }
    }

    override suspend fun getManualCreatorAliases(creatorId: Long): List<String> {
        bootstrap.awaitReady()
        return handler.awaitList { author_archiveQueries.getArchiveManualAliases(creatorId) }
    }

    override suspend fun removeManualCreatorAlias(creatorId: Long, alias: String) {
        bootstrap.awaitReady()
        val normalizedAlias = CreatorNameNormalizer.normalize(alias)
        require(normalizedAlias.isNotBlank()) { "Creator alias must not be blank" }
        handler.await(inTransaction = true) {
            requireActiveIdentity(creatorId)
            author_archiveQueries.deleteArchiveManualAlias(creatorId, normalizedAlias)
        }
    }

    override suspend fun mergeCreatorIdentities(sourceCreatorId: Long, targetCreatorId: Long) {
        bootstrap.awaitReady()
        require(sourceCreatorId != targetCreatorId) { "Cannot merge an identity into itself" }
        handler.await(inTransaction = true) {
            val source = requireActiveIdentity(sourceCreatorId)
            val target = requireActiveIdentity(targetCreatorId)
            val now = clock()

            author_archiveQueries.getArchiveAliasesForCreatorIdentity(
                sourceCreatorId,
            ).executeAsList().forEach { alias ->
                author_archiveQueries.upsertArchiveAlias(
                    creatorId = targetCreatorId,
                    rawAlias = alias.raw_alias,
                    normalizedAlias = alias.normalized_alias,
                    source = alias.source,
                    evidence = alias.evidence,
                    confidence = alias.confidence,
                    isManual = alias.is_manual,
                    createdAt = alias.created_at,
                    lastModifiedAt = maxOf(alias.last_modified_at, now),
                )
            }

            author_archiveQueries.getArchiveMangaCreatorsForCreator(sourceCreatorId, ::mapMergeMangaRelation)
                .executeAsList()
                .forEach { relation ->
                    val full = author_archiveQueries
                        .getArchiveMangaLinksForIndex(relation.mangaId, ::mapIndexedRelation)
                        .executeAsList()
                        .single { it.creatorId == sourceCreatorId }
                    upsertMangaRelation(relation.mangaId, targetCreatorId, full, now)
                    author_archiveQueries.deleteArchiveMangaLink(relation.mangaId, sourceCreatorId)
                }

            author_archiveQueries
                .getArchiveSourceWorkCreatorsForCreator(sourceCreatorId, ::mapMergeSourceRelation)
                .executeAsList()
                .forEach { relation ->
                    val full = author_archiveQueries
                        .getArchiveSourceWorkCreatorsForIndex(relation.sourceWorkId, ::mapIndexedSourceRelation)
                        .executeAsList()
                        .single { it.creatorId == sourceCreatorId }
                    upsertSourceWorkRelation(relation.sourceWorkId, targetCreatorId, full, now)
                    author_archiveQueries.deleteArchiveSourceWorkCreator(relation.sourceWorkId, sourceCreatorId)
                }

            author_archiveQueries.getArchiveCanonicalCreatorsForCreator(
                sourceCreatorId,
            ).executeAsList().forEach { relation ->
                author_archiveQueries.upsertArchiveCanonicalCreator(
                    workId = relation.work_id,
                    creatorId = targetCreatorId,
                    role = relation.role,
                    creatorOrder = relation.creator_order,
                    origin = relation.origin,
                    evidence = relation.evidence,
                )
                author_archiveQueries.deleteArchiveCanonicalCreator(relation.work_id, sourceCreatorId)
            }

            mergeWatchArchive(sourceCreatorId, targetCreatorId, now)
            author_archiveQueries.remapArchiveCreatorLanguageAssertions(
                sourceSubjectKey = creatorSubjectKey(source.portable_key),
                targetSubjectKey = creatorSubjectKey(target.portable_key),
            )
            author_archiveQueries.markArchiveCreatorMerged(
                targetCreatorId = targetCreatorId,
                lastModifiedAt = now,
                sourceCreatorId = sourceCreatorId,
            )
            check(source.status == "ACTIVE")
        }
    }

    override suspend fun splitCreatorIdentity(
        sourceCreatorId: Long,
        mangaIds: Set<Long>,
        newDisplayName: String,
        sourceWorks: Set<SourceWorkNaturalKey>,
    ): Long {
        bootstrap.awaitReady()
        require(mangaIds.isNotEmpty() || sourceWorks.isNotEmpty()) {
            "At least one manga or source-work binding must be selected for split"
        }
        val displayName = newDisplayName.trim()
        val normalizedName = CreatorNameNormalizer.normalize(displayName)
        require(normalizedName.isNotBlank()) { "Split identity name must not be blank" }
        return handler.await(inTransaction = true) {
            requireActiveIdentity(sourceCreatorId)
            val now = clock()
            val targetCreatorId = createCreatorIdentity(
                displayName = displayName,
                normalizedName = normalizedName,
                needsReview = false,
                now = now,
                aliasSource = "USER",
                aliasEvidence = "identity split",
                aliasManual = true,
            )
            val selectedSourceWorkIds = linkedSetOf<Long>()
            mangaIds.sorted().forEach { mangaId ->
                val mangaRelation = author_archiveQueries
                    .getArchiveMangaLinksForIndex(mangaId, ::mapIndexedRelation)
                    .executeAsList()
                    .singleOrNull { it.creatorId == sourceCreatorId }
                    ?: error("Creator $sourceCreatorId is not bound to manga $mangaId")
                upsertMangaRelation(
                    mangaId = mangaId,
                    creatorId = targetCreatorId,
                    relation = mangaRelation.copy(origin = "USER", evidence = "identity split"),
                    now = now,
                )
                author_archiveQueries.deleteArchiveMangaLink(mangaId, sourceCreatorId)

                selectedSourceWorkIds += author_archiveQueries.getArchiveSourceWorksByManga(mangaId)
                    .executeAsList()
                    .map { it._id }
            }
            sourceWorks.sortedWith(compareBy(SourceWorkNaturalKey::sourceId, SourceWorkNaturalKey::stableSourceUrl))
                .forEach { key ->
                    val sourceWorkId = author_archiveQueries
                        .getArchiveSourceWorkByKey(key.sourceId, key.stableSourceUrl.trim())
                        .executeAsOneOrNull()
                        ?._id
                        ?: error("Source work does not exist: $key")
                    selectedSourceWorkIds += sourceWorkId
                }
            selectedSourceWorkIds.forEach { sourceWorkId ->
                val sourceRelation = author_archiveQueries
                    .getArchiveSourceWorkCreatorsForIndex(sourceWorkId, ::mapIndexedSourceRelation)
                    .executeAsList()
                    .singleOrNull { it.creatorId == sourceCreatorId }
                    ?: error("Creator $sourceCreatorId is not bound to source work $sourceWorkId")
                upsertSourceWorkRelation(
                    sourceWorkId = sourceWorkId,
                    creatorId = targetCreatorId,
                    relation = sourceRelation.copy(origin = "USER", evidence = "identity split"),
                    now = now,
                )
                author_archiveQueries.deleteArchiveSourceWorkCreator(sourceWorkId, sourceCreatorId)
            }
            if (selectedSourceWorkIds.isNotEmpty()) {
                val canonicalRelations = author_archiveQueries
                    .getArchiveCanonicalCreatorsForCreator(sourceCreatorId)
                    .executeAsList()
                    .associateBy { it.work_id }
                author_archiveQueries.getArchiveCanonicalWorkIdsForSourceWorks(selectedSourceWorkIds.toList())
                    .executeAsList()
                    .forEach { workId ->
                        canonicalRelations[workId]?.let { relation ->
                            author_archiveQueries.upsertArchiveCanonicalCreator(
                                workId = workId,
                                creatorId = targetCreatorId,
                                role = relation.role,
                                creatorOrder = relation.creator_order,
                                origin = "USER",
                                evidence = "identity split",
                            )
                            if (
                                author_archiveQueries.countArchiveCanonicalVersionsForCreatorOutsideSelection(
                                    workId = workId,
                                    creatorId = sourceCreatorId,
                                    selectedSourceWorkIds = selectedSourceWorkIds.toList(),
                                ).executeAsOne() == 0L
                            ) {
                                author_archiveQueries.deleteArchiveCanonicalCreator(workId, sourceCreatorId)
                            }
                        }
                    }
            }
            copyWatchForSplit(
                sourceCreatorId = sourceCreatorId,
                targetCreatorId = targetCreatorId,
                selectedSourceWorkIds = selectedSourceWorkIds,
                now = now,
            )
            targetCreatorId
        }
    }

    override suspend fun linkMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) {
        bootstrap.awaitReady()
        val now = clock()
        handler.await {
            author_archiveQueries.upsertArchiveMangaLink(
                mangaId = mangaId,
                creatorId = creatorId,
                role = role.name,
                creatorOrder = 0,
                origin = "USER",
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
        }
    }

    override suspend fun linkDiscoveryCandidateCreator(
        candidateId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) {
        bootstrap.awaitReady()
        val now = clock()
        handler.await {
            author_archiveQueries.upsertArchiveSourceWorkCreator(
                sourceWorkId = candidateId,
                creatorId = creatorId,
                role = role.name,
                creatorOrder = 0,
                origin = "AUTOMATIC",
                verification = if (role == CreatorRole.UNKNOWN) "POSSIBLE" else "VERIFIED",
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
        }
    }

    override suspend fun followCreator(
        creatorId: Long,
        sourceIds: List<Long>,
        languageTags: List<String>,
    ): CreatorWatch {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            author_archiveQueries.upsertArchiveWatch(
                creatorId = creatorId,
                periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
                createdAt = now,
                lastModifiedAt = now,
            )
            val watchId = author_archiveQueries.getArchiveWatchIdByCreator(creatorId).executeAsOne()
            val requestedSourceIds = sourceIds.distinct()
            val existingSourceIds = author_archiveQueries.getArchiveWatchSourceIds(watchId).executeAsList()
            (existingSourceIds - requestedSourceIds.toSet()).forEach { sourceId ->
                author_archiveQueries.deleteArchiveWatchSource(watchId, sourceId)
            }
            requestedSourceIds.forEach { sourceId ->
                author_archiveQueries.insertArchiveWatchSource(
                    watchId = watchId,
                    sourceId = sourceId,
                    createdAt = now,
                    lastModifiedAt = now,
                )
            }
            author_archiveQueries.upsertArchiveWatchPolicy(
                watchId = watchId,
                createdAt = now,
                lastModifiedAt = now,
            )
            val policyId = author_archiveQueries.getArchiveWatchPolicyId(watchId).executeAsOne()
            val requestedLanguageTags = languageTags
                .map(CreatorArchiveLanguageTag::normalize)
                .filter { it != "und" }
                .distinct()
            val existingLanguageTags = author_archiveQueries.getArchiveWatchLanguages(policyId).executeAsList()
            (existingLanguageTags - requestedLanguageTags.toSet()).forEach { languageTag ->
                author_archiveQueries.deleteArchiveWatchLanguage(policyId, languageTag)
            }
            requestedLanguageTags.forEach { languageTag ->
                author_archiveQueries.insertArchiveWatchLanguage(policyId, languageTag)
            }
            author_archiveQueries.getArchiveFollowedCreators(::mapCreatorWatch)
                .executeAsList()
                .first { it.creatorId == creatorId }
        }
    }

    override suspend fun unfollowCreator(creatorId: Long) {
        bootstrap.awaitReady()
        val now = clock()
        handler.await { author_archiveQueries.unfollowArchiveCreator(now, creatorId) }
    }

    override suspend fun getFollowedCreators(): List<CreatorWatch> {
        bootstrap.awaitReady()
        return handler.awaitList { author_archiveQueries.getArchiveFollowedCreators(::mapCreatorWatch) }
    }

    override fun getFollowedCreatorsAsFlow(): Flow<List<CreatorWatch>> {
        return handler.subscribeToList { author_archiveQueries.getArchiveFollowedCreators(::mapCreatorWatch) }
            .onStart { bootstrap.awaitReady() }
    }

    override suspend fun updateWatchCheckResult(creatorId: Long, checkedAt: Long, success: Boolean, error: String?) {
        bootstrap.awaitReady()
        handler.await {
            author_archiveQueries.updateArchiveWatchCheckResult(
                checkedAt = checkedAt,
                success = success,
                error = error,
                creatorId = creatorId,
            )
        }
    }

    override suspend fun upsertSourceWork(
        sourceId: Long,
        stableSourceUrl: String,
        mangaId: Long?,
        title: String,
        authorText: String?,
        artistText: String?,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey> {
        bootstrap.awaitReady()
        return handler.await(inTransaction = true) {
            upsertSourceWorkRecord(
                sourceId = sourceId,
                stableSourceUrl = stableSourceUrl,
                mangaId = mangaId,
                title = title,
                authorText = authorText,
                artistText = artistText,
                thumbnailUrl = thumbnailUrl,
                detailsFetchedAt = detailsFetchedAt,
                reviewState = null,
                now = clock(),
            ).outcome
        }
    }

    override suspend fun upsertSourceWorkCreator(
        sourceWork: SourceWorkNaturalKey,
        creatorId: Long,
        role: CreatorRole,
        order: Long,
        origin: CreatorRelationOrigin,
        verification: CreatorRelationVerification,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey> {
        bootstrap.awaitReady()
        require(order >= 0) { "Creator order must not be negative" }
        require(confidence in 0.0..1.0) { "Creator relation confidence must be between 0 and 1" }
        return handler.await(inTransaction = true) {
            val work = author_archiveQueries
                .getArchiveSourceWorkByKey(sourceWork.sourceId, sourceWork.stableSourceUrl.trim())
                .executeAsOneOrNull()
                ?: error("Source work does not exist: $sourceWork")
            val before = author_archiveQueries
                .getArchiveSourceWorkCreator(work._id, creatorId, ::mapRelationSnapshot)
                .executeAsOneOrNull()
            val requested = RelationSnapshot(
                role = role.name,
                order = order,
                origin = origin.name,
                verification = verification.name,
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
            )
            if (before == requested) return@await ArchiveUpsertOutcome.Unchanged(sourceWork)
            val now = clock()
            author_archiveQueries.upsertArchiveSourceWorkCreator(
                sourceWorkId = work._id,
                creatorId = creatorId,
                role = requested.role,
                creatorOrder = requested.order,
                origin = requested.origin,
                verification = requested.verification,
                sourceText = requested.sourceText,
                confidence = requested.confidence,
                evidence = requested.evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
            val after = author_archiveQueries
                .getArchiveSourceWorkCreator(work._id, creatorId, ::mapRelationSnapshot)
                .executeAsOne()
            when {
                before == null -> ArchiveUpsertOutcome.Inserted(sourceWork)
                before == after -> ArchiveUpsertOutcome.Unchanged(sourceWork)
                else -> ArchiveUpsertOutcome.Updated(sourceWork)
            }
        }
    }

    override suspend fun appendWorkDecision(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        decision: WorkDecisionContract,
        algorithmVersion: String?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<WorkDecisionContract> {
        bootstrap.awaitReady()
        require(idempotencyKey.isNotBlank()) { "Work decision idempotency key must not be blank" }
        require(score == null || score in 0.0..1.0) { "Work decision score must be between 0 and 1" }
        CreatorArchiveV2Policy.resolveWorkDecision(current = null, proposed = decision)
        if (decision.actor == DecisionActor.ALGORITHM) {
            require(!algorithmVersion.isNullOrBlank()) { "Algorithm decisions require an algorithm version" }
        }
        return handler.await(inTransaction = true) {
            val sourceWorkId = author_archiveQueries
                .getArchiveSourceWorkByKey(sourceWork.sourceId, sourceWork.stableSourceUrl.trim())
                .executeAsOneOrNull()
                ?._id
                ?: error("Source work does not exist: $sourceWork")
            val attempted = WorkDecisionEvent(
                sourceWorkId = sourceWorkId,
                workId = workId,
                decision = decision,
                algorithmVersion = algorithmVersion,
                score = score,
                evidence = evidence,
                decidedAt = decidedAt,
            )
            val existing = author_archiveQueries
                .getArchiveWorkDecisionByIdempotencyKey(idempotencyKey, ::mapWorkDecisionEvent)
                .executeAsOneOrNull()
            if (existing != null) {
                return@await if (existing == attempted) {
                    ArchiveAppendOutcome.Unchanged(existing.decision)
                } else {
                    ArchiveAppendOutcome.Conflict(existing.decision, attempted.decision)
                }
            }
            author_archiveQueries.upsertArchiveWorkDecision(
                sourceWorkId = sourceWorkId,
                workId = workId,
                state = decision.state.name,
                actor = decision.actor.name,
                explicit = decision.explicit,
                algorithmVersion = algorithmVersion,
                score = score,
                evidence = evidence,
                decidedAt = decidedAt,
                idempotencyKey = idempotencyKey,
            )
            reconcileArchiveCanonicalVersion(sourceWorkId)
            ArchiveAppendOutcome.Inserted(decision)
        }
    }

    override suspend fun getWorkDecisions(sourceWork: SourceWorkNaturalKey): List<WorkDecisionProjection> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveWorkDecisionProjections(
                sourceId = sourceWork.sourceId,
                stableSourceUrl = sourceWork.stableSourceUrl,
                mapper = ::mapWorkDecisionProjection,
            )
        }
    }

    override suspend fun appendUserWorkDecisionIfCurrent(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        state: WorkDecisionState,
        expectedDecidedAt: Long?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ): WorkDecisionProjection {
        bootstrap.awaitReady()
        require(score == null || score in 0.0..1.0)
        require(evidence.isNotBlank() && idempotencyKey.isNotBlank())
        return handler.await(inTransaction = true) {
            val sourceWorkId = author_archiveQueries.getArchiveSourceWorkByKey(
                sourceWork.sourceId,
                sourceWork.stableSourceUrl,
            ).executeAsOne()._id
            val attempted = WorkDecisionEvent(
                sourceWorkId = sourceWorkId,
                workId = workId,
                decision = WorkDecisionContract(state, DecisionActor.USER, explicit = true),
                algorithmVersion = null,
                score = score,
                evidence = evidence,
                decidedAt = decidedAt,
            )
            val existing = author_archiveQueries.getArchiveWorkDecisionByIdempotencyKey(
                idempotencyKey,
                ::mapWorkDecisionEvent,
            ).executeAsOneOrNull()
            if (existing != null) {
                require(existing == attempted) { "Work decision idempotency key already contains another review" }
                return@await author_archiveQueries.getArchiveWorkDecisionProjections(
                    sourceId = sourceWork.sourceId,
                    stableSourceUrl = sourceWork.stableSourceUrl,
                    mapper = ::mapWorkDecisionProjection,
                ).executeAsList().first { it.workId == workId }
            }
            val current = author_archiveQueries.getLatestArchiveWorkDecision(sourceWorkId, workId).executeAsOneOrNull()
            if (current?.decided_at != expectedDecidedAt) throw StaleWorkDecisionException()
            author_archiveQueries.upsertArchiveWorkDecision(
                sourceWorkId = sourceWorkId,
                workId = workId,
                state = state.name,
                actor = DecisionActor.USER.name,
                explicit = true,
                algorithmVersion = null,
                score = score,
                evidence = evidence,
                decidedAt = decidedAt,
                idempotencyKey = idempotencyKey,
            )
            reconcileArchiveCanonicalVersion(sourceWorkId)
            author_archiveQueries.getArchiveWorkDecisionProjections(
                sourceId = sourceWork.sourceId,
                stableSourceUrl = sourceWork.stableSourceUrl,
                mapper = ::mapWorkDecisionProjection,
            ).executeAsList().first { it.workId == workId }
        }
    }

    override suspend fun appendLanguageAssertion(
        subject: ArchiveLanguageSubject,
        assertion: LanguageAssertionContract,
        actor: DecisionActor,
        evidencePayload: String,
        algorithmVersion: String?,
        assertedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<LanguageAssertionContract> {
        bootstrap.awaitReady()
        require(idempotencyKey.isNotBlank()) { "Language assertion idempotency key must not be blank" }
        if (assertion.evidenceKind == LanguageEvidenceKind.MANUAL) {
            require(actor != DecisionActor.ALGORITHM) { "Manual language evidence requires USER or RESTORE actor" }
        }
        if (actor == DecisionActor.ALGORITHM) {
            require(!algorithmVersion.isNullOrBlank()) { "Algorithm language assertions require an algorithm version" }
        }
        return handler.await(inTransaction = true) {
            val resolvedSubject = resolveLanguageSubject(subject)
            val normalizedAssertion = assertion.copy(tag = CreatorArchiveLanguageTag.normalize(assertion.tag))
            val attempted = LanguageAssertionEvent(
                subjectType = resolvedSubject.first,
                subjectKey = resolvedSubject.second,
                assertion = normalizedAssertion,
                actor = actor,
                evidencePayload = evidencePayload,
                algorithmVersion = algorithmVersion,
                assertedAt = assertedAt,
            )
            val existing = author_archiveQueries
                .getArchiveLanguageAssertionByIdempotencyKey(idempotencyKey, ::mapLanguageAssertionEvent)
                .executeAsOneOrNull()
            if (existing != null) {
                return@await if (existing == attempted) {
                    ArchiveAppendOutcome.Unchanged(existing.assertion)
                } else {
                    ArchiveAppendOutcome.Conflict(existing.assertion, attempted.assertion)
                }
            }
            author_archiveQueries.upsertArchiveLanguageAssertion(
                subjectType = attempted.subjectType,
                subjectKey = attempted.subjectKey,
                dimension = attempted.assertion.dimension.name,
                languageTag = attempted.assertion.tag,
                confidence = attempted.assertion.confidence,
                evidenceKind = attempted.assertion.evidenceKind.name,
                evidencePayload = attempted.evidencePayload,
                actor = attempted.actor.name,
                algorithmVersion = attempted.algorithmVersion,
                withdrawn = attempted.assertion.withdrawn,
                assertedAt = attempted.assertedAt,
                idempotencyKey = idempotencyKey,
            )
            ArchiveAppendOutcome.Inserted(normalizedAssertion)
        }
    }

    override suspend fun getLanguageProjection(
        subject: ArchiveLanguageSubject,
        dimension: LanguageDimension,
    ): LanguageProjectionContract {
        bootstrap.awaitReady()
        return handler.await {
            val resolved = resolveLanguageSubject(subject)
            val assertions = author_archiveQueries.getArchiveLanguageAssertions(
                subjectType = resolved.first,
                subjectKey = resolved.second,
                dimension = dimension.name,
            ) { tag, confidence, evidenceKind, withdrawn ->
                LanguageAssertionContract(
                    dimension = dimension,
                    tag = tag,
                    confidence = confidence,
                    evidenceKind = LanguageEvidenceKind.valueOf(evidenceKind),
                    withdrawn = withdrawn,
                )
            }.executeAsList()
            CreatorArchiveV2Policy.projectLanguage(dimension, assertions)
        }
    }

    override suspend fun setManualLanguage(
        subject: ArchiveLanguageSubject,
        dimension: LanguageDimension,
        languageTag: String,
        now: Long,
    ) {
        appendLanguageAssertion(
            subject = subject,
            assertion = LanguageAssertionContract(
                dimension = dimension,
                tag = CreatorArchiveLanguageTag.normalize(languageTag),
                confidence = 1.0,
                evidenceKind = LanguageEvidenceKind.MANUAL,
            ),
            actor = DecisionActor.USER,
            evidencePayload = "manual-language-override",
            algorithmVersion = null,
            assertedAt = now,
            idempotencyKey = "manual-language:${subject.languageSubjectKey()}:${dimension.name}:$now",
        )
    }

    override suspend fun withdrawManualLanguage(
        subject: ArchiveLanguageSubject,
        dimension: LanguageDimension,
        now: Long,
    ) {
        appendLanguageAssertion(
            subject = subject,
            assertion = LanguageAssertionContract(
                dimension = dimension,
                tag = "und",
                confidence = 1.0,
                evidenceKind = LanguageEvidenceKind.MANUAL,
                withdrawn = true,
            ),
            actor = DecisionActor.USER,
            evidencePayload = "manual-language-withdrawal",
            algorithmVersion = null,
            assertedAt = now,
            idempotencyKey = "manual-language-withdraw:${subject.languageSubjectKey()}:${dimension.name}:$now",
        )
    }

    override suspend fun upsertDiscoveryCandidate(
        source: Long,
        url: String,
        title: String,
        authorText: String?,
        artistText: String?,
        languageTag: String,
        languageConfidence: Double,
        languageEvidence: String,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
        state: DiscoveryCandidateState,
    ): DiscoveryCandidate {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            val mutation = upsertSourceWorkRecord(
                sourceId = source,
                stableSourceUrl = url,
                mangaId = null,
                title = title,
                authorText = authorText,
                artistText = artistText,
                thumbnailUrl = thumbnailUrl,
                detailsFetchedAt = detailsFetchedAt,
                reviewState = state.toArchiveReviewState(),
                now = now,
            )
            val evidenceKind = languageEvidence.toArchiveEvidenceKind()
            author_archiveQueries.upsertArchiveLanguageAssertion(
                subjectType = "SOURCE_WORK",
                subjectKey = sourceWorkSubjectKey(source, url.trim()),
                dimension = "READING",
                languageTag = CreatorArchiveLanguageTag.normalize(languageTag),
                confidence = languageConfidence.coerceIn(0.0, 1.0),
                evidenceKind = evidenceKind,
                evidencePayload = languageEvidence,
                actor = "ALGORITHM",
                algorithmVersion = LEGACY_COMPAT_ALGORITHM_VERSION,
                withdrawn = false,
                assertedAt = now,
                idempotencyKey = languageAssertionIdempotencyKey(
                    sourceWorkId = mutation.sourceWorkId,
                    evidenceKind = evidenceKind,
                    languageTag = CreatorArchiveLanguageTag.normalize(languageTag),
                    confidence = languageConfidence,
                    evidencePayload = languageEvidence,
                    assertedAt = now,
                ),
            )
            getArchiveCandidate(sourceWorkId = mutation.sourceWorkId)
        }
    }

    override suspend fun getDiscoveryCandidatesForCreator(creatorId: Long): List<DiscoveryCandidate> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveCandidatesForCreator(
                sourceWorkId = -1,
                creatorId = creatorId,
                limit = Long.MAX_VALUE,
                mapper = ::mapDiscoveryCandidate,
            )
        }
    }

    override suspend fun getDiscoveryCandidate(id: Long): DiscoveryCandidate? {
        bootstrap.awaitReady()
        return handler.awaitOneOrNull {
            author_archiveQueries.getArchiveCandidatesForCreator(
                sourceWorkId = id,
                creatorId = null,
                limit = 1,
                mapper = ::mapDiscoveryCandidate,
            )
        }
    }

    override suspend fun getMangaCreatorsForCreator(creatorId: Long): List<MangaCreator> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveMangaCreatorsForCreator(creatorId, ::mapMangaCreator)
        }
    }

    override suspend fun getMangaTitlesForCreator(creatorId: Long): Map<Long, String> {
        bootstrap.awaitReady()
        return handler.awaitList { author_archiveQueries.getArchiveMangaTitlesForCreator(creatorId) }
            .associate { it.manga_id to it.title }
    }

    override suspend fun getDiscoveryCandidateCreatorsForCreator(creatorId: Long): List<DiscoveryCandidateCreator> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveSourceWorkCreatorsForCreator(creatorId, ::mapDiscoveryCandidateCreator)
        }
    }

    override suspend fun createCanonicalWork(
        primaryTitle: String,
        primaryCreatorId: Long?,
        originalLanguage: String?,
    ): CanonicalWork {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            val portableKey = portableKeyFactory()
            author_archiveQueries.insertArchiveCanonicalWork(
                portableKey = portableKey,
                primaryTitle = primaryTitle.trim(),
                normalizedTitle = CreatorNameNormalizer.normalize(primaryTitle),
                createdAt = now,
                lastModifiedAt = now,
            )
            val workId = author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
            if (primaryCreatorId != null) {
                author_archiveQueries.upsertArchiveCanonicalCreator(
                    workId = workId,
                    creatorId = primaryCreatorId,
                    role = "AUTHOR",
                    creatorOrder = 0,
                    origin = "USER",
                    evidence = "CreatorRepository.createCanonicalWork",
                )
            }
            if (!originalLanguage.isNullOrBlank()) {
                author_archiveQueries.upsertArchiveLanguageAssertion(
                    subjectType = "CANONICAL_WORK",
                    subjectKey = canonicalWorkSubjectKey(portableKey),
                    dimension = "ORIGINAL",
                    languageTag = CreatorArchiveLanguageTag.normalize(originalLanguage),
                    confidence = 1.0,
                    evidenceKind = "MANUAL",
                    evidencePayload = "legacy canonical-work original language",
                    actor = "USER",
                    algorithmVersion = null,
                    withdrawn = false,
                    assertedAt = now,
                    idempotencyKey = "legacy-canonical-work:$workId:original",
                )
            }
            author_archiveQueries.getArchiveCanonicalWork(workId, ::mapCanonicalWork).executeAsOne()
        }
    }

    override suspend fun upsertMangaWorkMatch(
        mangaId: Long,
        workId: Long,
        confidence: Double,
        matchReason: String,
        state: WorkMatchState,
        manuallyConfirmed: Boolean,
    ): MangaWorkMatch {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            val sourceWork = author_archiveQueries.getArchiveSourceWorkByManga(mangaId).executeAsOneOrNull()
                ?: author_archiveQueries.getMangaSourceWorkSeed(mangaId).executeAsOne().let { seed ->
                    val mutation = upsertSourceWorkRecord(
                        sourceId = seed.source,
                        stableSourceUrl = seed.url,
                        mangaId = mangaId,
                        title = seed.title,
                        authorText = seed.author,
                        artistText = seed.artist,
                        thumbnailUrl = seed.thumbnail_url,
                        detailsFetchedAt = null,
                        reviewState = "PENDING",
                        now = now,
                    )
                    author_archiveQueries.getArchiveSourceWorkById(mutation.sourceWorkId).executeAsOne()
                }
            val currentRow = author_archiveQueries
                .getLatestArchiveWorkDecision(sourceWork._id, workId)
                .executeAsOneOrNull()
            val current = currentRow?.let {
                WorkDecisionContract(
                    state = WorkDecisionState.valueOf(it.state),
                    actor = DecisionActor.valueOf(it.actor),
                    explicit = it.explicit,
                )
            }
            val proposed = WorkDecisionContract(
                state = state.toDecisionState(),
                actor = if (manuallyConfirmed) DecisionActor.USER else DecisionActor.ALGORITHM,
                explicit = manuallyConfirmed,
            )
            val resolved = CreatorArchiveV2Policy.resolveWorkDecision(current, proposed)
            if (resolved != current) {
                author_archiveQueries.upsertArchiveWorkDecision(
                    sourceWorkId = sourceWork._id,
                    workId = workId,
                    state = resolved.state.name,
                    actor = resolved.actor.name,
                    explicit = resolved.explicit,
                    algorithmVersion = if (resolved.actor == DecisionActor.ALGORITHM) {
                        LEGACY_COMPAT_ALGORITHM_VERSION
                    } else {
                        null
                    },
                    score = confidence.coerceIn(0.0, 1.0),
                    evidence = matchReason,
                    decidedAt = now,
                    idempotencyKey = workDecisionIdempotencyKey(
                        mangaId = mangaId,
                        workId = workId,
                        decision = resolved,
                        confidence = confidence,
                        evidence = matchReason,
                        decidedAt = now,
                    ),
                )
                reconcileArchiveCanonicalVersion(sourceWork._id)
            }
            val effective = author_archiveQueries
                .getLatestArchiveWorkDecision(sourceWork._id, workId)
                .executeAsOne()
            mapMangaWorkMatch(
                mangaId = mangaId,
                workId = workId,
                confidence = effective.score ?: confidence,
                matchReason = effective.evidence,
                state = effective.state,
                manuallyConfirmed = effective.actor != DecisionActor.ALGORITHM.name && effective.explicit,
                createdAt = effective.decided_at,
                lastModifiedAt = effective.decided_at,
            )
        }
    }

    private fun Database.reconcileLibraryMangaBatch(
        entries: List<CreatorLibraryIndexEntry>,
        now: Long,
    ) {
        if (entries.isEmpty()) return
        author_archiveQueries.createArchiveIndexWorkBuffer()
        author_archiveQueries.createArchiveIndexMentionBuffer()
        author_archiveQueries.clearArchiveIndexMentionBuffer()
        author_archiveQueries.clearArchiveIndexWorkBuffer()
        val workPayload = mutableListOf<String>()
        val mentionPayload = mutableListOf<String>()
        entries.forEach { entry ->
            val manga = entry.manga
            val title = manga.title.ifBlank { "Untitled ${manga.id}" }
            val normalizedTitle = CreatorNameNormalizer.normalize(title)
            val mentions = entry.mentions.distinctBy(CreatorMention::normalizedName)
            if (mentions.isEmpty()) {
                workPayload += archiveIndexPayloadRow(
                    manga.id.toString(),
                    normalizedTitle,
                    manga.archiveStableUrl(),
                    now.toString(),
                )
            }
            mentions.forEach { mention ->
                mentionPayload += archiveIndexPayloadRow(
                    manga.id.toString(),
                    normalizedTitle,
                    manga.archiveStableUrl(),
                    mention.displayName.trim(),
                    mention.normalizedName,
                    mention.role.name,
                    mention.order.toString(),
                    mention.sourceTexts.distinct().joinToString(" | "),
                    portableKeyFactory(),
                    now.toString(),
                )
            }
        }
        if (workPayload.isNotEmpty()) {
            author_archiveQueries.insertArchiveIndexWorkPayload(workPayload.joinToString(RECORD_SEPARATOR.toString()))
        }
        if (mentionPayload.isNotEmpty()) {
            author_archiveQueries.insertArchiveIndexMentionPayload(
                mentionPayload.joinToString(RECORD_SEPARATOR.toString()),
            )
        }
        author_archiveQueries.resolveArchiveIndexUniqueCurrentBindings()
        author_archiveQueries.resolveArchiveIndexProtectedCurrentBindings()
        author_archiveQueries.markArchiveIndexAmbiguousExistingCreators()
        author_archiveQueries.markArchiveIndexNewIdentities()
        author_archiveQueries.markArchiveIndexNewIdentityReviews()
        author_archiveQueries.insertArchiveIndexCreators()
        author_archiveQueries.resolveArchiveIndexNewCreatorIds()
        author_archiveQueries.insertArchiveIndexAliases()
        author_archiveQueries.detachStaleArchiveIndexSourceWorks()
        author_archiveQueries.upsertArchiveIndexSourceWorks()
        author_archiveQueries.upsertArchiveIndexMangaLinks()
        author_archiveQueries.upsertArchiveIndexSourceWorkCreators()
        author_archiveQueries.deleteStaleArchiveIndexMangaLinks()
        author_archiveQueries.deleteStaleArchiveIndexSourceWorkCreators()
        author_archiveQueries.clearArchiveIndexMentionBuffer()
        author_archiveQueries.clearArchiveIndexWorkBuffer()
    }

    private fun archiveIndexPayloadRow(vararg fields: String): String {
        return fields.joinToString(LIST_SEPARATOR.toString()) { field ->
            field
                .replace("%", "%25")
                .replace("\u0000", "%00")
                .replace(RECORD_SEPARATOR.toString(), "%1E")
                .replace(LIST_SEPARATOR.toString(), "%1F")
        }
    }

    private fun Database.removeLibraryMangaIndexRecord(mangaId: Long) {
        author_archiveQueries.getArchiveMangaLinksForIndex(mangaId, ::mapIndexedRelation).executeAsList()
            .filter { it.origin in AUTOMATIC_RELATION_ORIGINS }
            .forEach { author_archiveQueries.deleteAutomaticArchiveMangaLink(mangaId, it.creatorId) }
        author_archiveQueries.getArchiveSourceWorksByManga(mangaId).executeAsList().forEach { sourceWork ->
            author_archiveQueries
                .getArchiveSourceWorkCreatorsForIndex(sourceWork._id, ::mapIndexedSourceRelation)
                .executeAsList()
                .filter { it.origin in AUTOMATIC_RELATION_ORIGINS }
                .forEach {
                    author_archiveQueries.deleteAutomaticArchiveSourceWorkCreator(sourceWork._id, it.creatorId)
                }
        }
        author_archiveQueries.detachArchiveSourceWorkFromManga(mangaId)
    }

    private fun Database.createCreatorIdentity(
        displayName: String,
        normalizedName: String,
        needsReview: Boolean,
        now: Long,
        aliasSource: String,
        aliasEvidence: String,
        aliasManual: Boolean,
    ): Long {
        val trimmedName = displayName.trim()
        require(normalizedName.isNotBlank()) { "Creator identity name must not be blank" }
        author_archiveQueries.insertArchiveCreatorForReview(
            portableKey = portableKeyFactory(),
            displayName = trimmedName,
            normalizedName = normalizedName,
            sortName = trimmedName,
            needsReview = needsReview,
            createdAt = now,
            lastModifiedAt = now,
        )
        val creatorId = author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
        author_archiveQueries.upsertArchiveAlias(
            creatorId = creatorId,
            rawAlias = trimmedName,
            normalizedAlias = normalizedName,
            source = aliasSource,
            evidence = aliasEvidence,
            confidence = 1.0,
            isManual = aliasManual,
            createdAt = now,
            lastModifiedAt = now,
        )
        return creatorId
    }

    private fun Database.bindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
        creatorId: Long,
        now: Long,
    ) {
        requireActiveIdentity(creatorId)
        val sourceWork = upsertSourceWorkRecord(
            sourceId = manga.source,
            stableSourceUrl = manga.archiveStableUrl(),
            mangaId = manga.id,
            title = manga.title.ifBlank { "Untitled ${manga.id}" },
            authorText = manga.author,
            artistText = manga.artist,
            thumbnailUrl = manga.thumbnailUrl,
            detailsFetchedAt = manga.lastModifiedAt.takeIf { it > 0L },
            reviewState = null,
            now = now,
        )
        author_archiveQueries.getArchiveBoundCreatorIdsByAlias(manga.id, mention.normalizedName).executeAsList()
            .filter { it != creatorId }
            .forEach { previousCreatorId ->
                author_archiveQueries.deleteArchiveMangaLink(manga.id, previousCreatorId)
                author_archiveQueries.deleteArchiveSourceWorkCreator(
                    sourceWork.sourceWorkId,
                    previousCreatorId,
                )
            }
        author_archiveQueries.upsertArchiveAlias(
            creatorId = creatorId,
            rawAlias = mention.displayName,
            normalizedAlias = mention.normalizedName,
            source = "BIBLIOGRAPHY",
            evidence = "explicit manga identity binding",
            confidence = 1.0,
            isManual = false,
            createdAt = now,
            lastModifiedAt = now,
        )
        val sourceText = mention.sourceTexts.joinToString(" | ")
        author_archiveQueries.upsertArchiveMangaLink(
            mangaId = manga.id,
            creatorId = creatorId,
            role = mention.role.name,
            creatorOrder = mention.order,
            origin = "USER",
            sourceText = sourceText,
            confidence = 1.0,
            evidence = "explicit manga identity binding",
            createdAt = now,
            lastModifiedAt = now,
        )
        author_archiveQueries.upsertArchiveSourceWorkCreator(
            sourceWorkId = sourceWork.sourceWorkId,
            creatorId = creatorId,
            role = mention.role.name,
            creatorOrder = mention.order,
            origin = "USER",
            verification = "VERIFIED",
            sourceText = sourceText,
            confidence = 1.0,
            evidence = "explicit manga identity binding",
            createdAt = now,
            lastModifiedAt = now,
        )
    }

    private fun Database.requireActiveIdentity(creatorId: Long) =
        author_archiveQueries.getArchiveCreatorIdentityRecord(creatorId).executeAsOneOrNull()
            ?.also { check(it.status == "ACTIVE") { "Creator identity is not active: $creatorId" } }
            ?: error("Creator identity does not exist: $creatorId")

    private fun Database.upsertMangaRelation(
        mangaId: Long,
        creatorId: Long,
        relation: IndexedRelation,
        now: Long,
    ) {
        author_archiveQueries.upsertArchiveMangaLink(
            mangaId = mangaId,
            creatorId = creatorId,
            role = relation.role,
            creatorOrder = relation.order,
            origin = relation.origin,
            sourceText = relation.sourceText,
            confidence = relation.confidence,
            evidence = relation.evidence,
            createdAt = minOf(relation.createdAt, now),
            lastModifiedAt = maxOf(relation.lastModifiedAt, now),
        )
    }

    private fun Database.upsertSourceWorkRelation(
        sourceWorkId: Long,
        creatorId: Long,
        relation: IndexedSourceRelation,
        now: Long,
    ) {
        author_archiveQueries.upsertArchiveSourceWorkCreator(
            sourceWorkId = sourceWorkId,
            creatorId = creatorId,
            role = relation.role,
            creatorOrder = relation.order,
            origin = relation.origin,
            verification = relation.verification,
            sourceText = relation.sourceText,
            confidence = relation.confidence,
            evidence = relation.evidence,
            createdAt = minOf(relation.createdAt, now),
            lastModifiedAt = maxOf(relation.lastModifiedAt, now),
        )
    }

    private fun Database.mergeWatchArchive(sourceCreatorId: Long, targetCreatorId: Long, now: Long) {
        val sourceWatchId = author_archiveQueries.getArchiveWatchIdByCreator(sourceCreatorId).executeAsOneOrNull()
            ?: return
        val targetWatchId = author_archiveQueries.getArchiveWatchIdByCreator(targetCreatorId).executeAsOneOrNull()
        if (targetWatchId == null) {
            author_archiveQueries.reassignArchiveWatchCreator(targetCreatorId, now, sourceWatchId)
            return
        }
        author_archiveQueries.mergeArchiveWatchRoot(sourceWatchId, now, targetWatchId)
        author_archiveQueries.mergeArchiveWatchSources(targetWatchId, sourceWatchId)
        author_archiveQueries.mergeArchiveWatchPolicy(targetWatchId, sourceWatchId)
        author_archiveQueries.getArchiveWatchPolicyId(targetWatchId).executeAsOneOrNull()?.let { targetPolicyId ->
            author_archiveQueries.mergeArchiveWatchLanguages(targetPolicyId, sourceWatchId)
        }
        author_archiveQueries.moveArchiveWatchRuns(targetWatchId, sourceWatchId)
        author_archiveQueries.mergeArchiveSourceCheckpoints(targetWatchId, sourceWatchId)
        author_archiveQueries.mergeArchiveDuplicateDiscoveries(sourceWatchId, targetWatchId)
        author_archiveQueries.repointArchiveDuplicateDiscoveryOutbox(sourceWatchId, targetWatchId)
        author_archiveQueries.deleteArchiveDuplicateDiscoveries(sourceWatchId, targetWatchId)
        author_archiveQueries.moveArchiveWatchDiscoveries(targetWatchId, sourceWatchId)
        author_archiveQueries.deleteArchiveWatchById(sourceWatchId)
    }

    private fun Database.copyWatchForSplit(
        sourceCreatorId: Long,
        targetCreatorId: Long,
        selectedSourceWorkIds: Set<Long>,
        now: Long,
    ) {
        val sourceWatchId = author_archiveQueries.getArchiveWatchIdByCreator(sourceCreatorId).executeAsOneOrNull()
            ?: return
        author_archiveQueries.insertArchiveSplitWatch(targetCreatorId, now, now, sourceWatchId)
        val targetWatchId = author_archiveQueries.getArchiveWatchIdByCreator(targetCreatorId).executeAsOne()
        author_archiveQueries.insertArchiveSplitWatchSources(targetWatchId, now, now, sourceWatchId)
        author_archiveQueries.mergeArchiveWatchPolicy(targetWatchId, sourceWatchId)
        author_archiveQueries.getArchiveWatchPolicyId(targetWatchId).executeAsOneOrNull()?.let { targetPolicyId ->
            author_archiveQueries.mergeArchiveWatchLanguages(targetPolicyId, sourceWatchId)
        }
        if (selectedSourceWorkIds.isNotEmpty()) {
            author_archiveQueries.moveArchiveDiscoveriesForSourceWorks(
                targetWatchId = targetWatchId,
                sourceWatchId = sourceWatchId,
                sourceWorkIds = selectedSourceWorkIds.toList(),
            )
        }
    }

    private fun Database.applyFollowPolicy(
        creatorId: Long,
        sourceIds: List<Long>,
        languageTags: List<String>,
        now: Long,
    ) {
        author_archiveQueries.upsertArchiveWatch(
            creatorId = creatorId,
            periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
            createdAt = now,
            lastModifiedAt = now,
        )
        val watchId = author_archiveQueries.getArchiveWatchIdByCreator(creatorId).executeAsOne()
        sourceIds.distinct().forEach { sourceId ->
            author_archiveQueries.insertArchiveWatchSource(watchId, sourceId, now, now)
        }
        author_archiveQueries.upsertArchiveWatchPolicy(watchId, now, now)
        val policyId = author_archiveQueries.getArchiveWatchPolicyId(watchId).executeAsOne()
        languageTags.distinct().forEach { languageTag ->
            author_archiveQueries.insertArchiveWatchLanguage(policyId, CreatorArchiveLanguageTag.normalize(languageTag))
        }
    }

    private fun Manga.archiveStableUrl(): String = CreatorSourceWorkKey.stableUrl(url, title, author, artist)

    private fun Database.upsertSourceWorkRecord(
        sourceId: Long,
        stableSourceUrl: String,
        mangaId: Long?,
        title: String,
        authorText: String?,
        artistText: String?,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
        reviewState: String?,
        now: Long,
    ): SourceWorkMutation {
        val stableUrl = stableSourceUrl.trim()
        val trimmedTitle = title.trim()
        require(stableUrl.isNotBlank()) { "Source work URL must not be blank" }
        require(trimmedTitle.isNotBlank()) { "Source work title must not be blank" }
        val key = SourceWorkNaturalKey(sourceId, stableUrl)
        mangaId?.let {
            author_archiveQueries.detachOtherArchiveSourceWorksFromManga(it, sourceId, stableUrl)
        }
        val existing = author_archiveQueries.getArchiveSourceWorkByKey(sourceId, stableUrl).executeAsOneOrNull()
        if (existing == null) {
            author_archiveQueries.insertArchiveSourceWork(
                sourceId = sourceId,
                stableSourceUrl = stableUrl,
                mangaId = mangaId,
                title = trimmedTitle,
                normalizedTitle = CreatorNameNormalizer.normalize(trimmedTitle),
                authorText = authorText,
                artistText = artistText,
                thumbnailUrl = thumbnailUrl,
                firstSeenAt = now,
                lastSeenAt = now,
                detailsFetchedAt = detailsFetchedAt,
                legacyReviewSnapshot = reviewState,
            )
            val id = author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
            return SourceWorkMutation(id, ArchiveUpsertOutcome.Inserted(key))
        }
        val normalizedTitle = CreatorNameNormalizer.normalize(trimmedTitle)
        val effectiveDetailsFetchedAt = detailsFetchedAt ?: existing.details_fetched_at
        val effectiveMangaId = mangaId ?: existing.manga_id
        val effectiveReviewState = when {
            reviewState == null -> existing.legacy_review_snapshot
            existing.legacy_review_snapshot in setOf("ACCEPTED", "IGNORED", "MERGED") && reviewState == "PENDING" ->
                existing.legacy_review_snapshot
            else -> reviewState
        }
        val changed = existing.manga_id != effectiveMangaId ||
            existing.title != trimmedTitle ||
            existing.normalized_title != normalizedTitle ||
            existing.author_text != authorText ||
            existing.artist_text != artistText ||
            existing.thumbnail_url != thumbnailUrl ||
            existing.details_fetched_at != effectiveDetailsFetchedAt ||
            existing.legacy_review_snapshot != effectiveReviewState
        author_archiveQueries.updateArchiveSourceWorkMetadata(
            mangaId = mangaId,
            title = trimmedTitle,
            normalizedTitle = normalizedTitle,
            authorText = authorText,
            artistText = artistText,
            thumbnailUrl = thumbnailUrl,
            lastSeenAt = now,
            detailsFetchedAt = detailsFetchedAt,
            id = existing._id,
        )
        if (reviewState != null) {
            author_archiveQueries.updateArchiveSourceWorkLegacyReviewSnapshot(
                legacyReviewSnapshot = reviewState,
                id = existing._id,
            )
        }
        return SourceWorkMutation(
            sourceWorkId = existing._id,
            outcome = if (changed) ArchiveUpsertOutcome.Updated(key) else ArchiveUpsertOutcome.Unchanged(key),
        )
    }

    private fun Database.getArchiveCandidate(sourceWorkId: Long): DiscoveryCandidate {
        return author_archiveQueries.getArchiveCandidatesForCreator(
            sourceWorkId = sourceWorkId,
            creatorId = null,
            limit = 1,
            mapper = ::mapDiscoveryCandidate,
        ).executeAsOne()
    }

    private fun Database.resolveLanguageSubject(subject: ArchiveLanguageSubject): Pair<String, String> {
        return when (subject) {
            is ArchiveLanguageSubject.SourceWork -> {
                val key = subject.naturalKey.copy(stableSourceUrl = subject.naturalKey.stableSourceUrl.trim())
                check(
                    author_archiveQueries
                        .getArchiveSourceWorkByKey(key.sourceId, key.stableSourceUrl)
                        .executeAsOneOrNull() != null,
                ) { "Source work does not exist: $key" }
                "SOURCE_WORK" to sourceWorkSubjectKey(key.sourceId, key.stableSourceUrl)
            }
            is ArchiveLanguageSubject.CanonicalWork -> {
                check(
                    author_archiveQueries
                        .getArchiveCanonicalWorkIdByPortableKey(subject.portableKey.value)
                        .executeAsOneOrNull() != null,
                ) { "Canonical work does not exist: ${subject.portableKey.value}" }
                "CANONICAL_WORK" to canonicalWorkSubjectKey(subject.portableKey.value)
            }
            is ArchiveLanguageSubject.Creator -> {
                check(
                    author_archiveQueries
                        .getArchiveCreatorIdByPortableKey(subject.portableKey.value)
                        .executeAsOneOrNull() != null,
                ) { "Creator does not exist: ${subject.portableKey.value}" }
                "CREATOR" to CreatorArchiveSubjectKey.creator(subject.portableKey)
            }
        }
    }

    private fun ArchiveLanguageSubject.languageSubjectKey(): String = when (this) {
        is ArchiveLanguageSubject.SourceWork -> CreatorArchiveSubjectKey.sourceWork(naturalKey)
        is ArchiveLanguageSubject.CanonicalWork -> CreatorArchiveSubjectKey.canonicalWork(portableKey)
        is ArchiveLanguageSubject.Creator -> CreatorArchiveSubjectKey.creator(portableKey)
    }

    private fun mapRelationSnapshot(
        role: String,
        creatorOrder: Long,
        origin: String,
        verification: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = RelationSnapshot(
        role = role,
        order = creatorOrder,
        origin = origin,
        verification = verification,
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
    )

    private fun mapWorkDecisionEvent(
        sourceWorkId: Long,
        workId: Long,
        state: String,
        actor: String,
        explicit: Boolean,
        algorithmVersion: String?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
    ) = WorkDecisionEvent(
        sourceWorkId = sourceWorkId,
        workId = workId,
        decision = WorkDecisionContract(
            state = WorkDecisionState.valueOf(state),
            actor = DecisionActor.valueOf(actor),
            explicit = explicit,
        ),
        algorithmVersion = algorithmVersion,
        score = score,
        evidence = evidence,
        decidedAt = decidedAt,
    )

    private fun mapLanguageAssertionEvent(
        subjectType: String,
        subjectKey: String,
        dimension: String,
        languageTag: String,
        confidence: Double,
        evidenceKind: String,
        evidencePayload: String,
        actor: String,
        algorithmVersion: String?,
        withdrawn: Boolean,
        assertedAt: Long,
    ) = LanguageAssertionEvent(
        subjectType = subjectType,
        subjectKey = subjectKey,
        assertion = LanguageAssertionContract(
            dimension = LanguageDimension.valueOf(dimension),
            tag = languageTag,
            confidence = confidence,
            evidenceKind = LanguageEvidenceKind.valueOf(evidenceKind),
            withdrawn = withdrawn,
        ),
        actor = DecisionActor.valueOf(actor),
        evidencePayload = evidencePayload,
        algorithmVersion = algorithmVersion,
        assertedAt = assertedAt,
    )

    private fun mapCreator(
        id: Long,
        displayName: String,
        normalizedName: String,
        sortName: String?,
        aliases: String,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = Creator(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        sortName = sortName,
        aliases = decodeHexStrings(aliases).filter {
            CreatorNameNormalizer.normalize(it) != normalizedName
        },
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapCreatorIdentityOption(
        id: Long,
        portableKey: String,
        displayName: String,
        aliases: String,
        needsReview: Boolean,
        currentlyBound: Boolean,
    ) = CreatorIdentityOption(
        id = id,
        portableKey = CreatorPortableKey(portableKey),
        displayName = displayName,
        aliases = decodeHexStrings(aliases),
        needsReview = needsReview,
        currentlyBound = currentlyBound,
    )

    private fun mapIndexedRelation(
        creatorId: Long,
        role: String,
        creatorOrder: Long,
        origin: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = IndexedRelation(
        creatorId = creatorId,
        role = role,
        order = creatorOrder,
        origin = origin,
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapIndexedSourceRelation(
        creatorId: Long,
        role: String,
        creatorOrder: Long,
        origin: String,
        verification: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = IndexedSourceRelation(
        creatorId = creatorId,
        role = role,
        order = creatorOrder,
        origin = origin,
        verification = verification,
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapMergeMangaRelation(
        mangaId: Long,
        creatorId: Long,
        role: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = MergeMangaRelation(mangaId, creatorId, role, sourceText, confidence, evidence)

    private fun mapMergeSourceRelation(
        sourceWorkId: Long,
        creatorId: Long,
        role: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = MergeSourceRelation(sourceWorkId, creatorId, role, sourceText, confidence, evidence)

    private fun mapCreatorWatch(
        creatorId: Long,
        enabled: Boolean,
        sourceIds: String,
        languageTags: String,
        lastCheckedAt: Long?,
        lastSuccessAt: Long?,
        lastError: String?,
        createdAt: Long,
    ) = CreatorWatch(
        creatorId = creatorId,
        enabled = enabled,
        sourceIds = decodeStrings(sourceIds).mapNotNull(String::toLongOrNull),
        languageTags = decodeStrings(languageTags),
        lastCheckedAt = lastCheckedAt,
        lastSuccessAt = lastSuccessAt,
        lastError = lastError,
        createdAt = createdAt,
    )

    private fun mapCanonicalWork(
        id: Long,
        primaryTitle: String,
        normalizedTitle: String,
        primaryCreatorId: Long?,
        originalLanguage: String?,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = CanonicalWork(
        id = id,
        primaryTitle = primaryTitle,
        normalizedTitle = normalizedTitle,
        primaryCreatorId = primaryCreatorId,
        originalLanguage = originalLanguage,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = MangaCreator(
        mangaId = mangaId,
        creatorId = creatorId,
        role = CreatorRole.valueOf(role),
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
    )

    private fun mapDiscoveryCandidateCreator(
        candidateId: Long,
        creatorId: Long,
        role: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = DiscoveryCandidateCreator(
        candidateId = candidateId,
        creatorId = creatorId,
        role = CreatorRole.valueOf(role),
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
    )

    private fun mapMangaWorkMatch(
        mangaId: Long,
        workId: Long,
        confidence: Double,
        matchReason: String,
        state: String,
        manuallyConfirmed: Boolean,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = MangaWorkMatch(
        mangaId = mangaId,
        workId = workId,
        confidence = confidence,
        matchReason = matchReason,
        state = when (WorkDecisionState.valueOf(state)) {
            WorkDecisionState.SUGGESTED -> WorkMatchState.CANDIDATE
            WorkDecisionState.CONFIRMED -> WorkMatchState.CONFIRMED
            WorkDecisionState.REJECTED -> WorkMatchState.REJECTED
        },
        manuallyConfirmed = manuallyConfirmed,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapDiscoveryCandidate(
        id: Long,
        source: Long,
        url: String,
        title: String,
        normalizedTitle: String,
        authorText: String?,
        artistText: String?,
        languageTag: String,
        languageConfidence: Double,
        languageEvidence: String,
        thumbnailUrl: String?,
        firstSeenAt: Long,
        lastSeenAt: Long,
        detailsFetchedAt: Long?,
        state: String,
    ) = DiscoveryCandidate(
        id = id,
        source = source,
        url = url,
        title = title,
        normalizedTitle = normalizedTitle,
        authorText = authorText,
        artistText = artistText,
        languageTag = languageTag,
        languageConfidence = languageConfidence,
        languageEvidence = languageEvidence,
        thumbnailUrl = thumbnailUrl,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        detailsFetchedAt = detailsFetchedAt,
        state = when (state) {
            "PENDING" -> DiscoveryCandidateState.NEW
            "ACCEPTED" -> DiscoveryCandidateState.ACCEPTED
            "IGNORED" -> DiscoveryCandidateState.IGNORED
            "MERGED" -> DiscoveryCandidateState.MERGED
            else -> error("Unknown archive review state: $state")
        },
    )

    private fun decodeStrings(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(LIST_SEPARATOR).filter(String::isNotBlank)
    }

    private fun decodeHexStrings(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(',').map { hex ->
            require(hex.length % 2 == 0) { "Invalid archive alias encoding" }
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }.decodeToString()
        }
    }

    private fun WorkMatchState.toDecisionState(): WorkDecisionState = when (this) {
        WorkMatchState.CANDIDATE -> WorkDecisionState.SUGGESTED
        WorkMatchState.CONFIRMED -> WorkDecisionState.CONFIRMED
        WorkMatchState.REJECTED -> WorkDecisionState.REJECTED
    }

    private fun DiscoveryCandidateState.toArchiveReviewState(): String = when (this) {
        DiscoveryCandidateState.NEW -> "PENDING"
        DiscoveryCandidateState.ACCEPTED -> "ACCEPTED"
        DiscoveryCandidateState.IGNORED -> "IGNORED"
        DiscoveryCandidateState.MERGED -> "MERGED"
    }

    private fun String.toArchiveEvidenceKind(): String = when (uppercase()) {
        "EXPLICIT_METADATA", "STRUCTURED_METADATA" -> "STRUCTURED_METADATA"
        "GENRE_TAG", "SOURCE_FILTER_OR_TAG" -> "SOURCE_FILTER_OR_TAG"
        "SOURCE_LANGUAGE", "SINGLE_LANGUAGE_SOURCE" -> "SINGLE_LANGUAGE_SOURCE"
        "TEXT_DETECTED", "TEXT_DETECTION" -> "TEXT_DETECTION"
        "MANUAL" -> "MANUAL"
        else -> "UNKNOWN"
    }

    private fun sourceWorkSubjectKey(sourceId: Long, stableSourceUrl: String): String {
        return CreatorArchiveSubjectKey.sourceWork(SourceWorkNaturalKey(sourceId, stableSourceUrl))
    }

    private fun canonicalWorkSubjectKey(portableKey: String): String {
        return CreatorArchiveSubjectKey.canonicalWork(
            tachiyomi.domain.creator.model.CanonicalWorkPortableKey(portableKey),
        )
    }

    private fun creatorSubjectKey(portableKey: String): String {
        return CreatorArchiveSubjectKey.creator(
            tachiyomi.domain.creator.model.CreatorPortableKey(portableKey),
        )
    }

    private fun languageAssertionIdempotencyKey(
        sourceWorkId: Long,
        evidenceKind: String,
        languageTag: String,
        confidence: Double,
        evidencePayload: String,
        assertedAt: Long,
    ): String {
        return "legacy-language:$sourceWorkId:reading:$evidenceKind:$languageTag:${confidence.coerceIn(0.0, 1.0)}:" +
            "${evidencePayload.hashCode()}:$assertedAt"
    }

    private fun workDecisionIdempotencyKey(
        mangaId: Long,
        workId: Long,
        decision: WorkDecisionContract,
        confidence: Double,
        evidence: String,
        decidedAt: Long,
    ): String {
        return "legacy-work-decision:$mangaId:$workId:${decision.actor}:${decision.state}:" +
            "${confidence.coerceIn(0.0, 1.0)}:${evidence.hashCode()}:$decidedAt"
    }

    private data class SourceWorkMutation(
        val sourceWorkId: Long,
        val outcome: ArchiveUpsertOutcome<SourceWorkNaturalKey>,
    )

    private data class RelationSnapshot(
        val role: String,
        val order: Long,
        val origin: String,
        val verification: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class IndexedRelation(
        val creatorId: Long,
        val role: String,
        val order: Long,
        val origin: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
        val createdAt: Long,
        val lastModifiedAt: Long,
    )

    private data class IndexedSourceRelation(
        val creatorId: Long,
        val role: String,
        val order: Long,
        val origin: String,
        val verification: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
        val createdAt: Long,
        val lastModifiedAt: Long,
    )

    private data class MergeMangaRelation(
        val mangaId: Long,
        val creatorId: Long,
        val role: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class MergeSourceRelation(
        val sourceWorkId: Long,
        val creatorId: Long,
        val role: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class WorkDecisionEvent(
        val sourceWorkId: Long,
        val workId: Long,
        val decision: WorkDecisionContract,
        val algorithmVersion: String?,
        val score: Double?,
        val evidence: String,
        val decidedAt: Long,
    )

    private data class LanguageAssertionEvent(
        val subjectType: String,
        val subjectKey: String,
        val assertion: LanguageAssertionContract,
        val actor: DecisionActor,
        val evidencePayload: String,
        val algorithmVersion: String?,
        val assertedAt: Long,
    )

    private companion object {
        const val DEFAULT_WATCH_PERIOD_MILLIS = 86_400_000L
        const val LEGACY_COMPAT_ALGORITHM_VERSION = "creator-archive-v2-compat-1"
        const val RECORD_SEPARATOR = '\u001E'
        const val LIST_SEPARATOR = '\u001F'
        const val INDEX_EVIDENCE = "library bibliography parser v1"
        val AUTOMATIC_RELATION_ORIGINS = setOf("AUTOMATIC", "MIGRATION")
    }
}

private data class CreatorWorkArchiveRow(
    val version: SourceWorkArchiveVersion,
    val canonicalWorkId: Long?,
    val canonicalPortableKey: String?,
    val canonicalTitle: String?,
)

private fun mapWorkDecisionProjection(
    workId: Long,
    portableKey: String,
    title: String,
    state: String,
    actor: String,
    explicit: Boolean,
    score: Double?,
    evidence: String,
    decidedAt: Long,
) = WorkDecisionProjection(
    workId = workId,
    workPortableKey = portableKey,
    workTitle = title,
    decision = WorkDecisionContract(
        state = WorkDecisionState.valueOf(state),
        actor = DecisionActor.valueOf(actor),
        explicit = explicit,
    ),
    score = score,
    evidence = evidence,
    decidedAt = decidedAt,
)

private fun mapCreatorWorkArchiveRow(
    sourceWorkId: Long,
    sourceId: Long,
    stableSourceUrl: String,
    mangaId: Long?,
    title: String,
    detailsFetchedAt: Long?,
    lastSeenAt: Long,
    lastCheckResult: String?,
    consecutiveFailures: Long,
    lastSuccessAt: Long?,
    inLibrary: Long,
    chapterCount: Long,
    canonicalWorkId: Long?,
    canonicalPortableKey: String?,
    canonicalTitle: String?,
    decisionWorkId: Long?,
    decisionWorkPortableKey: String?,
    decisionWorkTitle: String?,
    decisionState: String?,
    decisionActor: String?,
    decisionExplicit: Boolean?,
    decisionScore: Double?,
    decisionEvidence: String?,
    decisionDecidedAt: Long?,
    languageTag: String?,
    languageConfidence: Double?,
    languageEvidenceKind: String?,
    languageConflict: Long,
    originalLanguageTag: String?,
    originalLanguageConfidence: Double?,
    originalLanguageEvidenceKind: String?,
    originalLanguageConflict: Long,
): CreatorWorkArchiveRow {
    val language = if (languageConflict != 0L && languageEvidenceKind != null) {
        LanguageProjectionContract(
            dimension = LanguageDimension.READING,
            tag = "und",
            certainty = LanguageCertainty.CONFLICT,
            evidenceKind = LanguageEvidenceKind.valueOf(languageEvidenceKind),
        )
    } else if (languageTag == null) {
        LanguageProjectionContract(
            dimension = LanguageDimension.READING,
            tag = "und",
            certainty = LanguageCertainty.UNKNOWN,
            evidenceKind = LanguageEvidenceKind.UNKNOWN,
        )
    } else {
        CreatorArchiveV2Policy.projectLanguage(
            LanguageDimension.READING,
            listOf(
                LanguageAssertionContract(
                    dimension = LanguageDimension.READING,
                    tag = languageTag,
                    confidence = languageConfidence ?: 0.0,
                    evidenceKind = LanguageEvidenceKind.valueOf(checkNotNull(languageEvidenceKind)),
                ),
            ),
        )
    }
    val originalLanguage = if (originalLanguageConflict != 0L && originalLanguageEvidenceKind != null) {
        LanguageProjectionContract(
            dimension = LanguageDimension.ORIGINAL,
            tag = "und",
            certainty = LanguageCertainty.CONFLICT,
            evidenceKind = LanguageEvidenceKind.valueOf(originalLanguageEvidenceKind),
        )
    } else if (originalLanguageTag == null) {
        LanguageProjectionContract(
            dimension = LanguageDimension.ORIGINAL,
            tag = "und",
            certainty = LanguageCertainty.UNKNOWN,
            evidenceKind = LanguageEvidenceKind.UNKNOWN,
        )
    } else {
        CreatorArchiveV2Policy.projectLanguage(
            LanguageDimension.ORIGINAL,
            listOf(
                LanguageAssertionContract(
                    dimension = LanguageDimension.ORIGINAL,
                    tag = originalLanguageTag,
                    confidence = originalLanguageConfidence ?: 0.0,
                    evidenceKind = LanguageEvidenceKind.valueOf(checkNotNull(originalLanguageEvidenceKind)),
                ),
            ),
        )
    }
    val decision = decisionWorkId?.let { workId ->
        WorkDecisionProjection(
            workId = workId,
            workPortableKey = checkNotNull(decisionWorkPortableKey),
            workTitle = checkNotNull(decisionWorkTitle),
            decision = WorkDecisionContract(
                state = WorkDecisionState.valueOf(checkNotNull(decisionState)),
                actor = DecisionActor.valueOf(checkNotNull(decisionActor)),
                explicit = checkNotNull(decisionExplicit),
            ),
            score = decisionScore,
            evidence = checkNotNull(decisionEvidence),
            decidedAt = checkNotNull(decisionDecidedAt),
        )
    }
    return CreatorWorkArchiveRow(
        version = SourceWorkArchiveVersion(
            sourceWorkId = sourceWorkId,
            naturalKey = SourceWorkNaturalKey(sourceId, stableSourceUrl),
            mangaId = mangaId,
            title = title,
            readingLanguage = language,
            chapterCount = chapterCount,
            inLibrary = inLibrary != 0L,
            detailsFetchedAt = detailsFetchedAt,
            lastSeenAt = lastSeenAt,
            decision = decision,
            originalLanguage = originalLanguage,
            lastCheckResult = lastCheckResult?.let(SourceCheckpointResult::valueOf),
            consecutiveFailures = consecutiveFailures,
            lastSuccessAt = lastSuccessAt,
        ),
        canonicalWorkId = canonicalWorkId,
        canonicalPortableKey = canonicalPortableKey,
        canonicalTitle = canonicalTitle,
    )
}

private fun List<CreatorWorkArchiveRow>.toCreatorWorkArchive(): CreatorWorkArchive {
    val grouped = filter { it.canonicalWorkId != null }.groupBy(CreatorWorkArchiveRow::canonicalWorkId)
    val works = grouped.values.map { versions ->
        val first = versions.first()
        CanonicalWorkArchiveGroup(
            workId = checkNotNull(first.canonicalWorkId),
            portableKey = checkNotNull(first.canonicalPortableKey),
            title = checkNotNull(first.canonicalTitle),
            versions = versions.map(CreatorWorkArchiveRow::version),
        )
    }
    val ungrouped = filter { it.canonicalWorkId == null }.map(CreatorWorkArchiveRow::version)
    return CreatorWorkArchive(
        works = works,
        pending = ungrouped.filter { it.decision?.decision?.state != WorkDecisionState.REJECTED },
        rejected = ungrouped.filter { it.decision?.decision?.state == WorkDecisionState.REJECTED },
    )
}

private fun mapDueWatchSource(
    creatorId: Long,
    sourceId: Long,
    baselineState: String,
    baselineGeneration: Long,
    nextDueAt: Long?,
) = DueWatchSource(
    creatorId = creatorId,
    sourceId = sourceId,
    baselineState = WatchBaselineState.valueOf(baselineState),
    baselineGeneration = baselineGeneration,
    nextDueAt = nextDueAt,
)

private fun mapWatchSourceBaseline(
    sourceId: Long,
    baselineState: String,
    baselineGeneration: Long,
) = WatchSourceBaseline(
    sourceId = sourceId,
    baselineState = WatchBaselineState.valueOf(baselineState),
    baselineGeneration = baselineGeneration,
)

private fun mapDiscoveryRun(
    runKey: String,
    creatorId: Long,
    state: String,
    completedSources: Long,
    totalSources: Long,
    truncated: Boolean,
    errorCode: String?,
    errorMessage: String?,
    queuedAt: Long,
    startedAt: Long?,
    finishedAt: Long?,
) = DiscoveryRun(
    runKey = runKey,
    creatorId = creatorId,
    state = DiscoveryRunState.valueOf(state),
    completedSources = completedSources,
    totalSources = totalSources,
    truncated = truncated,
    errorCode = errorCode,
    errorMessage = errorMessage,
    queuedAt = queuedAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
)

private fun mapSourceCheckpoint(
    creatorId: Long,
    sourceId: Long,
    cursor: String?,
    resultState: String,
    consecutiveFailures: Long,
    backoffUntil: Long?,
    lastCheckedAt: Long?,
    lastSuccessAt: Long?,
    errorCode: String?,
    errorMessage: String?,
) = SourceCheckpoint(
    creatorId = creatorId,
    sourceId = sourceId,
    cursor = cursor,
    result = SourceCheckpointResult.valueOf(resultState),
    consecutiveFailures = consecutiveFailures,
    backoffUntil = backoffUntil,
    lastCheckedAt = lastCheckedAt,
    lastSuccessAt = lastSuccessAt,
    errorCode = errorCode,
    errorMessage = errorMessage,
)

private fun mapArchiveDiscovery(
    id: Long,
    creatorId: Long,
    sourceId: Long,
    stableSourceUrl: String,
    title: String,
    kind: String,
    reason: String,
    baselineGeneration: Long,
    readState: String,
    reviewDisposition: String,
    deliveryState: String,
    firstDiscoveredAt: Long,
    lastModifiedAt: Long,
) = ArchiveDiscovery(
    id = id,
    creatorId = creatorId,
    sourceWork = SourceWorkNaturalKey(sourceId, stableSourceUrl),
    title = title,
    kind = DiscoveryKind.valueOf(kind),
    reason = reason,
    baselineGeneration = baselineGeneration,
    state = DiscoveryStateVector(
        readState = DiscoveryReadState.valueOf(readState),
        reviewDisposition = ReviewDisposition.valueOf(reviewDisposition),
        deliveryState = NotificationDeliveryState.valueOf(deliveryState),
    ),
    firstDiscoveredAt = firstDiscoveredAt,
    lastModifiedAt = lastModifiedAt,
)

private fun mapOutboxItem(
    id: Long,
    discoveryId: Long,
    channel: String,
    idempotencyKey: String,
    attemptCount: Long,
    state: String,
    lastError: String?,
    nextAttemptAt: Long?,
    createdAt: Long,
    lastAttemptAt: Long?,
    deliveredAt: Long?,
) = NotificationOutboxItem(
    id = id,
    discoveryId = discoveryId,
    channel = channel,
    idempotencyKey = idempotencyKey,
    attemptCount = attemptCount,
    state = NotificationDeliveryState.valueOf(state),
    lastError = lastError,
    nextAttemptAt = nextAttemptAt,
    createdAt = createdAt,
    lastAttemptAt = lastAttemptAt,
    deliveredAt = deliveredAt,
)

internal fun Database.reconcileArchiveCanonicalVersion(sourceWorkId: Long) {
    val binding = author_archiveQueries
        .getArchiveWorkDecisionsForBinding(sourceWorkId)
        .executeAsList()
        .distinctBy { it.work_id }
        .firstOrNull { it.state == WorkDecisionState.CONFIRMED.name }
    if (binding == null) {
        author_archiveQueries.deleteArchiveCanonicalVersion(sourceWorkId)
        return
    }
    author_archiveQueries.upsertArchiveCanonicalVersion(
        workId = binding.work_id,
        sourceWorkId = sourceWorkId,
        confirmation = if (binding.actor == DecisionActor.RESTORE.name) "RESTORED" else "CONFIRMED",
        evidence = binding.evidence,
        confirmedAt = binding.decided_at,
    )
}
