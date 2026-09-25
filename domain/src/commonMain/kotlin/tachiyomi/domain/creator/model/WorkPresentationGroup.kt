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

    /** Preserve the complete group's order and source identities independently of card filtering. */
    fun sourceNames(resolve: (Long) -> String): List<String> = members
        .distinctBy { it.naturalKey.sourceId }
        .map { version ->
            val sourceId = version.naturalKey.sourceId
            resolve(sourceId).ifBlank { sourceId.toString() }
        }

    val inLibrary: Boolean
        get() = members.any(SourceWorkArchiveVersion::inLibrary)

    val unread: Boolean
        get() = members.any(SourceWorkArchiveVersion::unread)

    /** Earliest retained first-seen timestamp; dates are not summed across sources. */
    val firstSeenAt: Long?
        get() = members.mapNotNull { it.firstSeenAt.takeIf { firstSeen -> firstSeen > 0L } }.minOrNull()

    /** The earliest retained publication date among current members, including evidence under review. */
    val publishedDateAt: Long?
        get() = members
            .mapNotNull(SourceWorkArchiveVersion::publishedDateAt)
            .minOrNull()

    val publishedDateQuality: SourceDateQualityStatus
        get() = when {
            publishedDateAt != null -> if (members.any {
                    it.publishedDateAt == publishedDateAt && it.publishedDateQuality != SourceDateQualityStatus.TRUSTED
                }
            ) {
                SourceDateQualityStatus.SUSPECT
            } else {
                SourceDateQualityStatus.TRUSTED
            }
            members.any { it.publishedDateQuality == SourceDateQualityStatus.SUSPECT } ->
                SourceDateQualityStatus.SUSPECT
            else -> SourceDateQualityStatus.UNKNOWN
        }

    val firstSeenDate: String?
        get() = members.mapNotNull { it.firstSeenDate?.takeIf(String::isNotBlank) }.minOrNull()

    val latestChapterAt: Long?
        get() = representative.latestChapterAt
}
