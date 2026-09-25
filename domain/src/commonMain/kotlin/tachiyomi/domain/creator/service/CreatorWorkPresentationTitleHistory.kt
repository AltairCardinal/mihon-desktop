package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.WorkPresentationGroup
import tachiyomi.domain.library.service.LibraryPreferences

/** Remembers a valid source title for a creator's presentation group on this device. */
class CreatorWorkPresentationTitleHistory(
    private val preferences: LibraryPreferences?,
    private val creatorRootId: () -> Long,
    private val script: WorkTitleNormalizer.DisplayScript?,
) {
    private val sessionTitles = mutableMapOf<Pair<Long, String>, String>()

    fun get(groupKey: String): String? {
        val root = creatorRootId()
        sessionTitles[root to groupKey]?.let { return it }
        val prefix = "${groupKey.length}:$groupKey"
        val saved = try {
            preferences?.creatorWorkPresentationTitle(root, script, groupKey)?.get()
        } catch (_: Exception) {
            null
        }
        return saved
            ?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf(String::isNotBlank)
            ?.also { sessionTitles[root to groupKey] = it }
    }

    fun remember(groups: List<WorkPresentationGroup>) {
        val root = creatorRootId()
        groups.forEach { group ->
            val cacheKey = root to group.groupKey
            if (group.groupKey.startsWith("source:") || group.title.isBlank() ||
                sessionTitles[cacheKey] == group.title
            ) {
                return@forEach
            }
            sessionTitles[cacheKey] = group.title
            try {
                preferences?.creatorWorkPresentationTitle(root, script, group.groupKey)
                    ?.set("${group.groupKey.length}:${group.groupKey}${group.title}")
            } catch (_: Exception) {
                // Titles are derived display state. Keep the current session usable if local storage fails.
            }
        }
    }
}
