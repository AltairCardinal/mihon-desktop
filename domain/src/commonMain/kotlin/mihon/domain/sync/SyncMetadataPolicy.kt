package mihon.domain.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ReaderChapterOpeningMode

/** Whole-value registers reuse the causal frontier and USER precedence of the shared reducer. */
object SyncMetadataPolicy {
    fun choose(projection: SyncProjection): SyncEffect? = effectiveSyncHeads(projection.heads, projection.metadata)
        .maxWithOrNull(compareBy<SyncEffectRef> { projection.metadata[it]?.occurredAt ?: 0 }.thenBy { it.stableKey })
        ?.let(projection.effectsByRef::get)

    fun categoryPayload(names: Collection<String>): JsonObject = buildJsonObject {
        put("names", JsonArray(names.distinct().sorted().map(::JsonPrimitive)))
    }

    fun categoryNames(payload: JsonObject): List<String> {
        require(payload.keys == setOf("names"))
        val values = payload["names"] as? JsonArray ?: error("category names must be an array")
        require(values.size <= 256)
        val names = values.map {
            val name = it as? JsonPrimitive ?: error("category name must be a string")
            require(name.isString && name.content.isNotBlank() && name.content.length <= 512)
            name.content
        }
        require(names.distinct().size == names.size)
        return names
    }

    fun pairingPayload(record: ChapterPairingRecord): JsonObject = buildJsonObject {
        put("formatVersion", ChapterPairingRecord.FORMAT_VERSION)
        put("pageCount", record.pageCount)
        put("openingMode", record.openingMode.name)
        put("forcedSinglePages", JsonArray(record.forcedSinglePages.sorted().map(::JsonPrimitive)))
        put("manualPairStarts", JsonArray(record.manualPairStarts.sorted().map(::JsonPrimitive)))
    }

    fun pairingRecord(payload: JsonObject): ChapterPairingRecord {
        require(
            payload.keys == setOf("formatVersion", "pageCount", "openingMode", "forcedSinglePages", "manualPairStarts"),
        )
        fun integer(value: kotlinx.serialization.json.JsonElement?): Int {
            val primitive = value as? JsonPrimitive ?: error("layout integer is missing")
            require(!primitive.isString)
            return requireNotNull(primitive.content.toIntOrNull())
        }
        fun pages(name: String): Set<Int> {
            val values = payload[name] as? JsonArray ?: error("layout pages must be an array")
            val pages = values.map(::integer)
            require(pages.distinct().size == pages.size)
            return pages.toSet()
        }
        val mode = payload["openingMode"] as? JsonPrimitive ?: error("opening mode is missing")
        require(mode.isString)
        val record = ChapterPairingRecord(
            integer(payload["formatVersion"]).toLong(),
            integer(payload["pageCount"]),
            pages("forcedSinglePages"),
            openingMode = ReaderChapterOpeningMode.valueOf(mode.content),
            manualPairStarts = pages("manualPairStarts"),
        )
        require(record.formatVersion == ChapterPairingRecord.FORMAT_VERSION && record.pageCount > 0)
        require(isClear(record) || record.isValidFor(record.pageCount))
        return record
    }

    fun isClear(record: ChapterPairingRecord): Boolean = record.forcedSinglePages.isEmpty() &&
        record.manualPairStarts.isEmpty() && record.openingMode == ReaderChapterOpeningMode.AUTO
}
