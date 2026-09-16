package mihon.domain.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProtocolContractTest {
    @Test
    fun `codec round trips a multi effect event and counts the envelope once`() {
        val event =
            event(
                category = SyncCategory.READING,
                effects =
                listOf(
                    effect(
                        "read",
                        SyncField.READ_STATUS,
                        SyncEffectKind.MARK_READ,
                        objectType = SyncObjectType.CHAPTER,
                    ),
                    effect(
                        "resume",
                        SyncField.RESUME_POSITION,
                        SyncEffectKind.RESUME_POSITION,
                        objectType = SyncObjectType.MANGA,
                        payload =
                        buildJsonObject {
                            put("chapterKey", "chapter-1")
                            put("pageIndex", 4)
                        },
                    ),
                ),
            )

        val encoded = SyncCodec.encode(event)
        val decoded = SyncCodec.decode(encoded)

        assertTrue(decoded is SyncDecodeResult.Accepted)
        val restored = (decoded as SyncDecodeResult.Accepted).event
        assertEquals(event.eventId, restored.eventId)
        assertEquals(2, restored.effects.size)
        assertEquals(1, SyncBatchCodec.countByCategory(listOf(restored))[SyncCategory.READING])
        assertFalse(encoded.contains("token"))
        assertFalse(encoded.contains("readingMode"))
    }

    @Test
    fun `causal order is deterministic under all arrival permutations and duplicate input`() {
        val add = event("a", 1, SyncCategory.FAVORITE, effect("add", SyncField.FAVORITE, SyncEffectKind.ADD))
        val remove =
            event(
                "a",
                2,
                SyncCategory.FAVORITE,
                effect("remove", SyncField.FAVORITE, SyncEffectKind.REMOVE, parents = listOf(add.ref("add"))),
            )
        val reAdd =
            event(
                "a",
                3,
                SyncCategory.FAVORITE,
                effect("re-add", SyncField.FAVORITE, SyncEffectKind.ADD, parents = listOf(remove.ref("remove"))),
            )

        val expected =
            SyncReducer
                .reduce(
                    listOf(add, remove, reAdd, reAdd),
                    "space",
                    1,
                ).projection(add.effects.single().objectKey, SyncField.FAVORITE)
        listOf(
            listOf(reAdd, add, remove, reAdd),
            listOf(remove, reAdd, add, reAdd),
            listOf(add, reAdd, remove, reAdd),
        ).forEach { order ->
            val result = SyncReducer.reduce(order, "space", 1)
            assertEquals(expected.value, result.projection(add.effects.single().objectKey, SyncField.FAVORITE).value)
            assertEquals(expected.heads, result.projection(add.effects.single().objectKey, SyncField.FAVORITE).heads)
            assertTrue(result.rejections.isEmpty())
        }
    }

    @Test
    fun `missing parents are pending and cross field parents are rejected`() {
        val missing =
            event(
                "a",
                2,
                SyncCategory.FAVORITE,
                effect("remove", SyncField.FAVORITE, SyncEffectKind.REMOVE, parents = listOf(ref("a", 1, "add"))),
            )
        val pending = SyncReducer.reduce(listOf(missing), "space", 1)
        assertTrue(pending.pendingDependencies.isNotEmpty())
        assertEquals(null, pending.projection(missing.effects.single().objectKey, SyncField.FAVORITE).value)

        val otherField =
            event(
                "a",
                1,
                SyncCategory.FOLLOW,
                effect("follow", SyncField.FOLLOWING, SyncEffectKind.ADD, objectType = SyncObjectType.AUTHOR),
            )
        val crossField =
            event(
                "a",
                2,
                SyncCategory.FAVORITE,
                effect("remove", SyncField.FAVORITE, SyncEffectKind.REMOVE, parents = listOf(otherField.ref("follow"))),
            )
        val rejected = SyncReducer.reduce(listOf(otherField, crossField), "space", 1)
        assertTrue(rejected.rejections.any { it.reason == SyncRejectionReason.CROSS_FIELD_PARENT })
    }

    @Test
    fun `concurrent membership keeps add and baseline cannot override user choice`() {
        val baselineAdd =
            event(
                "baseline",
                1,
                SyncCategory.FAVORITE,
                origin = SyncOrigin.INITIAL_IMPORT,
                effect = effect("add", SyncField.FAVORITE, SyncEffectKind.ADD),
            ).copy(importId = "baseline-1")
        val userRemove =
            event(
                "user",
                1,
                SyncCategory.FAVORITE,
                effect = effect("remove", SyncField.FAVORITE, SyncEffectKind.REMOVE),
            )
        val result = SyncReducer.reduce(listOf(baselineAdd, userRemove), "space", 1)
        val projection = result.projection(baselineAdd.effects.single().objectKey, SyncField.FAVORITE)
        assertEquals(false, projection.value)

        val concurrentAdd =
            event("other", 1, SyncCategory.FAVORITE, effect = effect("add", SyncField.FAVORITE, SyncEffectKind.ADD))
        assertEquals(
            true,
            SyncReducer
                .reduce(
                    listOf(userRemove, concurrentAdd),
                    "space",
                    1,
                ).projection(concurrentAdd.effects.single().objectKey, SyncField.FAVORITE)
                .value,
        )

        val unread =
            event(
                "user",
                2,
                SyncCategory.READING,
                effect = effect(
                    "unread",
                    SyncField.READ_STATUS,
                    SyncEffectKind.MARK_UNREAD,
                    objectType = SyncObjectType.CHAPTER,
                ),
            )
        val read =
            event(
                "other",
                2,
                SyncCategory.READING,
                effect = effect(
                    "read",
                    SyncField.READ_STATUS,
                    SyncEffectKind.MARK_READ,
                    objectType = SyncObjectType.CHAPTER,
                ),
            )
        assertEquals(
            SyncReadStatus.UNREAD,
            SyncReducer
                .reduce(
                    listOf(unread, read),
                    "space",
                    1,
                ).projection(unread.effects.single().objectKey, SyncField.READ_STATUS)
                .readStatus,
        )
    }

    @Test
    fun `receiver cancellation is local, bound to heads, and does not emit an event`() {
        val add =
            event("source", 1, SyncCategory.FAVORITE, effect = effect("add", SyncField.FAVORITE, SyncEffectKind.ADD))
        val remove =
            event(
                "source",
                2,
                SyncCategory.FAVORITE,
                effect = effect("remove", SyncField.FAVORITE, SyncEffectKind.REMOVE, parents = listOf(add.ref("add"))),
            )
        val reducer = SyncReducer.reduce(listOf(add, remove), "space", 1)
        val key = add.effects.single().objectKey
        val request = SyncReceiver.pendingCancellation(reducer, key, SyncField.FAVORITE, localValue = true).single()
        val ignored = SyncReceiver.decide(request, SyncCancellationDecision.KEEP_LOCAL, reducer)
        assertEquals(SyncCancellationDecision.KEEP_LOCAL, ignored.decision)
        assertTrue(ignored.emittedEvents.isEmpty())
        assertEquals(true, ignored.localValue)

        val reAdd =
            event(
                "source",
                3,
                SyncCategory.FAVORITE,
                effect = effect(
                    "re-add",
                    SyncField.FAVORITE,
                    SyncEffectKind.ADD,
                    parents = listOf(remove.ref("remove")),
                ),
            )
        val stale =
            SyncReceiver.decide(
                request,
                SyncCancellationDecision.CONFIRM,
                SyncReducer.reduce(listOf(add, remove, reAdd), "space", 1),
            )
        assertEquals(SyncReceiverDecisionResult.INVALIDATED, stale.result)
    }

    @Test
    fun `reading keeps history and deterministic candidates without changing active session`() {
        val first =
            event(
                "a",
                1,
                SyncCategory.READING,
                occurredAt = 20,
                effect =
                effect(
                    "first",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    objectType = SyncObjectType.MANGA,
                    payload = position("chapter-1", 9),
                ),
            )
        val second =
            event(
                "b",
                1,
                SyncCategory.READING,
                occurredAt = 10,
                effect =
                effect(
                    "second",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    objectType = SyncObjectType.MANGA,
                    payload = position("chapter-1", 2),
                ),
            )
        val reducer = SyncReducer.reduce(listOf(first, second), "space", 1)
        val key = first.effects.single().objectKey
        val session = SyncReadingSession(key, "chapter-1", pageIndex = 30, mode = "double")
        val choice = SyncReadingPolicy.chooseResume(reducer.projection(key, SyncField.RESUME_POSITION), session)
        assertEquals(9, choice.nextPosition?.pageIndex)
        assertEquals(30, choice.activeSession.pageIndex)
        assertEquals(2, choice.history.size)
        assertEquals(first.ref("first"), choice.nextPosition?.effectRef)
    }

    @Test
    fun `invalid envelope is isolated and batch limits are enforced`() {
        val invalid =
            event(
                "a",
                0,
                SyncCategory.FAVORITE,
                effect = effect("bad", SyncField.FAVORITE, SyncEffectKind.ADD),
            ).copy(protocolVersion = 99)
        val decoded = SyncCodec.decode(SyncCodec.rawEncode(invalid))
        assertTrue(decoded is SyncDecodeResult.Rejected)
        assertEquals(SyncRejectionReason.UNKNOWN_PROTOCOL, (decoded as SyncDecodeResult.Rejected).reason)

        val tooMany =
            (1..257).map {
                event(
                    "actor-$it",
                    1,
                    SyncCategory.FAVORITE,
                    effect = effect("add", SyncField.FAVORITE, SyncEffectKind.ADD),
                )
            }
        val batch = SyncBatchCodec.decode(SyncBatchCodec.rawEncode(tooMany))
        assertTrue(batch is SyncBatchDecodeResult.Rejected)
        assertEquals(SyncRejectionReason.BATCH_TOO_LARGE, (batch as SyncBatchDecodeResult.Rejected).reason)
    }

    @Test
    fun `object identity keeps stable signed 64 bit source id extremes`() {
        listOf(Long.MAX_VALUE, Long.MIN_VALUE).forEach { sourceId ->
            val key =
                SyncObjectKey(
                    type = SyncObjectType.MANGA,
                    sourceId = sourceId.toString(),
                    originalUrl = "/manga?id=$sourceId",
                )
            val event =
                event(
                    "identity-${if (sourceId < 0) "min" else "max"}",
                    1,
                    SyncCategory.FAVORITE,
                    effect("add", SyncField.FAVORITE, SyncEffectKind.ADD).copy(objectKey = key),
                )
            val restored = (SyncCodec.decode(SyncCodec.encode(event)) as SyncDecodeResult.Accepted).event
            assertEquals(
                sourceId.toString(),
                restored.effects
                    .single()
                    .objectKey.sourceId,
            )
            assertEquals(
                key.originalUrl,
                restored.effects
                    .single()
                    .objectKey.originalUrl,
            )
        }
    }

    @Test
    fun `same id with different content and foreign generation are isolated`() {
        val original = event("same", 1, SyncCategory.FAVORITE, effect("add", SyncField.FAVORITE, SyncEffectKind.ADD))
        val changed = original.copy(effects = listOf(original.effects.single().copy(kind = SyncEffectKind.REMOVE)))
        val changedResult = SyncReducer.reduce(listOf(original, changed), "space", 1)
        assertTrue(changedResult.rejections.any { it.reason == SyncRejectionReason.SAME_ID_DIFFERENT_CONTENT })

        val foreign = original.copy(spaceId = "other-space")
        val foreignResult = SyncReducer.reduce(listOf(original, foreign), "space", 1)
        assertTrue(foreignResult.rejections.any { it.reason == SyncRejectionReason.WRONG_SPACE })
    }

    @Test
    fun `cycle is rejected without recursion and malformed fields reject the whole envelope`() {
        val firstRef = ref("cycle", 1, "first")
        val secondRef = ref("cycle", 2, "second")
        val first =
            event(
                "cycle",
                1,
                SyncCategory.FAVORITE,
                effect("first", SyncField.FAVORITE, SyncEffectKind.ADD, parents = listOf(secondRef)),
            )
        val second =
            event(
                "cycle",
                1,
                SyncCategory.FAVORITE,
                effect("second", SyncField.FAVORITE, SyncEffectKind.REMOVE, parents = listOf(firstRef)),
            ).copy(seq = 2)
        val cyclic = SyncReducer.reduce(listOf(first, second), "space", 1)
        assertTrue(cyclic.rejections.any { it.reason == SyncRejectionReason.CAUSAL_CYCLE })

        val malformed = originalEventWithUnknownPayload()
        val malformedResult = SyncCodec.decode(SyncCodec.rawEncode(malformed))
        assertTrue(malformedResult is SyncDecodeResult.Rejected)
        assertEquals(SyncRejectionReason.UNKNOWN_PAYLOAD_FIELD, (malformedResult as SyncDecodeResult.Rejected).reason)
    }

    @Test
    fun `empty receiver does not manufacture a cancellation and duplicate decision is idempotent`() {
        val add =
            event("source", 1, SyncCategory.FAVORITE, effect = effect("add", SyncField.FAVORITE, SyncEffectKind.ADD))
        val remove =
            event(
                "source",
                2,
                SyncCategory.FAVORITE,
                effect = effect("remove", SyncField.FAVORITE, SyncEffectKind.REMOVE, parents = listOf(add.ref("add"))),
            )
        val reducer = SyncReducer.reduce(listOf(add, remove), "space", 1)
        val key = add.effects.single().objectKey
        assertTrue(SyncReceiver.pendingCancellation(reducer, key, SyncField.FAVORITE, localValue = false).isEmpty())
        val request = SyncReceiver.pendingCancellation(reducer, key, SyncField.FAVORITE, localValue = true).single()
        val first = SyncReceiver.decide(request, SyncCancellationDecision.CONFIRM, reducer)
        val second = SyncReceiver.decide(request, SyncCancellationDecision.CONFIRM, reducer)
        assertEquals(first, second)
        assertTrue(first.emittedEvents.isEmpty())

        val memory = SyncReceiverMemory()
        val pending = memory.pendingCancellation(reducer, key, SyncField.FAVORITE, localValue = true).single()
        memory.decide(pending, SyncCancellationDecision.KEEP_LOCAL, reducer)
        assertTrue(memory.pendingCancellation(reducer, key, SyncField.FAVORITE, localValue = true).isEmpty())
    }

    @Test
    fun `causal reread can move backwards and equal time uses stable id tie break`() {
        val early =
            event(
                "reader",
                1,
                SyncCategory.READING,
                occurredAt = 100,
                effect =
                effect(
                    "early",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    payload = position("chapter-1", 20),
                ),
            )
        val reread =
            event(
                "reader",
                1,
                SyncCategory.READING,
                occurredAt = 101,
                effect =
                effect(
                    "reread",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    parents = listOf(early.ref("early")),
                    payload = position("chapter-1", 3),
                ),
            ).copy(seq = 2)
        val rereadProjection =
            SyncReducer.reduce(listOf(early, reread), "space", 1).projection(
                early.effects.single().objectKey,
                SyncField.RESUME_POSITION,
            )
        val session = SyncReadingSession(early.effects.single().objectKey, "chapter-1", 30, "single")
        val rereadChoice = SyncReadingPolicy.chooseResume(rereadProjection, session)
        assertEquals(3, rereadChoice.nextPosition?.pageIndex)
        assertEquals(listOf(3), rereadChoice.history.map { it.pageIndex })

        val sameTimeA =
            event(
                "a",
                1,
                SyncCategory.READING,
                occurredAt = 200,
                effect =
                effect(
                    "a-position",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    payload = position("chapter-1", 4),
                ),
            )
        val sameTimeB =
            event(
                "b",
                1,
                SyncCategory.READING,
                occurredAt = 200,
                effect =
                effect(
                    "b-position",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    payload = position("chapter-1", 8),
                ),
            )
        val tieProjection =
            SyncReducer.reduce(listOf(sameTimeA, sameTimeB), "space", 1).projection(
                sameTimeA.effects.single().objectKey,
                SyncField.RESUME_POSITION,
            )
        val tieChoice = SyncReadingPolicy.chooseResume(tieProjection, session)
        assertEquals(
            "b",
            tieChoice.nextPosition
                ?.effectRef
                ?.eventId
                ?.actorId,
        )
        assertEquals(setOf(4, 8), tieChoice.history.map { it.pageIndex }.toSet())
    }

    @Test
    fun `malformed arrays are rejected and oversized batches are isolated`() {
        val arrayPayload =
            event(
                "array",
                1,
                SyncCategory.READING,
                effect(
                    "position",
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    payload =
                    buildJsonObject {
                        put("chapterKey", "chapter")
                        put("pageIndex", buildJsonArray { add(JsonPrimitive(1)) })
                    },
                ),
            )
        val arrayResult = SyncCodec.decode(SyncCodec.rawEncode(arrayPayload))
        assertTrue(arrayResult is SyncDecodeResult.Rejected)
        assertEquals(SyncRejectionReason.INVALID_PAYLOAD, (arrayResult as SyncDecodeResult.Rejected).reason)

        val largePayload =
            buildJsonObject {
                put("chapterKey", "chapter")
                put("pageIndex", 1)
                put("contentVersion", "x".repeat(2200))
            }
        val largeEvents =
            (1..256).map { number ->
                event(
                    "large-$number",
                    1,
                    SyncCategory.READING,
                    effect(
                        "position",
                        SyncField.RESUME_POSITION,
                        SyncEffectKind.RESUME_POSITION,
                        payload = largePayload,
                    ),
                )
            }
        val tooLarge = SyncBatchCodec.decode(SyncBatchCodec.rawEncode(largeEvents))
        assertTrue(tooLarge is SyncBatchDecodeResult.Rejected)
        assertEquals(SyncRejectionReason.BATCH_TOO_LARGE, (tooLarge as SyncBatchDecodeResult.Rejected).reason)

        val first = event("generation", 1, SyncCategory.FAVORITE, effect("add", SyncField.FAVORITE, SyncEffectKind.ADD))
        val later = first.copy(generation = 2, seq = 2)
        assertTrue(
            SyncReducer.reduce(listOf(first, later), "space", 1).rejections.any {
                it.reason ==
                    SyncRejectionReason.WRONG_GENERATION
            },
        )
    }

    private fun event(
        actor: String = "actor",
        epoch: Long = 1,
        category: SyncCategory,
        effect: SyncEffect,
        origin: SyncOrigin = SyncOrigin.USER,
        occurredAt: Long = 100,
    ) = event(actor, epoch, category, listOf(effect), origin, occurredAt)

    private fun event(
        actor: String = "actor",
        epoch: Long = 1,
        category: SyncCategory,
        effects: List<SyncEffect>,
        origin: SyncOrigin = SyncOrigin.USER,
        occurredAt: Long = 100,
    ) = SyncEventEnvelope(
        protocolVersion = SyncCodec.CURRENT_PROTOCOL_VERSION,
        spaceId = "space",
        generation = 1,
        actorId = actor,
        epoch = epoch,
        seq = epoch,
        category = category,
        effects = effects,
        origin = origin,
        occurredAt = occurredAt,
    )

    private fun originalEventWithUnknownPayload() =
        event(
            "malformed",
            1,
            SyncCategory.FAVORITE,
            effect(
                "add",
                SyncField.FAVORITE,
                SyncEffectKind.ADD,
                payload = buildJsonObject { put("token", "secret") },
            ),
        )

    private fun effect(
        id: String,
        field: SyncField,
        kind: SyncEffectKind,
        objectType: SyncObjectType = SyncObjectType.MANGA,
        parents: List<SyncEffectRef> = emptyList(),
        payload: kotlinx.serialization.json.JsonObject = buildJsonObject {},
    ) = SyncEffect(
        effectId = id,
        objectKey =
        when (objectType) {
            SyncObjectType.MANGA -> {
                SyncObjectKey(
                    objectType,
                    sourceId = "64",
                    originalUrl = "https://source.example/manga/portable",
                )
            }

            SyncObjectType.CHAPTER -> {
                SyncObjectKey(
                    objectType,
                    sourceId = "64",
                    originalUrl = "https://source.example/manga/portable/chapter",
                    parentUrl = "https://source.example/manga/portable",
                )
            }

            SyncObjectType.AUTHOR -> {
                SyncObjectKey(
                    objectType,
                    portableKey = "portable-author",
                )
            }
        },
        field = field,
        kind = kind,
        parents = parents,
        payload = payload,
    )

    private fun ref(
        actor: String,
        seq: Long,
        effectId: String,
    ) = SyncEffectRef(SyncEventId(actor, 1, seq), effectId)

    private fun position(
        chapter: String,
        page: Int,
    ) = buildJsonObject {
        put("chapterKey", chapter)
        put("pageIndex", page)
    }
}
