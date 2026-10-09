package tachiyomi.domain.creator.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.creator.model.AddCreatorAliasesRequest
import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUnreadWork
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.CreatorAliasCandidates
import tachiyomi.domain.creator.model.CreatorArchiveV2Policy
import tachiyomi.domain.creator.model.CreatorCardProjectionPage
import tachiyomi.domain.creator.model.CreatorIdentityOption
import tachiyomi.domain.creator.model.CreatorIdentitySnapshot
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryCommitPlan
import tachiyomi.domain.creator.model.DiscoveryLease
import tachiyomi.domain.creator.model.DiscoveryRun
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.DueWatchSource
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.LeaseAcquireResult
import tachiyomi.domain.creator.model.NewCanonicalWorkDecision
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.NotificationOutboxItem
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SetCreatorDisplayNameRequest
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceCheckpointUpdate
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.model.SourceDateQualitySnapshot
import tachiyomi.domain.creator.model.SourceDiscoveryObservation
import tachiyomi.domain.creator.model.SourceDiscoveryObservationResult
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WatchSourceBaseline
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionProjection
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.service.ChapterVariantRecord
import tachiyomi.domain.creator.service.WorkTitleNormalizer
import tachiyomi.domain.manga.model.Manga

/**
 * Typed mutation boundary for the v2 author archive.
 *
 * Legacy candidate-shaped APIs remain temporarily available through [CreatorRepository], but new
 * indexing and discovery code must use this natural-key contract.
 */
interface CreatorLibraryIndexWriter {
    suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>)

    suspend fun indexLibraryManga(manga: Manga, mentions: List<CreatorMention>) {
        indexLibraryMangaBatch(listOf(CreatorLibraryIndexEntry(manga, mentions)))
    }

    suspend fun removeLibraryMangaIndex(mangaId: Long)

    suspend fun removeStaleLibraryMangaIndexes()
}

object NoopCreatorLibraryIndexWriter : CreatorLibraryIndexWriter {
    override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) = Unit

    override suspend fun removeLibraryMangaIndex(mangaId: Long) = Unit

    override suspend fun removeStaleLibraryMangaIndexes() = Unit
}

interface CreatorLibraryMangaSource {
    suspend fun countLibraryMangaForCreatorIndex(): Long

    suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga>
}

interface CreatorArchiveRepository : CreatorLibraryIndexWriter {

    /** Exact source identity, with only a same-manga legacy identity accepted as fallback. */
    suspend fun getSourceWorkCatalog(
        sourceWork: SourceWorkNaturalKey,
        mangaId: Long,
    ): tachiyomi.domain.creator.model.SourceWorkCatalogObservation? = null

    suspend fun getCreatorCardProjectionPage(
        offset: Int,
        limit: Int,
        followedOnly: Boolean,
        preferredLanguages: Set<String> = emptySet(),
        customCoverExists: (Long) -> Boolean = { false },
        query: String = "",
        preferredDisplayScript: WorkTitleNormalizer.DisplayScript? = null,
    ): CreatorCardProjectionPage = throw UnsupportedOperationException("Creator card projection is not implemented")

    /** Device-local presentation corrections; these facts are not part of backup or sync. */
    suspend fun getPresentationExclusions(creatorRootId: Long): Set<SourceWorkNaturalKey> = emptySet()

    suspend fun setPresentationExclusion(
        creatorRootId: Long,
        sourceWork: SourceWorkNaturalKey,
        excluded: Boolean,
        now: Long,
    ) = Unit

    /**
     * Imports only source keys that can be resolved in the archive and returns those keys so the
     * caller can remove exactly the migrated preference records. Unresolved or malformed legacy
     * values remain in the preference store for a later retry.
     */
    suspend fun importPresentationExclusions(
        entries: Map<Long, Set<SourceWorkNaturalKey>>,
    ): Map<Long, Set<SourceWorkNaturalKey>> = emptyMap()

    suspend fun getIdentitySnapshot(creatorId: Long): CreatorIdentitySnapshot =
        throw UnsupportedOperationException("Identity editor is not implemented")

    fun observeIdentitySnapshot(creatorId: Long): Flow<CreatorIdentitySnapshot> =
        throw UnsupportedOperationException("Identity editor is not implemented")

    suspend fun getAliasCandidates(creatorId: Long): CreatorAliasCandidates =
        throw UnsupportedOperationException("Identity editor is not implemented")

    suspend fun addCreatorAliases(request: AddCreatorAliasesRequest): CreatorIdentitySnapshot =
        throw UnsupportedOperationException("Identity editor is not implemented")

    suspend fun setCreatorDisplayName(request: SetCreatorDisplayNameRequest): CreatorIdentitySnapshot =
        throw UnsupportedOperationException("Identity editor is not implemented")

    suspend fun resolveCreatorIdByExactName(name: String): Long?

    fun observeCreatorIdByExactName(name: String): Flow<Long?>

    suspend fun getCreatorWorkArchive(creatorId: Long): CreatorWorkArchive

    fun observeCreatorWorkArchive(creatorId: Long): Flow<CreatorWorkArchive>

    suspend fun replaceChapterVariants(
        sourceWork: SourceWorkNaturalKey,
        variants: List<ChapterVariantRecord>,
        now: Long,
    )

    suspend fun getChapterVariants(sourceWork: SourceWorkNaturalKey): List<ChapterVariantRecord>

    /** Persist one real catalogue observation without changing the source work's first-seen fact. */
    suspend fun updateSourceWorkCatalog(
        sourceWork: SourceWorkNaturalKey,
        chapterCount: Long,
        completeness: ChapterCatalogCompleteness,
        latestChapterAt: Long?,
        observedAt: Long,
        mangaId: Long? = null,
    )

    /** Records bounded, device-local evidence for source supplied dates. */
    suspend fun recordSourceDateQualityObservations(
        observations: List<SourceDateObservation>,
        now: Long = observations.maxOfOrNull(SourceDateObservation::observedAt) ?: 0L,
    ): SourceDateQualitySnapshot? = null

    suspend fun getSourceDateQualitySnapshot(
        identity: SourceDateQualityIdentity,
    ): SourceDateQualitySnapshot? = null

    suspend fun upsertWatchPolicy(policy: ArchiveWatchPolicy, now: Long)

    suspend fun getWatchPolicy(creatorId: Long): ArchiveWatchPolicy?

    suspend fun getDueWatchSources(now: Long, limit: Long): List<DueWatchSource>

    suspend fun acquireWatchLease(
        creatorId: Long,
        ownerToken: String,
        expiresAt: Long,
        now: Long,
    ): LeaseAcquireResult

    suspend fun releaseWatchLease(creatorId: Long, ownerToken: String, now: Long)

    suspend fun createDiscoveryRun(runKey: String, creatorId: Long, totalSources: Long, queuedAt: Long): DiscoveryRun

    suspend fun getRecoverableDiscoveryRuns(): List<DiscoveryRun>

    suspend fun updateDiscoveryRun(
        runKey: String,
        state: DiscoveryRunState,
        completedSources: Long,
        truncated: Boolean,
        errorCode: String?,
        errorMessage: String?,
        occurredAt: Long,
    )

    suspend fun updateSourceCheckpoint(update: SourceCheckpointUpdate)

    suspend fun getSourceCheckpoints(creatorId: Long): List<SourceCheckpoint>

    fun observeSourceCheckpoints(creatorId: Long): Flow<List<SourceCheckpoint>>

    /** Persistent per-source baseline state for a watch, used to plan event creation. */
    suspend fun getWatchSourceBaselines(creatorId: Long): List<WatchSourceBaseline>

    /** True when the (source, url) already maps to a library or history manga. */
    suspend fun sourceWorkIsInLibraryOrHistory(key: SourceWorkNaturalKey): Boolean

    /** True when the (source, url) is a confirmed version of a canonical work. */
    suspend fun sourceWorkHasConfirmedCanonicalVersion(key: SourceWorkNaturalKey): Boolean

    /** Atomically archives one source result and creates its discovery/outbox when eligible. */
    suspend fun commitSourceDiscoveryObservation(
        observation: SourceDiscoveryObservation,
    ): SourceDiscoveryObservationResult {
        upsertSourceWork(
            sourceId = observation.sourceWork.sourceId,
            stableSourceUrl = observation.sourceWork.stableSourceUrl,
            mangaId = null,
            title = observation.title,
            authorText = observation.authorText,
            artistText = observation.artistText,
            thumbnailUrl = observation.thumbnailUrl,
            detailsFetchedAt = observation.detailsFetchedAt,
        )
        appendLanguageAssertion(
            subject = ArchiveLanguageSubject.SourceWork(observation.sourceWork),
            assertion = observation.languageAssertion,
            actor = observation.languageActor,
            evidencePayload = observation.languageEvidencePayload,
            algorithmVersion = observation.languageAlgorithmVersion,
            assertedAt = observation.languageAssertedAt,
            idempotencyKey = observation.languageIdempotencyKey,
        )
        observation.originalLanguageAssertion?.let { assertion ->
            appendLanguageAssertion(
                subject = ArchiveLanguageSubject.SourceWork(observation.sourceWork),
                assertion = assertion,
                actor = observation.languageActor,
                evidencePayload = observation.languageEvidencePayload,
                algorithmVersion = observation.languageAlgorithmVersion,
                assertedAt = observation.languageAssertedAt,
                idempotencyKey = checkNotNull(observation.originalLanguageIdempotencyKey),
            )
        }
        val relation = upsertSourceWorkCreator(
            sourceWork = observation.sourceWork,
            creatorId = observation.creatorId,
            role = observation.role,
            order = observation.order,
            origin = observation.origin,
            verification = observation.verification,
            sourceText = observation.sourceText,
            confidence = observation.confidence,
            evidence = observation.relationEvidence,
        )
        val plan = if (observation.notificationsEnabled) {
            CreatorArchiveV2Policy.planDiscoveryCommit(
                baselineState = observation.baselineState,
                relationVerification = observation.verification,
                watchRelationOutcome = relation,
                alreadyInLibraryOrHistory = sourceWorkIsInLibraryOrHistory(observation.sourceWork),
                confirmedCanonicalWork = sourceWorkHasConfirmedCanonicalVersion(observation.sourceWork),
                idempotencyKey = observation.discoveryIdempotencyKey,
            )
        } else {
            null
        }
        val discovery = if (plan is DiscoveryCommitPlan.EventWithOutbox) {
            commitDiscovery(
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
        return SourceDiscoveryObservationResult(relation, discovery)
    }

    suspend fun commitDiscovery(commit: DiscoveryCommit): ArchiveDiscovery

    suspend fun getUnreadDiscoveries(limit: Long): List<ArchiveDiscovery>

    /** Returns one unread reminder per canonical/source work, independent of source count. */
    suspend fun getUnreadWorkDiscoveries(limit: Long): List<ArchiveUnreadWork> = emptyList()

    /** Reactive work-level projection used by the author cards and in-app reminder. */
    fun observeUnreadWorkDiscoveries(limit: Long): Flow<List<ArchiveUnreadWork>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    /** Marks a work and all current source-level discovery members as seen atomically. */
    suspend fun markWorkSeen(sourceWork: SourceWorkNaturalKey, now: Long) {
        throw UnsupportedOperationException("Work-level discovery read state is not implemented")
    }

    /** Revalidates the selected version and its current author group in one repository transaction. */
    suspend fun markPresentationGroupSeen(
        creatorId: Long,
        selectedSourceWork: SourceWorkNaturalKey,
        now: Long,
    )

    fun observeUnreadDiscoveries(limit: Long): Flow<List<ArchiveDiscovery>>

    suspend fun getDiscoveries(limit: Long): List<ArchiveDiscovery>

    fun observeDiscoveries(limit: Long): Flow<List<ArchiveDiscovery>>

    suspend fun getDiscovery(discoveryId: Long): ArchiveDiscovery?

    suspend fun markDiscoverySeen(discoveryId: Long, now: Long)

    suspend fun markDiscoveriesSeen(discoveryIds: Set<Long>, now: Long)

    suspend fun setDiscoveryReview(discoveryId: Long, disposition: ReviewDisposition, now: Long)

    suspend fun deleteReviewedDiscoveries(before: Long)

    suspend fun getPendingNotificationOutbox(now: Long, limit: Long): List<NotificationOutboxItem>

    fun observePendingNotificationOutbox(now: Long, limit: Long): Flow<List<NotificationOutboxItem>>

    suspend fun updateNotificationDelivery(
        outboxId: Long,
        state: NotificationDeliveryState,
        error: String?,
        nextAttemptAt: Long?,
        occurredAt: Long,
    )

    suspend fun getCreatorIdentityOptions(
        mangaId: Long,
        mention: CreatorMention,
    ): List<CreatorIdentityOption>

    suspend fun bindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
        creatorId: Long,
    )

    suspend fun createAndBindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
    ): Long

    suspend fun addManualCreatorAlias(creatorId: Long, alias: String)

    suspend fun getManualCreatorAliases(creatorId: Long): List<String>

    suspend fun removeManualCreatorAlias(creatorId: Long, alias: String)

    suspend fun mergeCreatorIdentities(sourceCreatorId: Long, targetCreatorId: Long)

    suspend fun mergeCreatorIdentities(sourceCreatorIds: Set<Long>, targetCreatorId: Long)

    suspend fun splitCreatorIdentity(
        sourceCreatorId: Long,
        mangaIds: Set<Long>,
        newDisplayName: String,
        sourceWorks: Set<SourceWorkNaturalKey> = emptySet(),
    ): Long

    suspend fun upsertSourceWork(
        sourceId: Long,
        stableSourceUrl: String,
        mangaId: Long?,
        title: String,
        authorText: String?,
        artistText: String?,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey>

    suspend fun upsertSourceWorkCreator(
        sourceWork: SourceWorkNaturalKey,
        creatorId: Long,
        role: CreatorRole,
        order: Long,
        origin: CreatorRelationOrigin,
        verification: CreatorRelationVerification,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey>

    suspend fun appendWorkDecision(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        decision: WorkDecisionContract,
        algorithmVersion: String?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<WorkDecisionContract>

    suspend fun getWorkDecisions(sourceWork: SourceWorkNaturalKey): List<WorkDecisionProjection>

    suspend fun appendUserWorkDecisionIfCurrent(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        state: WorkDecisionState,
        expectedDecidedAt: Long?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ): WorkDecisionProjection

    /** Creates one canonical work and confirms all supplied source versions in the same transaction. */
    suspend fun createCanonicalWorkWithUserWorkDecisions(
        primaryTitle: String,
        primaryCreatorId: Long?,
        decisions: List<NewCanonicalWorkDecision>,
    ): CanonicalWork

    suspend fun appendLanguageAssertion(
        subject: ArchiveLanguageSubject,
        assertion: LanguageAssertionContract,
        actor: DecisionActor,
        evidencePayload: String,
        algorithmVersion: String?,
        assertedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<LanguageAssertionContract>

    suspend fun getLanguageProjection(
        subject: ArchiveLanguageSubject,
        dimension: LanguageDimension,
    ): LanguageProjectionContract

    suspend fun setManualLanguage(
        subject: ArchiveLanguageSubject,
        dimension: LanguageDimension,
        languageTag: String,
        now: Long,
    )

    suspend fun withdrawManualLanguage(
        subject: ArchiveLanguageSubject,
        dimension: LanguageDimension,
        now: Long,
    )
}

/**
 * Readiness gate shared by repository calls and platform startup.
 *
 * Implementations must not report ready until the incremental legacy import transaction commits.
 * A failure is propagated so creator reads and mutations fail closed instead of racing stale v1 data.
 */
interface CreatorArchiveBootstrap {
    suspend fun awaitReady()
}

object ReadyCreatorArchiveBootstrap : CreatorArchiveBootstrap {
    override suspend fun awaitReady() = Unit
}
