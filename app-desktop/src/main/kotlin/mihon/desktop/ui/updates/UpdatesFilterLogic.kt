package mihon.desktop.ui.updates

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * Applies a client-side downloaded filter to a list of updates.
 *
 * SQL-level filters (unread, started, bookmarked, hideExcludedScanlators) are
 * passed directly to [GetUpdates.subscribe]; only the downloaded state is
 * checked here because the download cache lives outside the database.
 */
fun List<UpdatesWithRelations>.applyDownloadedFilter(
    filter: TriState,
    isDownloaded: (item: UpdatesWithRelations) -> Boolean,
): List<UpdatesWithRelations> = when (filter) {
    TriState.DISABLED -> this
    TriState.ENABLED_IS -> filter { isDownloaded(it) }
    TriState.ENABLED_NOT -> filter { !isDownloaded(it) }
}

/**
 * Returns true if any filter is active (i.e. the filter icon should be tinted).
 */
fun hasActiveUpdatesFilters(
    filterUnread: TriState,
    filterDownloaded: TriState,
    filterStarted: TriState,
    filterBookmarked: TriState,
    filterExcludedScanlators: Boolean,
): Boolean = listOf(filterUnread, filterDownloaded, filterStarted, filterBookmarked)
    .any { it != TriState.DISABLED } || filterExcludedScanlators
