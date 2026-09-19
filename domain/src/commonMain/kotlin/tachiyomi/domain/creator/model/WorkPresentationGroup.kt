package tachiyomi.domain.creator.model

/**
 * A view-only grouping of source versions for an active creator archive.
 *
 * [canonicalWorkId] is set only for an already confirmed canonical archive group. A null value
 * means the group was derived from the current source titles and must not be persisted as a work
 * decision.
 */
data class WorkPresentationGroup(
    val groupKey: String,
    val title: String,
    val members: List<SourceWorkArchiveVersion>,
    val canonicalWorkId: Long?,
    val representative: SourceWorkArchiveVersion,
) {
    init {
        require(groupKey.isNotBlank())
        require(members.isNotEmpty())
        require(representative in members)
    }

    /** Complete source-version count; source filtering must not rewrite this projection. */
    val sourceCount: Int
        get() = members.size

    val inLibrary: Boolean
        get() = members.any(SourceWorkArchiveVersion::inLibrary)

    val unread: Boolean
        get() = members.any(SourceWorkArchiveVersion::unread)

    /** Earliest retained first-seen timestamp; dates are not summed across sources. */
    val firstSeenAt: Long?
        get() = members.mapNotNull { it.firstSeenAt.takeIf { firstSeen -> firstSeen > 0L } }.minOrNull()

    /** Work and chapter dates remain those of the selected representative version. */
    val publishedDateAt: Long?
        get() = representative.publishedDateAt

    val latestChapterAt: Long?
        get() = representative.latestChapterAt
}
