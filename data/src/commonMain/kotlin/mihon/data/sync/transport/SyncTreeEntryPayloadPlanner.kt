package mihon.data.sync.transport

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Plans Git tree entries before any blob is created. UTF-8 bytes can be sent
 * inline; binary bytes keep the blob path. The final request budget is checked
 * before selecting inline content so a large JSON body falls back safely.
 */
internal data class SyncTreeEntryPayload(
    val path: String,
    val bytes: ByteArray,
    val inlineContent: String?,
)

internal object SyncTreeEntryPayloadPlanner {
    private const val DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024

    fun plan(
        entries: List<Pair<String, ByteArray>>,
        maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
        baseTreeSha: String? = null,
    ): List<SyncTreeEntryPayload> {
        val planned = entries.map { (path, bytes) ->
            val inline = bytes.decodeToString().takeIf { it.encodeToByteArray().contentEquals(bytes) }
            SyncTreeEntryPayload(path, bytes, inline)
        }
        val estimatedBodyBytes = buildJsonObject {
            baseTreeSha?.let { put("base_tree", it) }
            put(
                "tree",
                buildJsonArray {
                    planned.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("path", entry.path)
                                put("mode", "100644")
                                put("type", "blob")
                                if (entry.inlineContent != null) {
                                    put("content", entry.inlineContent)
                                } else {
                                    put("sha", "0".repeat(64))
                                }
                            },
                        )
                    }
                },
            )
        }.toString().encodeToByteArray().size
        return if (estimatedBodyBytes <= maxBodyBytes) planned else planned.map { it.copy(inlineContent = null) }
    }
}
