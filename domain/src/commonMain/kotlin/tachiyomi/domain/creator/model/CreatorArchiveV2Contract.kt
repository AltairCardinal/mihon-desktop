package tachiyomi.domain.creator.model

object CreatorArchiveV2Contract {
    const val CURRENT_SCHEMA_VERSION = 15L
    const val TARGET_SCHEMA_VERSION = 16L
    const val LATEST_SCHEMA_VERSION = 17L
    const val TARGET_MIGRATION = "15.sqm"
    const val BACKUP_ENVELOPE_FIELD = 107
    const val BACKUP_SECTION_VERSION = 1
    const val LEGACY_READ_BRIDGE_REMOVAL_TASK = "AA4-02"
    const val LEGACY_TABLE_REMOVAL_TASK = "AA7-02"
}

enum class ArchiveDeletionPolicy {
    SOFT_DELETE,
    CASCADE,
    SET_NULL_OR_RETAIN,
    RETAIN_HISTORY,
}

data class ArchiveTableContract(
    val name: String,
    val uniqueKeys: Set<String>,
    val deletionPolicy: ArchiveDeletionPolicy,
)

object CreatorArchivePhysicalSchema {
    val tables = listOf(
        table("author_archive_creators", "portable_key", ArchiveDeletionPolicy.SOFT_DELETE),
        table("author_archive_aliases", "creator_id,normalized_alias", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_manga_links", "manga_id,creator_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_source_works", "source_id,stable_source_url", ArchiveDeletionPolicy.SET_NULL_OR_RETAIN),
        table("author_archive_source_work_creators", "source_work_id,creator_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_watches", "creator_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_watch_sources", "watch_id,source_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_watch_result_policies", "watch_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_watch_languages", "policy_id,language_tag", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_runs", "run_key", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_source_checkpoints", "watch_id,source_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_discoveries", "watch_id,source_work_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_canonical_works", "portable_key", ArchiveDeletionPolicy.SOFT_DELETE),
        table("author_archive_canonical_creators", "work_id,creator_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_canonical_versions", "source_work_id", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_work_decisions", "idempotency_key", ArchiveDeletionPolicy.RETAIN_HISTORY),
        table("author_archive_language_assertions", "idempotency_key", ArchiveDeletionPolicy.RETAIN_HISTORY),
        table("author_archive_chapter_variants", "source_work_id,chapter_natural_key", ArchiveDeletionPolicy.CASCADE),
        table("author_archive_notification_outbox", "idempotency_key", ArchiveDeletionPolicy.CASCADE),
        table(
            "author_archive_legacy_import_state",
            "entity_type,legacy_key",
            ArchiveDeletionPolicy.RETAIN_HISTORY,
        ),
    )

    private fun table(
        name: String,
        uniqueKey: String,
        deletionPolicy: ArchiveDeletionPolicy,
    ) = ArchiveTableContract(name, setOf(uniqueKey), deletionPolicy)
}

@JvmInline
value class CreatorPortableKey(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class CanonicalWorkPortableKey(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class SourceWorkNaturalKey(
    val sourceId: Long,
    val stableSourceUrl: String,
) {
    init {
        require(stableSourceUrl.isNotBlank())
    }
}

object CreatorArchiveSubjectKey {
    fun sourceWork(key: SourceWorkNaturalKey): String = "source:${key.sourceId}:${key.stableSourceUrl}"

    fun canonicalWork(key: CanonicalWorkPortableKey): String = "canonical:${key.value}"

    fun creator(key: CreatorPortableKey): String = "creator:${key.value}"
}

object CreatorArchiveLanguageTag {
    fun normalize(value: String): String {
        val raw = value.trim()
        val normalized = raw.lowercase()
        if (raw.uppercase() in NON_LANGUAGE_TAGS || normalized in NON_LANGUAGE_VALUES) return UNKNOWN
        return if (LANGUAGE_TAG.matches(normalized)) normalized else UNKNOWN
    }

    private const val UNKNOWN = "und"
    private val NON_LANGUAGE_TAGS = setOf("BL", "GL", "SF")
    private val NON_LANGUAGE_VALUES = setOf("", "unknown", UNKNOWN)
    private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")
}

sealed interface ArchiveUpsertOutcome<out T> {
    val value: T

    data class Inserted<T>(override val value: T) : ArchiveUpsertOutcome<T>

    data class Updated<T>(override val value: T) : ArchiveUpsertOutcome<T>

    data class Unchanged<T>(override val value: T) : ArchiveUpsertOutcome<T>
}

sealed interface ArchiveAppendOutcome<out T> {
    val value: T

    data class Inserted<T>(override val value: T) : ArchiveAppendOutcome<T>

    data class Unchanged<T>(override val value: T) : ArchiveAppendOutcome<T>

    data class Conflict<T>(
        val existing: T,
        val attempted: T,
    ) : ArchiveAppendOutcome<T> {
        override val value: T = existing
    }
}

enum class CreatorRelationOrigin {
    AUTOMATIC,
    USER,
    MIGRATION,
    RESTORE,
}

sealed interface ArchiveLanguageSubject {
    data class SourceWork(val naturalKey: SourceWorkNaturalKey) : ArchiveLanguageSubject

    data class CanonicalWork(val portableKey: CanonicalWorkPortableKey) : ArchiveLanguageSubject

    data class Creator(val portableKey: CreatorPortableKey) : ArchiveLanguageSubject
}

enum class WatchBaselineState {
    NEEDS_BASELINE,
    BASELINED,
}

enum class DiscoveryReadState {
    UNSEEN,
    SEEN,
}

enum class ReviewDisposition {
    PENDING,
    ACCEPTED,
    IGNORED,
}

enum class CreatorRelationVerification {
    POSSIBLE,
    VERIFIED,
}

enum class NotificationDeliveryState {
    PENDING,
    DELIVERED,
    FAILED,
    CANCELLED,
}

data class DiscoveryStateVector(
    val readState: DiscoveryReadState,
    val reviewDisposition: ReviewDisposition,
    val deliveryState: NotificationDeliveryState,
)

enum class WorkDecisionState {
    SUGGESTED,
    CONFIRMED,
    REJECTED,
}

enum class DecisionActor {
    ALGORITHM,
    USER,
    RESTORE,
}

data class WorkDecisionContract(
    val state: WorkDecisionState,
    val actor: DecisionActor,
    val explicit: Boolean,
)

data class WorkDecisionProjection(
    val workId: Long,
    val workPortableKey: String,
    val workTitle: String,
    val decision: WorkDecisionContract,
    val score: Double?,
    val evidence: String,
    val decidedAt: Long,
)

data class SourceWorkArchiveVersion(
    val sourceWorkId: Long,
    val naturalKey: SourceWorkNaturalKey,
    val mangaId: Long?,
    val title: String,
    val readingLanguage: LanguageProjectionContract,
    val chapterCount: Long,
    val inLibrary: Boolean,
    val detailsFetchedAt: Long?,
    val lastSeenAt: Long,
    val decision: WorkDecisionProjection?,
    val lastCheckResult: SourceCheckpointResult? = null,
    val consecutiveFailures: Long = 0,
    val lastSuccessAt: Long? = null,
)

data class CanonicalWorkArchiveGroup(
    val workId: Long,
    val portableKey: String,
    val title: String,
    val versions: List<SourceWorkArchiveVersion>,
)

data class CreatorWorkArchive(
    val works: List<CanonicalWorkArchiveGroup>,
    val pending: List<SourceWorkArchiveVersion>,
    val rejected: List<SourceWorkArchiveVersion>,
)

class StaleWorkDecisionException : IllegalStateException("The work decision changed while it was being reviewed")

enum class LanguageDimension {
    READING,
    ORIGINAL,
}

enum class LanguageEvidenceKind(val priority: Int) {
    UNKNOWN(0),
    TEXT_DETECTION(1),
    SINGLE_LANGUAGE_SOURCE(2),
    SOURCE_FILTER_OR_TAG(3),
    STRUCTURED_METADATA(4),
    MANUAL(5),
}

enum class LanguageCertainty {
    CONFIRMED,
    PROBABLE,
    UNKNOWN,
    CONFLICT,
}

data class LanguageAssertionContract(
    val dimension: LanguageDimension,
    val tag: String,
    val confidence: Double,
    val evidenceKind: LanguageEvidenceKind,
    val withdrawn: Boolean = false,
) {
    init {
        require(tag.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class LanguageProjectionContract(
    val dimension: LanguageDimension,
    val tag: String,
    val certainty: LanguageCertainty,
    val evidenceKind: LanguageEvidenceKind,
)

data class WatchScopeContract(
    val sourceIds: Set<Long>,
)

data class WatchResultPolicyContract(
    val readingLanguageTags: Set<String>,
    val includeProbable: Boolean = false,
    val includeUnknown: Boolean = false,
)

enum class DiscoveryKind {
    NEW_WORK_CANDIDATE,
    NEW_SOURCE_VERSION,
}

sealed interface DiscoveryCommitPlan {
    data object BaselineArchive : DiscoveryCommitPlan

    data class NoEvent(val reason: NoEventReason) : DiscoveryCommitPlan

    data class EventWithOutbox(
        val kind: DiscoveryKind,
        val idempotencyKey: String,
    ) : DiscoveryCommitPlan
}

enum class NoEventReason {
    POSSIBLE_RELATION,
    EXISTING_WATCH_RELATION,
    ALREADY_IN_LIBRARY_OR_HISTORY,
}

data class DiscoveryLease(
    val ownerToken: String,
    val expiresAtMillis: Long,
) {
    init {
        require(ownerToken.isNotBlank())
    }
}

sealed interface LeaseAcquireResult {
    data class Acquired(val lease: DiscoveryLease) : LeaseAcquireResult

    data class Busy(val current: DiscoveryLease) : LeaseAcquireResult
}

enum class DiscoveryRunState {
    QUEUED,
    RUNNING,
    PARTIAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

object CreatorArchiveV2Policy {
    fun preserveReviewDisposition(
        current: ReviewDisposition,
        @Suppress("UNUSED_PARAMETER") metadataRefreshValue: ReviewDisposition,
    ): ReviewDisposition = current

    fun resolveWorkDecision(
        current: WorkDecisionContract?,
        proposed: WorkDecisionContract,
    ): WorkDecisionContract {
        require(isValidWorkDecision(proposed)) {
            "Invalid work decision: ${proposed.actor} cannot write ${proposed.state} with explicit=${proposed.explicit}"
        }
        if (current == null) return proposed
        if (current.actor == DecisionActor.ALGORITHM) return proposed
        return if (proposed.actor == DecisionActor.USER) proposed else current
    }

    fun projectLanguage(
        dimension: LanguageDimension,
        assertions: List<LanguageAssertionContract>,
        probableThreshold: Double = 0.7,
    ): LanguageProjectionContract {
        val candidates = assertions.filter { it.dimension == dimension && !it.withdrawn }
        if (candidates.isEmpty()) {
            return LanguageProjectionContract(
                dimension = dimension,
                tag = "und",
                certainty = LanguageCertainty.UNKNOWN,
                evidenceKind = LanguageEvidenceKind.UNKNOWN,
            )
        }
        val priority = candidates.maxOf { it.evidenceKind.priority }
        val strongest = candidates.filter { it.evidenceKind.priority == priority }
        val strongestTags = strongest.map { it.tag }.distinct()
        if (strongestTags.size > 1) {
            return LanguageProjectionContract(
                dimension = dimension,
                tag = "und",
                certainty = LanguageCertainty.CONFLICT,
                evidenceKind = strongest.first().evidenceKind,
            )
        }
        val assertion = strongest.maxBy { it.confidence }
        val certainty = when {
            assertion.tag == "und" -> LanguageCertainty.UNKNOWN
            assertion.evidenceKind == LanguageEvidenceKind.MANUAL ||
                assertion.evidenceKind == LanguageEvidenceKind.STRUCTURED_METADATA -> LanguageCertainty.CONFIRMED
            assertion.confidence >= probableThreshold -> LanguageCertainty.PROBABLE
            else -> LanguageCertainty.UNKNOWN
        }
        return LanguageProjectionContract(
            dimension = dimension,
            tag = if (certainty == LanguageCertainty.UNKNOWN) "und" else assertion.tag,
            certainty = certainty,
            evidenceKind = assertion.evidenceKind,
        )
    }

    fun planDiscoveryCommit(
        baselineState: WatchBaselineState,
        relationVerification: CreatorRelationVerification,
        watchRelationOutcome: ArchiveUpsertOutcome<SourceWorkNaturalKey>,
        alreadyInLibraryOrHistory: Boolean,
        confirmedCanonicalWork: Boolean,
        idempotencyKey: String,
    ): DiscoveryCommitPlan {
        if (baselineState == WatchBaselineState.NEEDS_BASELINE) return DiscoveryCommitPlan.BaselineArchive
        if (relationVerification == CreatorRelationVerification.POSSIBLE) {
            return DiscoveryCommitPlan.NoEvent(NoEventReason.POSSIBLE_RELATION)
        }
        if (watchRelationOutcome !is ArchiveUpsertOutcome.Inserted) {
            return DiscoveryCommitPlan.NoEvent(NoEventReason.EXISTING_WATCH_RELATION)
        }
        if (alreadyInLibraryOrHistory) {
            return DiscoveryCommitPlan.NoEvent(NoEventReason.ALREADY_IN_LIBRARY_OR_HISTORY)
        }
        require(idempotencyKey.isNotBlank())
        return DiscoveryCommitPlan.EventWithOutbox(
            kind = if (confirmedCanonicalWork) {
                DiscoveryKind.NEW_SOURCE_VERSION
            } else {
                DiscoveryKind.NEW_WORK_CANDIDATE
            },
            idempotencyKey = idempotencyKey,
        )
    }

    fun acquireLease(
        current: DiscoveryLease?,
        requested: DiscoveryLease,
        nowMillis: Long,
    ): LeaseAcquireResult {
        return if (
            current == null ||
            current.expiresAtMillis <= nowMillis ||
            current.ownerToken == requested.ownerToken
        ) {
            LeaseAcquireResult.Acquired(requested)
        } else {
            LeaseAcquireResult.Busy(current)
        }
    }

    fun canTransitionBaseline(
        from: WatchBaselineState,
        to: WatchBaselineState,
        sourceScopeChanged: Boolean,
    ): Boolean = when (from) {
        WatchBaselineState.NEEDS_BASELINE -> to == WatchBaselineState.BASELINED
        WatchBaselineState.BASELINED -> to == WatchBaselineState.NEEDS_BASELINE && sourceScopeChanged
    }

    fun canTransitionRead(from: DiscoveryReadState, to: DiscoveryReadState): Boolean =
        from == DiscoveryReadState.UNSEEN && to == DiscoveryReadState.SEEN

    fun canTransitionReview(
        from: ReviewDisposition,
        to: ReviewDisposition,
        explicitUserAction: Boolean,
    ): Boolean {
        if (!explicitUserAction) return false
        return when (from) {
            ReviewDisposition.PENDING -> to == ReviewDisposition.ACCEPTED || to == ReviewDisposition.IGNORED
            ReviewDisposition.ACCEPTED,
            ReviewDisposition.IGNORED,
            -> to == ReviewDisposition.PENDING
        }
    }

    fun canTransitionDelivery(
        from: NotificationDeliveryState,
        to: NotificationDeliveryState,
    ): Boolean = when (from) {
        NotificationDeliveryState.PENDING -> to in setOf(
            NotificationDeliveryState.DELIVERED,
            NotificationDeliveryState.FAILED,
            NotificationDeliveryState.CANCELLED,
        )
        NotificationDeliveryState.FAILED ->
            to == NotificationDeliveryState.PENDING ||
                to == NotificationDeliveryState.CANCELLED
        NotificationDeliveryState.DELIVERED,
        NotificationDeliveryState.CANCELLED,
        -> false
    }

    fun canTransitionRun(from: DiscoveryRunState, to: DiscoveryRunState): Boolean = when (from) {
        DiscoveryRunState.QUEUED -> to == DiscoveryRunState.RUNNING || to == DiscoveryRunState.CANCELLED
        DiscoveryRunState.RUNNING -> to in terminalRunStates
        DiscoveryRunState.PARTIAL,
        DiscoveryRunState.SUCCEEDED,
        DiscoveryRunState.FAILED,
        DiscoveryRunState.CANCELLED,
        -> false
    }

    private val terminalRunStates = setOf(
        DiscoveryRunState.PARTIAL,
        DiscoveryRunState.SUCCEEDED,
        DiscoveryRunState.FAILED,
        DiscoveryRunState.CANCELLED,
    )

    private fun isValidWorkDecision(decision: WorkDecisionContract): Boolean = when (decision.actor) {
        DecisionActor.ALGORITHM -> decision.state == WorkDecisionState.SUGGESTED && !decision.explicit
        DecisionActor.USER -> decision.explicit
        DecisionActor.RESTORE -> decision.explicit && decision.state != WorkDecisionState.SUGGESTED
    }
}
