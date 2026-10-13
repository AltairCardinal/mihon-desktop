package tachiyomi.domain.library.service

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import tachiyomi.domain.library.model.LibraryManga

@Serializable
enum class LibraryUpdateSkipReason {
    ONLY_FETCH_ONCE,
    COMPLETED,
    HAS_UNREAD,
    NOT_STARTED,
    OUTSIDE_RELEASE_PERIOD,
}

/** SOURCE order and zero-chapter exception, shared by the actual library update consumers. */
fun libraryUpdateSkipReason(
    entry: LibraryManga,
    restrictions: Set<String>,
    fetchWindowUpperBound: Long,
): LibraryUpdateSkipReason? = when {
    entry.manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && entry.totalChapters > 0 ->
        LibraryUpdateSkipReason.ONLY_FETCH_ONCE
    LibraryPreferences.MANGA_NON_COMPLETED in restrictions && entry.manga.status.toInt() == SManga.COMPLETED ->
        LibraryUpdateSkipReason.COMPLETED
    LibraryPreferences.MANGA_HAS_UNREAD in restrictions && entry.unreadCount != 0L ->
        LibraryUpdateSkipReason.HAS_UNREAD
    LibraryPreferences.MANGA_NON_READ in restrictions && entry.totalChapters > 0 && !entry.hasStarted ->
        LibraryUpdateSkipReason.NOT_STARTED
    LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in restrictions && entry.manga.nextUpdate > fetchWindowUpperBound ->
        LibraryUpdateSkipReason.OUTSIDE_RELEASE_PERIOD
    else -> null
}

/** Only the four user-editable smart rules may be bypassed; source eligibility stays unconditional. */
fun effectiveLibraryUpdateRestrictions(
    restrictions: Set<String>,
    manualOverrides: Set<String>,
    isManual: Boolean,
): Set<String> = if (isManual) {
    restrictions - manualOverrides.intersect(
        setOf(
            LibraryPreferences.MANGA_NON_COMPLETED,
            LibraryPreferences.MANGA_HAS_UNREAD,
            LibraryPreferences.MANGA_NON_READ,
            LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
        ),
    )
} else {
    restrictions
}

/** Save the two drafts with best-effort restoration; the preference store has no cross-key transaction. */
fun LibraryPreferences.saveSmartUpdateSettings(restrictions: Set<String>, manualOverrides: Set<String>): Boolean {
    val rules = autoUpdateMangaRestrictions()
    val overrides = manualUpdateMangaRestrictionOverrides()
    val previous = try {
        (rules.get() to rules.isSet()) to (overrides.get() to overrides.isSet())
    } catch (_: Exception) {
        return false
    }
    return try {
        if (previous.first.first != restrictions) rules.set(restrictions)
        if (previous.second.first != manualOverrides) overrides.set(manualOverrides)
        true
    } catch (_: Exception) {
        runCatching { if (previous.first.second) rules.set(previous.first.first) else rules.delete() }
        runCatching { if (previous.second.second) overrides.set(previous.second.first) else overrides.delete() }
        false
    }
}
