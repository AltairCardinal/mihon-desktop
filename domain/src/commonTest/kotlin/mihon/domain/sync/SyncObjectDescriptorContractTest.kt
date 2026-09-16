package mihon.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncObjectDescriptorContractTest {
    @Test
    fun `reading descriptions cannot attach a chapter from another source or parent manga`() {
        for (chapter in listOf(
            SyncObjectKey(SyncObjectType.CHAPTER, sourceId = "2", originalUrl = "/chapter", parentUrl = "/manga"),
            SyncObjectKey(
                SyncObjectType.CHAPTER,
                sourceId = manga.sourceId,
                originalUrl = "/chapter",
                parentUrl = "/other",
            ),
            SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "8d03f9ee-3103-4b49-aef3-c28312f1c15b"),
        )) {
            val effects = listOf(
                SyncEffect(
                    "position",
                    manga,
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    payload = buildJsonObject {
                        put("chapterKey", chapter.stableKey)
                        put("pageIndex", 1)
                    },
                ),
                SyncEffect(
                    "summary",
                    manga,
                    SyncField.READING_SUMMARY,
                    SyncEffectKind.READING_SUMMARY,
                    payload = buildJsonObject {
                        put("chapterKey", chapter.stableKey)
                        put("readAt", 1)
                    },
                ),
            )
            for (effect in effects) {
                val event = SyncEventEnvelope(
                    1, "space", 1, "actor", 1, 1, SyncCategory.READING,
                    listOf(effect), SyncOrigin.USER, batchId = "batch",
                )
                val input = SyncBatch(
                    1,
                    "space",
                    1,
                    "batch",
                    listOf(event),
                    listOf(SyncObjectDescriptor(manga, "漫画"), SyncObjectDescriptor(chapter, "章节")),
                )
                assertTrue(SyncBatchCodec.decode(SyncBatchCodec.rawEncode(input)) is SyncBatchDecodeResult.Rejected)
            }
        }
    }

    @Test
    fun `batch retains the minimum manga description without making metadata a user effect`() {
        val input = withObjects(descriptor(manga, "星海"))
        val decoded = SyncBatchCodec.decode(input)
        assertTrue(decoded is SyncBatchDecodeResult.Accepted, decoded.toString())
        val batch = (decoded as SyncBatchDecodeResult.Accepted).batch
        assertEquals(1, batch.events.size)
        assertEquals(1, batch.events.single().effects.size)
        val roundTrip = Json.parseToJsonElement(SyncBatchCodec.rawEncode(batch)) as JsonObject
        assertEquals(JsonArray(listOf(descriptor(manga, "星海"))), roundTrip["objects"])
    }

    @Test
    fun `ambiguous unrelated and platform settings in descriptions reject the whole batch`() {
        val valid = descriptor(manga, "Title")
        val other = descriptor(manga.copy(originalUrl = "/unrelated"), "Other")
        for (objects in listOf(
            listOf(valid, valid),
            listOf(other),
            listOf(descriptor(manga, "")),
            listOf(JsonObject(valid + ("readerMode" to JsonPrimitive("webtoon")))),
            listOf(descriptor(manga, "x".repeat(4097))),
        )) {
            assertTrue(SyncBatchCodec.decode(withObjects(*objects.toTypedArray())) is SyncBatchDecodeResult.Rejected)
        }
    }

    private val manga = SyncObjectKey(SyncObjectType.MANGA, sourceId = "9223372036854775806", originalUrl = "/manga")

    private fun descriptor(key: SyncObjectKey, title: String) = JsonObject(
        mapOf("objectKey" to Json.parseToJsonElement(Json.encodeToString(key)), "title" to JsonPrimitive(title)),
    )

    private fun withObjects(vararg descriptors: JsonObject): String {
        val event = SyncEventEnvelope(
            1, "space", 1, "actor", 1, 1, SyncCategory.FAVORITE,
            listOf(SyncEffect("favorite", manga, SyncField.FAVORITE, SyncEffectKind.ADD)),
            SyncOrigin.USER, batchId = "batch",
        )
        val raw = Json.parseToJsonElement(SyncBatchCodec.encode(listOf(event), "batch", "space", 1)) as JsonObject
        return JsonObject(raw + ("objects" to JsonArray(descriptors.toList()))).toString()
    }
}
