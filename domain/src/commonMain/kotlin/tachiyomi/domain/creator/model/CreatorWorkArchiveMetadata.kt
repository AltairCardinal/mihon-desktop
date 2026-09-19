package tachiyomi.domain.creator.model

/**
 * How much of a source work's chapter catalogue has been observed and persisted.
 *
 * UNKNOWN is intentionally distinct from an empty complete catalogue: an empty list can be a
 * valid source response, while UNKNOWN means that no complete catalogue observation is available.
 */
enum class ChapterCatalogCompleteness {
    UNKNOWN,
    PARTIAL,
    COMPLETE,
}

object CreatorWorkDatePolicy {
    fun firstSeenAt(existing: Long?, observedAt: Long): Long = existing ?: observedAt

    fun earliestFirstSeenAt(values: Iterable<Long>): Long? = values
        .filter { it > 0L }
        .minOrNull()
}
