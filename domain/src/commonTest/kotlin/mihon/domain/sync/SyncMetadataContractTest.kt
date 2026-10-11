package mihon.domain.sync

import mihon.domain.reader.ChapterPairingRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncMetadataContractTest {
    @Test
    fun `transport remains version one and preserves historical and metadata events together`() {
        val metadata = (SyncCodec.decode(categoryJson("[]")) as SyncDecodeResult.Accepted).event
        val legacy = metadata.copy(
            protocolVersion = 1,
            seq = 2,
            effects = listOf(
                SyncEffect("favorite", metadata.effects.single().objectKey, SyncField.FAVORITE, SyncEffectKind.ADD),
            ),
        )
        val historical = SyncCodec.encode(legacy)
        val decoded = SyncBatchCodec.decode(SyncBatchCodec.encode(listOf(metadata, legacy), "batch", "space", 1))
        assertTrue(decoded is SyncBatchDecodeResult.Accepted)
        val batch = (decoded as SyncBatchDecodeResult.Accepted).batch
        assertEquals(1, batch.protocolVersion)
        assertEquals(listOf(2, 1), batch.events.map { it.protocolVersion })
        assertEquals(historical, SyncCodec.encode(batch.events.last()))
    }

    @Test
    fun `causal snapshot register converges under reordered repeated events and protects clear from baseline`() {
        val clear = (SyncCodec.decode(categoryJson("[]")) as SyncDecodeResult.Accepted).event
        val baseline = clear.copy(
            actorId = "baseline",
            origin = SyncOrigin.INITIAL_IMPORT,
            importId = "import",
            occurredAt = 999,
            effects = listOf(clear.effects.single().copy(payload = SyncMetadataPolicy.categoryPayload(listOf("old")))),
        )
        val concurrent = clear.copy(
            actorId = "other",
            occurredAt = 20,
            effects = listOf(clear.effects.single().copy(payload = SyncMetadataPolicy.categoryPayload(listOf("new")))),
        )
        val laterClear = clear.copy(
            seq = 2,
            occurredAt = 0,
            effects = listOf(
                clear.effects.single().copy(
                    parents = listOf(clear.effects.single().ref(clear), concurrent.effects.single().ref(concurrent)),
                ),
            ),
        )
        val key = clear.effects.single().objectKey
        listOf(listOf(clear, baseline), listOf(baseline, clear, clear)).forEach {
            val chosen = SyncMetadataPolicy.choose(
                SyncReducer.reduce(it, "space", 1).projection(key, SyncField.MANGA_CATEGORIES),
            )!!
            assertEquals(emptyList<String>(), SyncMetadataPolicy.categoryNames(chosen.payload))
        }
        listOf(listOf(clear, concurrent), listOf(concurrent, clear, concurrent)).forEach {
            val chosen = SyncMetadataPolicy.choose(
                SyncReducer.reduce(it, "space", 1).projection(key, SyncField.MANGA_CATEGORIES),
            )!!
            assertEquals(listOf("new"), SyncMetadataPolicy.categoryNames(chosen.payload))
        }
        listOf(
            listOf(laterClear, baseline, concurrent, clear),
            listOf(clear, concurrent, baseline, laterClear),
        ).forEach {
            val chosen = SyncMetadataPolicy.choose(
                SyncReducer.reduce(it, "space", 1).projection(key, SyncField.MANGA_CATEGORIES),
            )!!
            assertEquals(emptyList<String>(), SyncMetadataPolicy.categoryNames(chosen.payload))
        }
    }

    @Test
    fun `category snapshot version two round trips exact names and explicit empty sets`() {
        listOf("[\"科幻\",\"SF\",\"sf\"]", "[]").forEach { names ->
            val decoded = SyncCodec.decode(categoryJson(names))
            assertTrue(decoded is SyncDecodeResult.Accepted, decoded.toString())
            val event = (decoded as SyncDecodeResult.Accepted).event
            assertEquals(event, (SyncCodec.decode(SyncCodec.encode(event)) as SyncDecodeResult.Accepted).event)
        }
    }

    @Test
    fun `new effects require version two and reject invalid category sets`() {
        listOf("[\"a\",\"a\"]", "[1]", "[\"\"]").forEach {
            assertTrue(SyncCodec.decode(categoryJson(it)) is SyncDecodeResult.Rejected)
        }
        assertTrue(
            SyncCodec.decode(
                categoryJson("[]").replace("\"protocolVersion\":2", "\"protocolVersion\":1"),
            ) is SyncDecodeResult.Rejected,
        )
        assertTrue(
            SyncCodec.decode(categoryJson("[]").replace("\"protocolVersion\":2", "\"protocolVersion\":3"))
                is SyncDecodeResult.Rejected,
        )
    }

    @Test
    fun `layout clear register resolves ties and causal edits independently of event order`() {
        val clearJson = layoutJson(
            "\"openingMode\":\"AUTO\",\"forcedSinglePages\":[],\"manualPairStarts\":[]",
        )
        val clear = (SyncCodec.decode(clearJson) as SyncDecodeResult.Accepted).event
        val clearEffect = clear.effects.single()
        val manualEffect = clearEffect.copy(
            payload = SyncMetadataPolicy.pairingPayload(ChapterPairingRecord(2, 10, setOf(3))),
        )
        val manual = clear.copy(actorId = "z", effects = listOf(manualEffect))
        val baseline = manual.copy(
            actorId = "baseline",
            origin = SyncOrigin.INITIAL_IMPORT,
            importId = "import",
            occurredAt = 999,
        )
        val laterEffect = clearEffect.copy(parents = listOf(manualEffect.ref(manual), clearEffect.ref(clear)))
        val laterClear = clear.copy(seq = 2, effects = listOf(laterEffect))
        fun chosen(events: List<SyncEventEnvelope>): ChapterPairingRecord {
            val state = SyncReducer.reduce(events, "space", 1)
            val projection = state.projection(clearEffect.objectKey, SyncField.CHAPTER_PAIRING)
            return SyncMetadataPolicy.pairingRecord(SyncMetadataPolicy.choose(projection)!!.payload)
        }
        assertTrue(SyncMetadataPolicy.isClear(chosen(listOf(baseline, clear))))
        assertEquals(chosen(listOf(clear, manual)), chosen(listOf(manual, clear, manual)))
        assertEquals(setOf(3), chosen(listOf(clear, manual)).forcedSinglePages)
        assertTrue(SyncMetadataPolicy.isClear(chosen(listOf(laterClear, baseline, manual, clear))))
        assertEquals(chosen(listOf(laterClear, manual, clear)), chosen(listOf(clear, manual, laterClear)))
    }

    @Test
    fun `layout snapshots validate existing layout semantics and accept clear`() {
        listOf(
            "\"openingMode\":\"AUTO\",\"forcedSinglePages\":[3],\"manualPairStarts\":[5]",
            "\"openingMode\":\"AUTO\",\"forcedSinglePages\":[],\"manualPairStarts\":[]",
        ).forEach {
            val decoded = SyncCodec.decode(layoutJson(it))
            assertTrue(decoded is SyncDecodeResult.Accepted, decoded.toString())
        }
        listOf(
            "\"openingMode\":\"FIRST_PAIR\",\"forcedSinglePages\":[1],\"manualPairStarts\":[]",
            "\"openingMode\":\"AUTO\",\"forcedSinglePages\":[],\"manualPairStarts\":[5,6]",
            "\"openingMode\":\"AUTO\",\"forcedSinglePages\":[10],\"manualPairStarts\":[]",
        ).forEach { assertTrue(SyncCodec.decode(layoutJson(it)) is SyncDecodeResult.Rejected) }
    }

    private fun categoryJson(names: String) = """
        {"protocolVersion":2,"spaceId":"space","generation":1,"actorId":"actor","epoch":1,"seq":1,
        "category":"FAVORITE","origin":"USER","effects":[{"effectId":"categories",
        "objectKey":{"type":"MANGA","sourceId":"42","originalUrl":"/manga"},
        "field":"MANGA_CATEGORIES","kind":"SET_CATEGORIES","payload":{"names":$names}}]}
    """.trimIndent()

    private fun layoutJson(layout: String) = """
        {"protocolVersion":2,"spaceId":"space","generation":1,"actorId":"actor","epoch":1,"seq":1,
        "category":"READING","origin":"USER","effects":[{"effectId":"pairing",
        "objectKey":{"type":"CHAPTER","sourceId":"42","originalUrl":"/chapter","parentUrl":"/manga"},
        "field":"CHAPTER_PAIRING","kind":"SET_PAIRING","payload":{"formatVersion":2,"pageCount":10,$layout}}]}
    """.trimIndent()
}
