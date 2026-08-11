package tachiyomi.domain.creator.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorIdentityOption
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryLease
import tachiyomi.domain.creator.model.DiscoveryRun
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.DueWatchSource
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LeaseAcquireResult
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.NotificationOutboxItem
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceCheckpointUpdate
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
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

    suspend fun commitDiscovery(commit: DiscoveryCommit): ArchiveDiscovery

    suspend fun getUnreadDiscoveries(limit: Long): List<ArchiveDiscovery>

    fun observeUnreadDiscoveries(limit: Long): Flow<List<ArchiveDiscovery>>

    suspend fun markDiscoverySeen(discoveryId: Long, now: Long)

    suspend fun setDiscoveryReview(discoveryId: Long, disposition: ReviewDisposition, now: Long)

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

    suspend fun appendLanguageAssertion(
        subject: ArchiveLanguageSubject,
        assertion: LanguageAssertionContract,
        actor: DecisionActor,
        evidencePayload: String,
        algorithmVersion: String?,
        assertedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<LanguageAssertionContract>
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
