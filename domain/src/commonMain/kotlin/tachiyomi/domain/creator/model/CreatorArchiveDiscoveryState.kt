package tachiyomi.domain.creator.model

data class ArchiveWatchPolicy(
    val creatorId: Long,
    val enabled: Boolean,
    val periodMillis: Long,
    val sourceIds: Set<Long>,
    val readingLanguageTags: Set<String>,
    val includeProbable: Boolean = false,
    val includeUnknown: Boolean = false,
    val notifyProbable: Boolean = false,
    val notifyUnknown: Boolean = false,
)

data class DueWatchSource(
    val creatorId: Long,
    val sourceId: Long,
    val baselineState: WatchBaselineState,
    val baselineGeneration: Long,
    val nextDueAt: Long?,
)

/** Persistent per-source baseline of a watch; used by the discovery executor before every run. */
data class WatchSourceBaseline(
    val sourceId: Long,
    val baselineState: WatchBaselineState,
    val baselineGeneration: Long,
)

data class DiscoveryRun(
    val runKey: String,
    val creatorId: Long,
    val state: DiscoveryRunState,
    val completedSources: Long,
    val totalSources: Long,
    val truncated: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val queuedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)

enum class SourceCheckpointResult {
    NEVER,
    SUCCESS,
    EMPTY,
    TRUNCATED,
    FAILED,
    CANCELLED,
}

data class SourceCheckpointUpdate(
    val creatorId: Long,
    val sourceId: Long,
    val cursor: String?,
    val result: SourceCheckpointResult,
    val consecutiveFailures: Long,
    val backoffUntil: Long?,
    val checkedAt: Long,
    val successAt: Long?,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val nextDueAt: Long?,
    val baselineState: WatchBaselineState,
    val baselineGeneration: Long,
)

data class SourceCheckpoint(
    val creatorId: Long,
    val sourceId: Long,
    val cursor: String?,
    val result: SourceCheckpointResult,
    val consecutiveFailures: Long,
    val backoffUntil: Long?,
    val lastCheckedAt: Long?,
    val lastSuccessAt: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

data class DiscoveryCommit(
    val creatorId: Long,
    val sourceWork: SourceWorkNaturalKey,
    val kind: DiscoveryKind,
    val reason: String,
    val baselineGeneration: Long,
    val discoveredAt: Long,
    val outboxChannel: String,
    val idempotencyKey: String,
)

/**
 * One source-work observation committed by the discovery executor.
 *
 * Source metadata, language evidence, creator relation and the optional discovery/outbox are one
 * source-level transaction. This prevents a failed source from leaving a half-written observation.
 */
data class SourceDiscoveryObservation(
    val sourceWork: SourceWorkNaturalKey,
    val title: String,
    val authorText: String?,
    val artistText: String?,
    val thumbnailUrl: String?,
    val detailsFetchedAt: Long,
    val creatorId: Long,
    val role: CreatorRole,
    val order: Long,
    val origin: CreatorRelationOrigin,
    val verification: CreatorRelationVerification,
    val sourceText: String?,
    val confidence: Double,
    val relationEvidence: String,
    val languageAssertion: LanguageAssertionContract,
    val languageActor: DecisionActor,
    val languageEvidencePayload: String,
    val languageAlgorithmVersion: String?,
    val languageAssertedAt: Long,
    val languageIdempotencyKey: String,
    val notificationsEnabled: Boolean,
    val baselineState: WatchBaselineState,
    val discoveryReason: String,
    val baselineGeneration: Long,
    val discoveredAt: Long,
    val outboxChannel: String,
    val discoveryIdempotencyKey: String,
    val originalLanguageAssertion: LanguageAssertionContract? = null,
    val originalLanguageIdempotencyKey: String? = null,
    val requiresActiveWatch: Boolean = false,
)

data class SourceDiscoveryObservationResult(
    val relation: ArchiveUpsertOutcome<SourceWorkNaturalKey>,
    val discovery: ArchiveDiscovery?,
)

data class ArchiveDiscovery(
    val id: Long,
    val creatorId: Long,
    val sourceWork: SourceWorkNaturalKey,
    val title: String,
    val kind: DiscoveryKind,
    val reason: String,
    val baselineGeneration: Long,
    val state: DiscoveryStateVector,
    val firstDiscoveredAt: Long,
    val lastModifiedAt: Long,
    val readingLanguage: LanguageProjectionContract = LanguageProjectionContract(
        dimension = LanguageDimension.READING,
        tag = "und",
        certainty = LanguageCertainty.UNKNOWN,
        evidenceKind = LanguageEvidenceKind.UNKNOWN,
    ),
)

data class NotificationOutboxItem(
    val id: Long,
    val discoveryId: Long,
    val channel: String,
    val idempotencyKey: String,
    val attemptCount: Long,
    val state: NotificationDeliveryState,
    val lastError: String?,
    val nextAttemptAt: Long?,
    val createdAt: Long,
    val lastAttemptAt: Long?,
    val deliveredAt: Long?,
)
