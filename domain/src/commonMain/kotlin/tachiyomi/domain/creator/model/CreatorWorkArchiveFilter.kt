package tachiyomi.domain.creator.model

/** View-only filtering keeps canonical identities and stored totals intact. */
data class CreatorWorkArchiveFilter(val query: String = "", val sourceId: Long? = null) {
    fun matches(group: WorkPresentationGroup): Boolean =
        (sourceId == null || group.members.any { it.naturalKey.sourceId == sourceId }) &&
            (
                query.isBlank() ||
                    group.title.contains(query, ignoreCase = true) ||
                    group.members.any { it.title.contains(query, ignoreCase = true) }
                )

    fun apply(archive: CreatorWorkArchive): CreatorWorkArchive {
        fun matches(version: SourceWorkArchiveVersion, groupTitle: String? = null): Boolean =
            (sourceId == null || version.naturalKey.sourceId == sourceId) &&
                (
                    version.title.contains(query, ignoreCase = true) ||
                        groupTitle?.contains(query, ignoreCase = true) == true
                    )
        return archive.copy(
            works = archive.works.mapNotNull { group ->
                group.copy(versions = group.versions.filter { matches(it, group.title) })
                    .takeIf { it.versions.isNotEmpty() }
            },
            pending = archive.pending.filter { matches(it) },
            rejected = archive.rejected.filter { matches(it) },
        )
    }
}
