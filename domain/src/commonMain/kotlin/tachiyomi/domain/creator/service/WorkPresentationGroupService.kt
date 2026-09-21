package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkPresentationGroup

/**
 * Builds the author-page work presentation projection without changing archive facts.
 *
 * The input archive is already scoped to one active creator. Confirmed canonical groups are
 * always retained as separate groups. Only unconfirmed pending versions may be attached to one
 * matching canonical group or grouped by one strict presentation key.
 */
object WorkPresentationGroupService {
    private const val TITLE_GROUP_PREFIX = "title:"
    private const val CANONICAL_GROUP_PREFIX = "canonical:"
    private const val SOURCE_GROUP_PREFIX = "source:"

    fun project(
        archive: CreatorWorkArchive,
        excludedNaturalKeys: Set<SourceWorkNaturalKey> = emptySet(),
        previousTitles: Map<String, String> = emptyMap(),
        preferredLanguageTags: Set<String> = emptySet(),
        preferredDisplayScript: WorkTitleNormalizer.DisplayScript? = null,
    ): List<WorkPresentationGroup> {
        val preferredLanguages = preferredLanguageTags.mapTo(mutableSetOf()) { it.lowercase() }
        val standalone = mutableListOf<SourceWorkArchiveVersion>()
        val canonicalBuckets = archive.works.mapNotNull { group ->
            val members = group.versions.distinctBy(SourceWorkArchiveVersion::naturalKey)
            standalone += members.filter { it.naturalKey in excludedNaturalKeys }
            members.filterNot { it.naturalKey in excludedNaturalKeys }
                .takeIf { it.isNotEmpty() }
                ?.let { included ->
                    CanonicalBucket(source = group, members = included.toMutableList())
                }
        }
        val unmatched = mutableListOf<PendingVersion>()

        archive.pending
            .filterNot { it.decision?.decision?.state == WorkDecisionState.REJECTED }
            .forEach { version ->
                if (version.naturalKey in excludedNaturalKeys) {
                    standalone += version
                    return@forEach
                }

                val key = presentationKey(version.title)
                val matches = if (key == null) {
                    emptyList()
                } else {
                    canonicalBuckets.filter { bucket -> key in bucket.presentationKeys() }
                }
                when {
                    matches.size == 1 -> {
                        val bucket = matches.single()
                        if (bucket.members.none { it.naturalKey == version.naturalKey }) {
                            bucket.members += version
                        }
                    }
                    else -> unmatched += PendingVersion(version, key)
                }
            }

        val groups = canonicalBuckets.map { bucket ->
            val members = sortedMembers(bucket.members)
            WorkPresentationGroup(
                groupKey = "$CANONICAL_GROUP_PREFIX${bucket.source.portableKey}",
                title = chooseTitle(
                    groupKey = "$CANONICAL_GROUP_PREFIX${bucket.source.portableKey}",
                    members = members,
                    previousTitles = previousTitles,
                    preferredLanguages = preferredLanguages,
                    preferredDisplayScript = preferredDisplayScript,
                    fallback = bucket.source.title.ifBlank { members.first().title },
                ),
                members = members,
                canonicalWorkId = bucket.source.workId,
                representative = representative(members, preferredLanguages),
            )
        }.toMutableList()

        unmatched
            .filter { it.key == null }
            .map(PendingVersion::version)
            .forEach(standalone::add)
        unmatched
            .filter { it.key != null }
            .groupBy { checkNotNull(it.key) }
            .forEach { (key, versions) ->
                val distinctTitles = versions
                    .map(PendingVersion::version)
                    .map(SourceWorkArchiveVersion::title)
                    .filter(String::isNotBlank)
                    .distinct()
                if (distinctTitles.size < 2) {
                    standalone += versions.map(PendingVersion::version)
                } else {
                    val members = sortedMembers(versions.map(PendingVersion::version))
                    val groupKey = "$TITLE_GROUP_PREFIX$key"
                    groups += WorkPresentationGroup(
                        groupKey = groupKey,
                        title = chooseTitle(
                            groupKey,
                            members,
                            previousTitles,
                            preferredLanguages,
                            preferredDisplayScript,
                            fallback = null,
                        ),
                        members = members,
                        canonicalWorkId = null,
                        representative = representative(members, preferredLanguages),
                    )
                }
            }

        standalone
            .distinctBy(SourceWorkArchiveVersion::naturalKey)
            .sortedWith(memberComparator())
            .forEach { version ->
                groups += WorkPresentationGroup(
                    groupKey = sourceGroupKey(version.naturalKey),
                    title = version.title,
                    members = listOf(version),
                    canonicalWorkId = null,
                    representative = version,
                )
            }

        return groups
    }

    private fun presentationKey(title: String): String? {
        if (title.isBlank()) return null
        return WorkTitleNormalizer.normalizeForPresentationGroup(title).takeIf(String::isNotBlank)
    }

    private fun chooseTitle(
        groupKey: String,
        members: List<SourceWorkArchiveVersion>,
        previousTitles: Map<String, String>,
        preferredLanguages: Set<String>,
        preferredDisplayScript: WorkTitleNormalizer.DisplayScript?,
        fallback: String?,
    ): String {
        val previous = previousTitles[groupKey]
        if (previous != null && members.any { it.title == previous } &&
            (
                preferredDisplayScript == null ||
                    WorkTitleNormalizer.matchesDisplayScript(previous, preferredDisplayScript)
                )
        ) {
            return previous
        }
        val candidates = if (preferredDisplayScript == null) {
            members
        } else {
            members.filter { WorkTitleNormalizer.matchesDisplayScript(it.title, preferredDisplayScript) }
                .ifEmpty { members }
        }
        return candidates
            .sortedWith(titleComparator(preferredLanguages))
            .first()
            .title
            .ifBlank { fallback ?: members.first().title }
    }

    private fun representative(
        members: List<SourceWorkArchiveVersion>,
        preferredLanguages: Set<String>,
    ): SourceWorkArchiveVersion = members.minWithOrNull(
        compareByDescending<SourceWorkArchiveVersion> { it.inLibrary }
            .thenByDescending { it.unread }
            .thenByDescending { it.readingLanguage.tag.lowercase() in preferredLanguages }
            .thenByDescending { it.thumbnailUrl != null }
            .thenByDescending { it.latestChapterAt ?: Long.MIN_VALUE }
            .thenByDescending { it.publishedDateAt ?: Long.MIN_VALUE }
            .thenBy { it.naturalKey.sourceId }
            .thenBy { it.naturalKey.stableSourceUrl },
    ) ?: error("A presentation group must have at least one member")

    private fun sortedMembers(members: List<SourceWorkArchiveVersion>): List<SourceWorkArchiveVersion> =
        members.distinctBy(SourceWorkArchiveVersion::naturalKey).sortedWith(memberComparator())

    private fun memberComparator() = compareBy<SourceWorkArchiveVersion> {
        it.naturalKey.sourceId
    }.thenBy { it.naturalKey.stableSourceUrl }

    private fun titleComparator(preferredLanguages: Set<String>) =
        compareByDescending<SourceWorkArchiveVersion> {
            it.readingLanguage.tag.lowercase() in preferredLanguages
        }.thenBy { it.naturalKey.sourceId }
            .thenBy { it.naturalKey.stableSourceUrl }

    private fun sourceGroupKey(key: SourceWorkNaturalKey): String =
        "$SOURCE_GROUP_PREFIX${key.sourceId}:${key.stableSourceUrl}"

    private data class CanonicalBucket(
        val source: CanonicalWorkArchiveGroup,
        val members: MutableList<SourceWorkArchiveVersion>,
    ) {
        fun presentationKeys(): Set<String> = buildSet {
            source.title.let(::presentationKey)?.let(::add)
            members.mapNotNullTo(this) { presentationKey(it.title) }
        }
    }

    private data class PendingVersion(
        val version: SourceWorkArchiveVersion,
        val key: String?,
    )
}
