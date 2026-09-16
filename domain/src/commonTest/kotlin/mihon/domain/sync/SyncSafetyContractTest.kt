package mihon.domain.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSafetyContractTest {
    private val manga = SyncObjectKey(SyncObjectType.MANGA, sourceId = "64", originalUrl = "/manga/1")

    @Test
    fun `conflicting copies of one event cannot choose a winner by arrival order`() {
        val add = favorite("a", 1, SyncEffectKind.ADD)
        val remove = add.copy(effects = listOf(add.effects.single().copy(kind = SyncEffectKind.REMOVE)))
        listOf(listOf(add, remove), listOf(remove, add)).forEach { order ->
            val reduced = SyncReducer.reduce(order, "space", 1)
            assertTrue(reduced.rejections.any { it.reason == SyncRejectionReason.SAME_ID_DIFFERENT_CONTENT })
            assertEquals(null, reduced.projection(manga, SyncField.FAVORITE).value)
        }
    }

    @Test
    fun `explicit scope rejects a foreign first event and accepts local events`() {
        val valid = favorite("valid", 1, SyncEffectKind.ADD)
        val foreign = valid.copy(spaceId = "foreign")
        val reduced = SyncReducer.reduce(listOf(foreign, valid), spaceId = "space", generation = 1)
        assertTrue(reduced.rejections.any { it.reason == SyncRejectionReason.WRONG_SPACE })
        assertEquals(true, reduced.projection(manga, SyncField.FAVORITE).value)
        assertTrue(valid.eventId in reduced.events)
    }

    @Test
    fun `restore cannot resurrect removal by naming it as a parent`() {
        val remove = favorite("user", 1, SyncEffectKind.REMOVE)
        val restore =
            favorite("restore", 1, SyncEffectKind.ADD, parents = listOf(remove.ref("membership")))
                .copy(origin = SyncOrigin.BACKUP_RESTORE, importId = "restore-1")
        val reduced = SyncReducer.reduce(listOf(remove, restore), "space", 1)
        assertEquals(false, reduced.projection(manga, SyncField.FAVORITE).value)
    }

    @Test
    fun `event local effect names do not mix reading metadata between devices`() {
        val first = reading("a", page = 9, time = 100)
        val second = reading("b", page = 2, time = 200)
        val projection = SyncReducer.reduce(
            listOf(first, second),
            "space",
            1,
        ).projection(manga, SyncField.RESUME_POSITION)
        val choice = SyncReadingPolicy.chooseResume(projection, SyncReadingSession(manga, "chapter", 30, "double"))
        assertEquals(second.ref("position"), choice.nextPosition?.effectRef)
        assertEquals(2, choice.nextPosition?.pageIndex)
        assertEquals(setOf(first.ref("position"), second.ref("position")), choice.history.map { it.effectRef }.toSet())
        assertEquals(30, choice.activeSession.pageIndex)
        assertEquals("double", choice.activeSession.mode)
    }

    @Test
    fun `missing source identity cannot be accepted for a manga`() {
        val event = favorite("a", 1, SyncEffectKind.ADD)
        val missingSource = event.copy(
            effects = listOf(event.effects.single().copy(objectKey = manga.copy(sourceId = null))),
        )
        assertRejected(missingSource)
    }

    @Test
    fun `summary requires a chapter and typed timestamp`() {
        val empty =
            reading("a", page = 1, time = 10).let { event ->
                event.copy(
                    effects =
                    listOf(
                        event.effects.single().copy(
                            field = SyncField.READING_SUMMARY,
                            kind = SyncEffectKind.READING_SUMMARY,
                            payload = buildJsonObject {},
                        ),
                    ),
                )
            }
        assertRejected(empty)
        assertRejected(
            empty.copy(
                effects =
                listOf(
                    empty.effects.single().copy(
                        payload =
                        buildJsonObject {
                            put("chapterKey", "chapter")
                            put("readAt", buildJsonObject { put("invalid", true) })
                        },
                    ),
                ),
            ),
        )
    }

    @Test
    fun `resume optional hints still require the declared primitive types`() {
        val event = reading("a", page = 1, time = 10)
        val invalidPayloads =
            listOf(
                buildJsonObject {
                    put("chapterKey", true)
                    put("pageIndex", 1)
                },
                buildJsonObject {
                    put("chapterKey", "chapter")
                    put("pageIndex", "1")
                },
                buildJsonObject {
                    put("chapterKey", "chapter")
                    put("pageIndex", 1)
                    put("totalPages", "bad")
                },
                buildJsonObject {
                    put("chapterKey", "chapter")
                    put("pageIndex", 1)
                    put("contentVersion", buildJsonObject {})
                },
            )
        invalidPayloads.forEach { payload ->
            assertRejected(event.copy(effects = listOf(event.effects.single().copy(payload = payload))))
        }
    }

    @Test
    fun `malformed parent identity is rejected rather than waiting forever`() {
        val malformedParent = SyncEffectRef(SyncEventId("", 0, -1), "")
        assertRejected(favorite("a", 1, SyncEffectKind.REMOVE, parents = listOf(malformedParent)))
    }

    @Test
    fun `an accepted local decision cannot be reversed by a second click on the same request`() {
        val reduction = SyncReducer.reduce(listOf(favorite("a", 1, SyncEffectKind.REMOVE)), "space", 1)
        val memory = SyncReceiverMemory()
        val request = memory.pendingCancellation(reduction, manga, SyncField.FAVORITE, true).single()
        val original = memory.decide(request, SyncCancellationDecision.KEEP_LOCAL, reduction)
        val repeated = memory.decide(request, SyncCancellationDecision.CONFIRM, reduction)
        assertEquals(original, repeated)
        assertTrue(repeated.localValue)
        assertTrue(repeated.emittedEvents.isEmpty())
    }

    @Test
    fun `receiver decisions from a different generation do not suppress a new cancellation`() {
        val remove = favorite("a", 1, SyncEffectKind.REMOVE)
        val first = SyncReducer.reduce(listOf(remove), "space", 1)
        val memory = SyncReceiverMemory()
        val request = memory.pendingCancellation(first, manga, SyncField.FAVORITE, true).single()
        memory.decide(request, SyncCancellationDecision.KEEP_LOCAL, first)
        val second = SyncReducer.reduce(listOf(remove.copy(generation = 2)), "space", 2)
        assertEquals(1, memory.pendingCancellation(second, manga, SyncField.FAVORITE, true).size)
        assertEquals(
            SyncReceiverDecisionResult.INVALIDATED,
            SyncReceiver.decide(request, SyncCancellationDecision.CONFIRM, second).result,
        )
    }

    @Test
    fun `all three device arrival permutations and their prefixes converge`() {
        val add = favorite("a", 1, SyncEffectKind.ADD)
        val remove = favorite("b", 1, SyncEffectKind.REMOVE, parents = listOf(add.ref("membership")))
        val readd = favorite("c", 1, SyncEffectKind.ADD, parents = listOf(remove.ref("membership")))
        val orders =
            listOf(
                listOf(add, remove, readd),
                listOf(add, readd, remove),
                listOf(remove, add, readd),
                listOf(remove, readd, add),
                listOf(readd, add, remove),
                listOf(readd, remove, add),
            )
        orders.forEach { order ->
            val reduced = SyncReducer.reduce(order + order, "space", 1)
            assertTrue(reduced.rejections.isEmpty())
            assertEquals(listOf(readd.ref("membership")), reduced.projection(manga, SyncField.FAVORITE).heads)
            assertEquals(true, reduced.projection(manga, SyncField.FAVORITE).value)
        }
    }

    @Test
    fun `follow and unread successors use their own field histories`() {
        val author = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "author-1")
        val follow =
            envelope(
                "follow",
                1,
                SyncCategory.FOLLOW,
                SyncEffect("following", author, SyncField.FOLLOWING, SyncEffectKind.ADD),
            )
        val unfollow =
            envelope(
                "follow",
                2,
                SyncCategory.FOLLOW,
                SyncEffect(
                    "unfollowing",
                    author,
                    SyncField.FOLLOWING,
                    SyncEffectKind.REMOVE,
                    listOf(follow.ref("following")),
                ),
            )
        val followProjection = SyncReducer.reduce(
            listOf(follow, unfollow),
            "space",
            1,
        ).projection(author, SyncField.FOLLOWING)
        assertEquals(false, followProjection.value)

        val chapter =
            SyncObjectKey(
                SyncObjectType.CHAPTER,
                sourceId = "64",
                originalUrl = "/manga/1/chapter/1",
                parentUrl = "/manga/1",
            )
        val read =
            envelope(
                "reader",
                1,
                SyncCategory.READING,
                SyncEffect("read", chapter, SyncField.READ_STATUS, SyncEffectKind.MARK_READ),
            )
        val unread =
            envelope(
                "reader",
                2,
                SyncCategory.READING,
                SyncEffect(
                    "unread",
                    chapter,
                    SyncField.READ_STATUS,
                    SyncEffectKind.MARK_UNREAD,
                    listOf(read.ref("read")),
                ),
            )
        assertEquals(
            SyncReadStatus.UNREAD,
            SyncReducer.reduce(listOf(read, unread), "space", 1).projection(chapter, SyncField.READ_STATUS).readStatus,
        )
    }

    @Test
    fun `malformed effect prevents its valid sibling from entering projections`() {
        val valid = favorite("a", 1, SyncEffectKind.ADD)
        val invalid = valid.effects.single().copy(effectId = "bad", field = SyncField.READ_STATUS)
        val reduced = SyncReducer.reduce(listOf(valid.copy(effects = valid.effects + invalid)), "space", 1)
        assertTrue(reduced.events.isEmpty())
        assertFalse(reduced.projection(manga, SyncField.FAVORITE).value == true)
    }

    @Test
    fun `long causal chain completes without recursive ancestor scans`() {
        val events = mutableListOf(favorite("chain", 1, SyncEffectKind.ADD))
        repeat(1_999) { index ->
            val previous = events.last()
            events +=
                favorite(
                    "chain",
                    index + 2L,
                    SyncEffectKind.ADD,
                    parents = listOf(previous.ref("membership")),
                )
        }

        val reduced = SyncReducer.reduce(events, "space", 1)
        val projection = reduced.projection(manga, SyncField.FAVORITE)
        assertEquals(2_000, reduced.events.size)
        assertEquals(listOf(events.last().ref("membership")), projection.heads)
        assertEquals(true, projection.value)
        assertFalse(projection.pending)
    }

    private fun assertRejected(event: SyncEventEnvelope) {
        assertTrue(SyncCodec.decode(SyncCodec.rawEncode(event)) is SyncDecodeResult.Rejected)
        assertTrue(SyncReducer.reduce(listOf(event), "space", 1).events.isEmpty())
    }

    private fun favorite(
        actor: String,
        sequence: Long,
        kind: SyncEffectKind,
        parents: List<SyncEffectRef> = emptyList(),
    ) = envelope(
        actor,
        sequence,
        SyncCategory.FAVORITE,
        SyncEffect("membership", manga, SyncField.FAVORITE, kind, parents),
    )

    private fun reading(
        actor: String,
        page: Int,
        time: Long,
    ) = envelope(
        actor,
        1,
        SyncCategory.READING,
        SyncEffect(
            "position",
            manga,
            SyncField.RESUME_POSITION,
            SyncEffectKind.RESUME_POSITION,
            payload =
            buildJsonObject {
                put("chapterKey", "chapter")
                put("pageIndex", page)
            },
        ),
    ).copy(occurredAt = time)

    private fun envelope(
        actor: String,
        sequence: Long,
        category: SyncCategory,
        effect: SyncEffect,
    ) = SyncEventEnvelope(
        protocolVersion = 1,
        spaceId = "space",
        generation = 1,
        actorId = actor,
        epoch = 1,
        seq = sequence,
        category = category,
        effects = listOf(effect),
        origin = SyncOrigin.USER,
    )
}
