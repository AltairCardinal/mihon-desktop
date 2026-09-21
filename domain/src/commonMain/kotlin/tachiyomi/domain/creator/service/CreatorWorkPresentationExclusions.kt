package tachiyomi.domain.creator.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

/**
 * Device-local corrections for the author-page presentation projection.
 *
 * Entries are scoped to the active creator root and store the source natural key exactly. The
 * encoded representation is deliberately opaque to backup/sync code and does not reuse work
 * decision states.
 */
class CreatorWorkPresentationExclusions(
    private val preference: Preference<Set<String>>,
) {
    fun entries(): Map<Long, Set<SourceWorkNaturalKey>> = preference.get()
        .mapNotNull { value -> decode(value) }
        .groupBy(Entry::creatorRootId)
        .mapValues { (_, values) -> values.mapTo(linkedSetOf(), Entry::sourceWork) }

    fun clear() = preference.set(emptySet())

    fun remove(entries: Map<Long, Set<SourceWorkNaturalKey>>) {
        val encoded = entries.flatMap { (creatorRootId, sourceWorks) ->
            sourceWorks.map { sourceWork -> encode(Entry(creatorRootId, sourceWork)) }
        }.toSet()
        if (encoded.isEmpty()) return
        update { values -> values - encoded }
    }

    fun get(creatorRootId: Long): Set<SourceWorkNaturalKey> = preference.get()
        .mapNotNull(::decode)
        .filterTo(linkedSetOf()) { it.creatorRootId == creatorRootId }
        .mapTo(linkedSetOf(), Entry::sourceWork)

    fun exclude(creatorRootId: Long, sourceWork: SourceWorkNaturalKey) {
        update { it + encode(Entry(creatorRootId, sourceWork)) }
    }

    fun restore(creatorRootId: Long, sourceWork: SourceWorkNaturalKey) {
        update { values ->
            values - encode(Entry(creatorRootId, sourceWork))
        }
    }

    fun migrateRoot(sourceRootId: Long, targetRootId: Long) {
        require(sourceRootId != targetRootId)
        update { values ->
            values.mapTo(linkedSetOf()) { value ->
                decode(value)?.takeIf { it.creatorRootId == sourceRootId }
                    ?.copy(creatorRootId = targetRootId)
                    ?.let(::encode)
                    ?: value
            }
        }
    }

    fun moveMembers(
        sourceRootId: Long,
        targetRootId: Long,
        sourceWorks: Set<SourceWorkNaturalKey>,
    ) {
        require(sourceRootId != targetRootId)
        if (sourceWorks.isEmpty()) return
        update { values ->
            values.mapTo(linkedSetOf()) { value ->
                decode(value)?.takeIf {
                    it.creatorRootId == sourceRootId && it.sourceWork in sourceWorks
                }?.copy(creatorRootId = targetRootId)?.let(::encode) ?: value
            }
        }
    }

    /** Removes entries that no longer belong to the active creator projection. */
    fun prune(creatorRootId: Long, validSourceWorks: Set<SourceWorkNaturalKey>) {
        update { values ->
            values.filterTo(linkedSetOf()) { value ->
                val entry = decode(value)
                entry == null || entry.creatorRootId != creatorRootId || entry.sourceWork in validSourceWorks
            }
        }
    }

    private fun update(transform: (Set<String>) -> Set<String>) {
        val previous = preference.get()
        preference.set(transform(previous))
    }

    private data class Entry(
        val creatorRootId: Long,
        val sourceWork: SourceWorkNaturalKey,
    )

    private fun encode(entry: Entry): String = buildString {
        append(VERSION)
        append('|')
        append(entry.creatorRootId)
        append('|')
        append(entry.sourceWork.sourceId)
        append('|')
        append(entry.sourceWork.stableSourceUrl.encodeHex())
    }

    private fun decode(value: String): Entry? {
        val parts = value.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != VERSION) return null
        val rootId = parts[1].toLongOrNull() ?: return null
        val sourceId = parts[2].toLongOrNull() ?: return null
        val url = parts[3].decodeHex() ?: return null
        return runCatching { Entry(rootId, SourceWorkNaturalKey(sourceId, url)) }.getOrNull()
    }

    private fun String.encodeHex(): String = buildString(length * 2) {
        for (byte in encodeToByteArray()) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun String.decodeHex(): String? {
        if (length % 2 != 0) return null
        val bytes = ByteArray(length / 2)
        for (index in bytes.indices) {
            val high = HEX.indexOf(this[index * 2])
            val low = HEX.indexOf(this[index * 2 + 1])
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return runCatching { bytes.decodeToString() }.getOrNull()
    }

    private companion object {
        const val VERSION = "v1"
        const val HEX = "0123456789ABCDEF"
    }
}
