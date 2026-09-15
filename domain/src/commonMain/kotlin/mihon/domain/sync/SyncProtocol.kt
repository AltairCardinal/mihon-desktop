package mihon.domain.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

object SyncProtocol {
    const val CURRENT_VERSION = 1
    const val MAX_EVENTS_PER_BATCH = 256
    const val MAX_PLAINTEXT_BYTES_PER_BATCH = 512 * 1024
    const val MAX_EFFECTS_PER_EVENT = 32
    const val MAX_PARENTS_PER_EFFECT = 64
}

@Serializable
enum class SyncCategory {
    FAVORITE,
    FOLLOW,
    READING,
}

@Serializable
enum class SyncObjectType {
    MANGA,
    CHAPTER,
    AUTHOR,
}

@Serializable
enum class SyncField {
    FAVORITE,
    FOLLOWING,
    READ_STATUS,
    RESUME_POSITION,
    READING_SUMMARY,
}

@Serializable
enum class SyncEffectKind {
    ADD,
    REMOVE,
    MARK_READ,
    MARK_UNREAD,
    RESUME_POSITION,
    READING_SUMMARY,
}

@Serializable
enum class SyncOrigin {
    USER,
    INITIAL_IMPORT,
    BACKUP_RESTORE,
    MIGRATION,
    METADATA_REFRESH,
    REMOTE_SYNC,
}

@Serializable
data class SyncObjectKey(
    val type: SyncObjectType,
    val sourceId: String? = null,
    val portableKey: String? = null,
    val originalUrl: String? = null,
    val parentUrl: String? = null,
) {
    val stableKey: String
        get() = listOf(type.name, sourceId, portableKey, originalUrl, parentUrl)
            .joinToString(separator = "") { value ->
                val text = value.orEmpty()
                text.length.toString() + ":" + text
            }
}

@Serializable
data class SyncEventId(
    val actorId: String,
    val epoch: Long,
    val seq: Long,
) {
    val stableKey: String
        get() = actorId + ":" + epoch + ":" + seq
}

@Serializable
data class SyncEffectRef(
    val eventId: SyncEventId,
    val effectId: String,
    val spaceId: String? = null,
    val generation: Long? = null,
) {
    val stableKey: String
        get() = eventId.stableKey + ":" + effectId
}

@Serializable
data class SyncEffect(
    val effectId: String,
    val objectKey: SyncObjectKey,
    val field: SyncField,
    val kind: SyncEffectKind,
    val parents: List<SyncEffectRef> = emptyList(),
    val payload: JsonObject = buildJsonObject {},
) {
    fun ref(event: SyncEventEnvelope): SyncEffectRef =
        SyncEffectRef(event.eventId, effectId, event.spaceId, event.generation)
}

@Serializable
data class SyncEventEnvelope(
    val protocolVersion: Int,
    val spaceId: String,
    val generation: Long,
    val actorId: String,
    val epoch: Long,
    val seq: Long,
    val category: SyncCategory,
    val effects: List<SyncEffect>,
    val origin: SyncOrigin,
    val importId: String? = null,
    val occurredAt: Long = 0,
    val batchId: String? = null,
    val contentDigest: String? = null,
) {
    val eventId: SyncEventId
        get() = SyncEventId(actorId, epoch, seq)

    fun ref(effectId: String): SyncEffectRef =
        SyncEffectRef(eventId, effectId, spaceId, generation)
}

@Serializable
data class SyncBatch(
    val protocolVersion: Int,
    val spaceId: String,
    val generation: Long,
    val batchId: String,
    val events: List<SyncEventEnvelope>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val objects: List<SyncObjectDescriptor> = emptyList(),
)

/** Display and reconstruction data; never a preference or an additional user operation. */
@Serializable
data class SyncObjectDescriptor(
    val objectKey: SyncObjectKey,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val chapterNumber: Double? = null,
    val sourceOrder: Long? = null,
    val scanlator: String? = null,
)

data class SyncFieldKey(
    val objectKey: SyncObjectKey,
    val field: SyncField,
)

enum class SyncReadStatus {
    READ,
    UNREAD,
}

enum class SyncRejectionReason {
    MALFORMED,
    EMPTY_FIELD,
    UNKNOWN_PROTOCOL,
    WRONG_SPACE,
    WRONG_GENERATION,
    INVALID_ACTOR,
    INVALID_EPOCH,
    INVALID_SEQUENCE,
    INVALID_OBJECT_IDENTITY,
    INVALID_CATEGORY,
    INVALID_EFFECT,
    INVALID_PARENT,
    DUPLICATE_EFFECT_ID,
    DUPLICATE_PARENT,
    PARENT_FOREIGN_SPACE,
    PARENT_FOREIGN_GENERATION,
    MISSING_PARENT,
    MISSING_PARENT_EFFECT,
    CROSS_FIELD_PARENT,
    CAUSAL_CYCLE,
    SAME_ID_DIFFERENT_CONTENT,
    UNKNOWN_PAYLOAD_FIELD,
    INVALID_PAYLOAD,
    INVALID_EFFECT_COMBINATION,
    BATCH_TOO_LARGE,
    TOO_MANY_EFFECTS,
    PAYLOAD_TOO_LARGE,
}

data class SyncRejection(
    val reason: SyncRejectionReason,
    val message: String,
    val eventId: SyncEventId? = null,
    val effectRef: SyncEffectRef? = null,
)

sealed interface SyncDecodeResult {
    data class Accepted(val event: SyncEventEnvelope) : SyncDecodeResult
    data class Rejected(
        val reason: SyncRejectionReason,
        val message: String,
        val eventId: SyncEventId? = null,
    ) : SyncDecodeResult
}

sealed interface SyncBatchDecodeResult {
    data class Accepted(val batch: SyncBatch) : SyncBatchDecodeResult
    data class Rejected(
        val reason: SyncRejectionReason,
        val message: String,
    ) : SyncBatchDecodeResult
}

enum class SyncIngestStatus {
    ACCEPTED,
    DUPLICATE,
    PENDING_DEPENDENCY,
    REJECTED,
}

data class SyncIngestResult(
    val status: SyncIngestStatus,
    val event: SyncEventEnvelope,
    val rejections: List<SyncRejection> = emptyList(),
)

data class SyncProjection(
    val key: SyncFieldKey,
    val heads: List<SyncEffectRef>,
    val effects: List<SyncEffect>,
    val metadata: Map<SyncEffectRef, SyncEffectMetadata> = emptyMap(),
    val effectsByRef: Map<SyncEffectRef, SyncEffect> = emptyMap(),
    val value: Boolean? = null,
    val readStatus: SyncReadStatus? = null,
    val conflict: Boolean = false,
    val pending: Boolean = false,
)

data class SyncEffectMetadata(
    val occurredAt: Long,
    val origin: SyncOrigin,
)

data class SyncReduction(
    val events: Map<SyncEventId, SyncEventEnvelope>,
    val rejections: List<SyncRejection>,
    val pendingDependencies: List<SyncEffectRef>,
    val projections: Map<SyncFieldKey, SyncProjection>,
) {
    fun projection(objectKey: SyncObjectKey, field: SyncField): SyncProjection =
        projections[SyncFieldKey(objectKey, field)]
            ?: SyncProjection(SyncFieldKey(objectKey, field), emptyList(), emptyList())
}

enum class SyncCancellationDecision {
    CONFIRM,
    KEEP_LOCAL,
}

data class SyncCancellationRequest(
    val projection: SyncProjection,
    val effectRef: SyncEffectRef,
    val objectKey: SyncObjectKey,
    val field: SyncField,
    val expectedHeads: List<SyncEffectRef>,
    val binding: String,
)

enum class SyncReceiverDecisionResult {
    APPLIED,
    KEPT_LOCAL,
    INVALIDATED,
    NOT_APPLICABLE,
}

data class SyncReceiverDecision(
    val result: SyncReceiverDecisionResult,
    val decision: SyncCancellationDecision,
    val localValue: Boolean,
    val binding: String,
    val emittedEvents: List<SyncEventEnvelope> = emptyList(),
)

data class SyncReadingSession(
    val objectKey: SyncObjectKey,
    val chapterKey: String,
    val pageIndex: Int,
    val mode: String,
)

data class SyncResumePosition(
    val effectRef: SyncEffectRef,
    val chapterKey: String,
    val pageIndex: Int,
    val occurredAt: Long,
    val origin: SyncOrigin,
)

data class SyncReadingChoice(
    val nextPosition: SyncResumePosition?,
    val history: List<SyncResumePosition>,
    val activeSession: SyncReadingSession,
    val requiresAdoption: Boolean,
)
