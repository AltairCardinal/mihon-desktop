package mihon.domain.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

object SyncCodec {
    const val CURRENT_PROTOCOL_VERSION: Int = SyncProtocol.CURRENT_VERSION

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(event: SyncEventEnvelope): String {
        val rejection = SyncValidator.validate(event).firstOrNull()
        require(rejection == null) { rejection?.message.orEmpty() }
        return rawEncode(event)
    }

    fun rawEncode(event: SyncEventEnvelope): String = json.encodeToString(event)

    fun decode(input: String): SyncDecodeResult {
        if (input.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH) {
            return SyncDecodeResult.Rejected(
                SyncRejectionReason.BATCH_TOO_LARGE,
                "event exceeds plaintext limit",
            )
        }
        return try {
            val event = json.decodeFromString<SyncEventEnvelope>(input)
            val rejection = SyncValidator.validate(event).firstOrNull()
            if (rejection == null) {
                SyncDecodeResult.Accepted(event)
            } else {
                SyncDecodeResult.Rejected(rejection.reason, rejection.message, event.eventId)
            }
        } catch (error: SerializationException) {
            SyncDecodeResult.Rejected(SyncRejectionReason.MALFORMED, "malformed sync event")
        } catch (error: IllegalArgumentException) {
            SyncDecodeResult.Rejected(SyncRejectionReason.MALFORMED, "malformed sync event")
        }
    }

    fun canonical(event: SyncEventEnvelope): String =
        canonicalElement(json.encodeToJsonElement(event))

    private fun canonicalElement(element: JsonElement): String = when (element) {
        is JsonObject ->
            element.entries
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") {
                    it.key.length.toString() + ":" + it.key + "=" + canonicalElement(it.value)
                }
        is kotlinx.serialization.json.JsonArray ->
            element.joinToString(prefix = "[", postfix = "]") { canonicalElement(it) }
        is JsonPrimitive -> element.toString()
    }
}

object SyncBatchCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(
        events: List<SyncEventEnvelope>,
        batchId: String,
        spaceId: String,
        generation: Long,
        protocolVersion: Int = SyncProtocol.CURRENT_VERSION,
        objects: List<SyncObjectDescriptor> = emptyList(),
    ): String {
        val batch = SyncBatch(protocolVersion, spaceId, generation, batchId, events, objects)
        val rejection = validate(batch).firstOrNull()
        require(rejection == null) { rejection?.message.orEmpty() }
        return rawEncode(batch)
    }

    fun rawEncode(events: List<SyncEventEnvelope>): String =
        rawEncode(
            SyncBatch(
                protocolVersion = SyncProtocol.CURRENT_VERSION,
                spaceId = events.firstOrNull()?.spaceId.orEmpty(),
                generation = events.firstOrNull()?.generation ?: 0,
                batchId = "raw",
                events = events,
            ),
        )

    fun rawEncode(batch: SyncBatch): String = json.encodeToString(batch)

    fun decode(input: String): SyncBatchDecodeResult {
        if (input.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH) {
            return SyncBatchDecodeResult.Rejected(
                SyncRejectionReason.BATCH_TOO_LARGE,
                "batch exceeds plaintext limit",
            )
        }
        return try {
            val batch = json.decodeFromString<SyncBatch>(input)
            val rejection = validate(batch).firstOrNull()
            if (rejection == null) {
                SyncBatchDecodeResult.Accepted(batch)
            } else {
                SyncBatchDecodeResult.Rejected(rejection.reason, rejection.message)
            }
        } catch (error: SerializationException) {
            SyncBatchDecodeResult.Rejected(SyncRejectionReason.MALFORMED, "malformed sync batch")
        } catch (error: IllegalArgumentException) {
            SyncBatchDecodeResult.Rejected(SyncRejectionReason.MALFORMED, "malformed sync batch")
        }
    }

    fun countByCategory(events: List<SyncEventEnvelope>): Map<SyncCategory, Int> =
        events.groupingBy { it.category }.eachCount()

    private fun validate(batch: SyncBatch): List<SyncRejection> {
        if (batch.protocolVersion != SyncProtocol.CURRENT_VERSION) {
            return listOf(SyncRejection(SyncRejectionReason.UNKNOWN_PROTOCOL, "unsupported batch protocol"))
        }
        if (batch.events.isEmpty() || batch.events.size > SyncProtocol.MAX_EVENTS_PER_BATCH) {
            return listOf(
                SyncRejection(SyncRejectionReason.BATCH_TOO_LARGE, "batch must contain 1..256 events"),
            )
        }
        if (batch.batchId.isBlank() || batch.batchId.length > 128) {
            return listOf(SyncRejection(SyncRejectionReason.EMPTY_FIELD, "batch id is invalid"))
        }
        val eventIds = mutableSetOf<SyncEventId>()
        val results = mutableListOf<SyncRejection>()
        batch.events.forEach { event ->
            if (event.protocolVersion != batch.protocolVersion) {
                results += SyncRejection(
                    SyncRejectionReason.UNKNOWN_PROTOCOL,
                    "event protocol differs from batch",
                    event.eventId,
                )
            }
            if (event.spaceId != batch.spaceId) {
                results += SyncRejection(
                    SyncRejectionReason.WRONG_SPACE,
                    "event space differs from batch",
                    event.eventId,
                )
            }
            if (event.generation != batch.generation) {
                results += SyncRejection(
                    SyncRejectionReason.WRONG_GENERATION,
                    "event generation differs from batch",
                    event.eventId,
                )
            }
            if (!eventIds.add(event.eventId)) {
                results += SyncRejection(
                    SyncRejectionReason.SAME_ID_DIFFERENT_CONTENT,
                    "duplicate event id in batch",
                    event.eventId,
                )
            }
            results += SyncValidator.validate(event)
        }
        results += validateDescriptions(batch)
        if (rawEncode(batch).toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH) {
            results += SyncRejection(SyncRejectionReason.BATCH_TOO_LARGE, "batch exceeds plaintext limit")
        }
        return results
    }

    private fun validateDescriptions(batch: SyncBatch): List<SyncRejection> {
        fun invalid() = listOf(SyncRejection(SyncRejectionReason.INVALID_PAYLOAD, "invalid object descriptions"))
        if (batch.objects.size > SyncProtocol.MAX_EVENTS_PER_BATCH * 3) return invalid()
        val descriptionsByKey = batch.objects.associateBy { it.objectKey.stableKey }
        batch.events.flatMap { it.effects }.forEach { effect ->
            val chapterKey = (effect.payload["chapterKey"] as? JsonPrimitive)?.content
            val describedChapter = descriptionsByKey[chapterKey]?.objectKey ?: return@forEach
            if (describedChapter.type != SyncObjectType.CHAPTER) return invalid()
            if (effect.objectKey.type == SyncObjectType.MANGA) {
                if (describedChapter.sourceId != effect.objectKey.sourceId ||
                    describedChapter.parentUrl != effect.objectKey.originalUrl
                ) {
                    return invalid()
                }
            } else if (describedChapter != effect.objectKey) {
                return invalid()
            }
        }
        val referenced = buildSet {
            batch.events.flatMap { it.effects }.forEach { effect ->
                add(effect.objectKey.stableKey)
                (effect.payload["chapterKey"] as? JsonPrimitive)?.let { add(it.content) }
                if (effect.objectKey.type == SyncObjectType.CHAPTER) {
                    add(
                        SyncObjectKey(
                            SyncObjectType.MANGA,
                            sourceId = effect.objectKey.sourceId,
                            originalUrl = effect.objectKey.parentUrl,
                        ).stableKey,
                    )
                }
            }
            // Partial reading effects reference a chapter through their payload.
            batch.objects.filter { it.objectKey.stableKey in this && it.objectKey.type == SyncObjectType.CHAPTER }
                .forEach {
                    add(
                        SyncObjectKey(
                            SyncObjectType.MANGA,
                            sourceId = it.objectKey.sourceId,
                            originalUrl = it.objectKey.parentUrl,
                        ).stableKey,
                    )
                }
        }
        val seen = mutableSetOf<String>()
        for (descriptor in batch.objects) {
            val key = descriptor.objectKey
            if (!seen.add(key.stableKey) || key.stableKey !in referenced) return invalid()
            if (descriptor.title.isBlank() || descriptor.title.length > 4096) return invalid()
            if (listOf(descriptor.author, descriptor.artist, descriptor.scanlator).any { (it?.length ?: 0) > 4096 } ||
                (descriptor.thumbnailUrl?.length ?: 0) > 8192
            ) {
                return invalid()
            }
            if (key.type != SyncObjectType.MANGA &&
                listOf(descriptor.author, descriptor.artist, descriptor.thumbnailUrl).any { it != null }
            ) {
                return invalid()
            }
            if (key.type != SyncObjectType.CHAPTER &&
                (descriptor.chapterNumber != null || descriptor.sourceOrder != null || descriptor.scanlator != null)
            ) {
                return invalid()
            }
            if (descriptor.chapterNumber?.isFinite() == false) return invalid()
            val (category, field, kind) = when (key.type) {
                SyncObjectType.MANGA -> Triple(SyncCategory.FAVORITE, SyncField.FAVORITE, SyncEffectKind.ADD)
                SyncObjectType.AUTHOR -> Triple(SyncCategory.FOLLOW, SyncField.FOLLOWING, SyncEffectKind.ADD)
                SyncObjectType.CHAPTER -> Triple(SyncCategory.READING, SyncField.READ_STATUS, SyncEffectKind.MARK_READ)
            }
            val identityProbe = batch.events.first().copy(
                category = category,
                effects = listOf(SyncEffect("identity", key, field, kind)),
            )
            if (SyncValidator.validate(identityProbe).isNotEmpty()) return invalid()
        }
        return emptyList()
    }
}

internal object SyncValidator {
    fun validate(
        event: SyncEventEnvelope,
        expectedSpaceId: String? = null,
        expectedGeneration: Long? = null,
    ): List<SyncRejection> {
        val errors = mutableListOf<SyncRejection>()
        fun error(reason: SyncRejectionReason, message: String, effect: SyncEffect? = null) {
            errors += SyncRejection(reason, message, event.eventId, effect?.let { event.ref(it.effectId) })
        }
        if (event.protocolVersion != SyncProtocol.CURRENT_VERSION) {
            error(SyncRejectionReason.UNKNOWN_PROTOCOL, "unsupported protocol version")
        }
        if (event.spaceId.isBlank() || event.spaceId.length > 128) {
            error(SyncRejectionReason.EMPTY_FIELD, "space id is invalid")
        }
        if (expectedSpaceId != null && event.spaceId != expectedSpaceId) {
            error(SyncRejectionReason.WRONG_SPACE, "event belongs to another space")
        }
        if (event.generation < 0) {
            error(SyncRejectionReason.WRONG_GENERATION, "generation must be non-negative")
        }
        if (expectedGeneration != null && event.generation != expectedGeneration) {
            error(SyncRejectionReason.WRONG_GENERATION, "event belongs to another generation")
        }
        if (event.actorId.isBlank() || event.actorId.length > 128 || event.actorId.contains(':')) {
            error(SyncRejectionReason.INVALID_ACTOR, "actor id is invalid")
        }
        if (event.epoch <= 0) error(SyncRejectionReason.INVALID_EPOCH, "actor epoch must be positive")
        if (event.seq <= 0) error(SyncRejectionReason.INVALID_SEQUENCE, "sequence must be positive")
        if (event.effects.isEmpty() || event.effects.size > SyncProtocol.MAX_EFFECTS_PER_EVENT) {
            error(SyncRejectionReason.TOO_MANY_EFFECTS, "event effect count is invalid")
        }
        if (event.origin != SyncOrigin.USER && event.importId.isNullOrBlank()) {
            error(SyncRejectionReason.EMPTY_FIELD, "import origin requires import id")
        }
        if (event.importId != null && event.importId.length > 128) {
            error(SyncRejectionReason.PAYLOAD_TOO_LARGE, "import id is too long")
        }
        if (event.batchId != null && event.batchId.length > 128) {
            error(SyncRejectionReason.PAYLOAD_TOO_LARGE, "batch id is too long")
        }
        if (event.contentDigest != null && event.contentDigest.length > 256) {
            error(SyncRejectionReason.PAYLOAD_TOO_LARGE, "content digest is too long")
        }

        val effectIds = mutableSetOf<String>()
        event.effects.forEach { effect ->
            if (!effectIds.add(effect.effectId)) {
                error(SyncRejectionReason.DUPLICATE_EFFECT_ID, "effect id repeats", effect)
            }
            if (effect.effectId.isBlank() || effect.effectId.length > 128) {
                error(SyncRejectionReason.EMPTY_FIELD, "effect id is invalid", effect)
            }
            validateObjectKey(effect.objectKey, event, effect, errors)
            validateCombination(event.category, effect, errors)
            validatePayload(effect, errors, event)
            if (effect.parents.size > SyncProtocol.MAX_PARENTS_PER_EFFECT) {
                error(SyncRejectionReason.INVALID_EFFECT, "too many parents", effect)
            }
            val parentIds = mutableSetOf<String>()
            effect.parents.forEach { parent ->
                if (!parentIds.add(parent.stableKey)) {
                    error(SyncRejectionReason.DUPLICATE_PARENT, "parent repeats", effect)
                }
                if (parent.eventId.actorId.isBlank() ||
                    parent.eventId.actorId.length > 128 ||
                    parent.eventId.actorId.contains(':') ||
                    parent.eventId.epoch <= 0 ||
                    parent.eventId.seq <= 0 ||
                    parent.effectId.isBlank() ||
                    parent.effectId.length > 128
                ) {
                    error(SyncRejectionReason.INVALID_PARENT, "parent identity is invalid", effect)
                }
                if (parent.eventId == event.eventId && parent.effectId == effect.effectId) {
                    error(SyncRejectionReason.CAUSAL_CYCLE, "effect cannot parent itself", effect)
                }
                if (parent.spaceId != null && parent.spaceId != event.spaceId) {
                    error(SyncRejectionReason.PARENT_FOREIGN_SPACE, "parent belongs to another space", effect)
                }
                if (parent.generation != null && parent.generation != event.generation) {
                    error(
                        SyncRejectionReason.PARENT_FOREIGN_GENERATION,
                        "parent belongs to another generation",
                        effect,
                    )
                }
            }
        }
        return errors
    }

    private fun validateObjectKey(
        key: SyncObjectKey,
        event: SyncEventEnvelope,
        effect: SyncEffect,
        errors: MutableList<SyncRejection>,
    ) {
        val sourceIdValid =
            !key.sourceId.isNullOrBlank() &&
                key.sourceId.toLongOrNull()?.toString() == key.sourceId
        val portableValid = !key.portableKey.isNullOrBlank() && key.portableKey.length <= 1024
        val originalUrlValid = !key.originalUrl.isNullOrBlank() && key.originalUrl.length <= 4096
        val parentUrlValid = !key.parentUrl.isNullOrBlank() && key.parentUrl.length <= 4096
        val valid = when (key.type) {
            SyncObjectType.MANGA ->
                sourceIdValid && originalUrlValid && key.portableKey == null && key.parentUrl == null
            SyncObjectType.CHAPTER ->
                sourceIdValid && originalUrlValid && parentUrlValid && key.portableKey == null
            SyncObjectType.AUTHOR ->
                portableValid && key.sourceId == null && key.originalUrl == null && key.parentUrl == null
        }
        if (!valid) {
            errors += SyncRejection(
                SyncRejectionReason.INVALID_OBJECT_IDENTITY,
                "object identity is incomplete or has the wrong shape",
                event.eventId,
                event.ref(effect.effectId),
            )
        }
        if ((key.originalUrl != null && key.originalUrl.length > 4096) ||
            (key.parentUrl != null && key.parentUrl.length > 4096)
        ) {
            errors += SyncRejection(
                SyncRejectionReason.PAYLOAD_TOO_LARGE,
                "object url is too long",
                event.eventId,
                event.ref(effect.effectId),
            )
        }
    }

    private fun validateCombination(
        category: SyncCategory,
        effect: SyncEffect,
        errors: MutableList<SyncRejection>,
    ) {
        val valid = when (category) {
            SyncCategory.FAVORITE ->
                effect.objectKey.type == SyncObjectType.MANGA &&
                    effect.field == SyncField.FAVORITE &&
                    effect.kind in setOf(SyncEffectKind.ADD, SyncEffectKind.REMOVE)
            SyncCategory.FOLLOW ->
                effect.objectKey.type == SyncObjectType.AUTHOR &&
                    effect.field == SyncField.FOLLOWING &&
                    effect.kind in setOf(SyncEffectKind.ADD, SyncEffectKind.REMOVE)
            SyncCategory.READING ->
                (
                    effect.field == SyncField.READ_STATUS &&
                        effect.objectKey.type == SyncObjectType.CHAPTER &&
                        effect.kind in setOf(SyncEffectKind.MARK_READ, SyncEffectKind.MARK_UNREAD)
                    ) ||
                    (
                        effect.field == SyncField.RESUME_POSITION &&
                            effect.objectKey.type == SyncObjectType.MANGA &&
                            effect.kind == SyncEffectKind.RESUME_POSITION
                        ) ||
                    (
                        effect.field == SyncField.READING_SUMMARY &&
                            effect.objectKey.type == SyncObjectType.MANGA &&
                            effect.kind == SyncEffectKind.READING_SUMMARY
                        )
        }
        if (!valid) {
            errors += SyncRejection(
                SyncRejectionReason.INVALID_EFFECT_COMBINATION,
                "category, field, object and kind do not match",
            )
        }
    }

    private fun validatePayload(
        effect: SyncEffect,
        errors: MutableList<SyncRejection>,
        event: SyncEventEnvelope,
    ) {
        val allowed = when (effect.kind) {
            SyncEffectKind.ADD, SyncEffectKind.REMOVE -> emptySet()
            SyncEffectKind.MARK_READ, SyncEffectKind.MARK_UNREAD -> setOf("chapterKey")
            SyncEffectKind.RESUME_POSITION ->
                setOf(
                    "chapterKey",
                    "pageIndex",
                    "totalPages",
                    "contentVersion",
                )
            SyncEffectKind.READING_SUMMARY -> setOf("chapterKey", "readAt")
        }
        val unknown = effect.payload.keys - allowed
        if (unknown.isNotEmpty()) {
            errors += SyncRejection(
                SyncRejectionReason.UNKNOWN_PAYLOAD_FIELD,
                "payload contains an unknown field",
                event.eventId,
                event.ref(effect.effectId),
            )
        }
        if (effect.payload.toString().toByteArray(Charsets.UTF_8).size > 64 * 1024) {
            errors += SyncRejection(
                SyncRejectionReason.PAYLOAD_TOO_LARGE,
                "effect payload is too large",
                event.eventId,
                event.ref(effect.effectId),
            )
        }
        if (effect.kind == SyncEffectKind.RESUME_POSITION) {
            val chapterElement = effect.payload["chapterKey"] as? JsonPrimitive
            val pageElement = effect.payload["pageIndex"] as? JsonPrimitive
            val chapter = chapterElement?.takeIf { it.isString }?.content
            val page = pageElement?.takeUnless { it.isString }?.content?.toIntOrNull()
            if (chapter.isNullOrBlank() || page == null || page < 0) {
                errors += SyncRejection(
                    SyncRejectionReason.INVALID_PAYLOAD,
                    "resume position is invalid",
                    event.eventId,
                    event.ref(effect.effectId),
                )
            }
            val totalElement = effect.payload["totalPages"] as? JsonPrimitive
            val total = totalElement?.takeUnless { it.isString }?.content?.toIntOrNull()
            if (effect.payload.containsKey("totalPages") && total == null) {
                errors += SyncRejection(
                    SyncRejectionReason.INVALID_PAYLOAD,
                    "total pages is invalid",
                    event.eventId,
                    event.ref(effect.effectId),
                )
            }
            if (total != null && total < 0) {
                errors += SyncRejection(
                    SyncRejectionReason.INVALID_PAYLOAD,
                    "total pages is invalid",
                    event.eventId,
                    event.ref(effect.effectId),
                )
            }
            val contentVersion = effect.payload["contentVersion"] as? JsonPrimitive
            if (effect.payload.containsKey("contentVersion") &&
                (contentVersion == null || !contentVersion.isString || contentVersion.content.isBlank())
            ) {
                errors += SyncRejection(
                    SyncRejectionReason.INVALID_PAYLOAD,
                    "content version is invalid",
                    event.eventId,
                    event.ref(effect.effectId),
                )
            }
        }
        if (effect.kind == SyncEffectKind.MARK_READ || effect.kind == SyncEffectKind.MARK_UNREAD) {
            val chapterElement = effect.payload["chapterKey"] as? JsonPrimitive
            if (effect.payload.containsKey("chapterKey") &&
                (chapterElement == null || !chapterElement.isString || chapterElement.content.isBlank())
            ) {
                errors += SyncRejection(
                    SyncRejectionReason.INVALID_PAYLOAD,
                    "chapter key is invalid",
                    event.eventId,
                    event.ref(effect.effectId),
                )
            }
        }
        if (effect.kind == SyncEffectKind.READING_SUMMARY) {
            val chapterElement = effect.payload["chapterKey"] as? JsonPrimitive
            val readAtElement = effect.payload["readAt"] as? JsonPrimitive
            val chapter = chapterElement?.takeIf { it.isString }?.content
            val readAt = readAtElement?.takeUnless { it.isString }?.content?.toLongOrNull()
            if (chapter.isNullOrBlank() || readAt == null || readAt < 0) {
                errors += SyncRejection(
                    SyncRejectionReason.INVALID_PAYLOAD,
                    "reading summary is invalid",
                    event.eventId,
                    event.ref(effect.effectId),
                )
            }
        }
    }
}
