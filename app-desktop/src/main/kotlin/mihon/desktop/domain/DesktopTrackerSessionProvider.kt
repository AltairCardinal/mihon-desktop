package mihon.desktop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.track.service.TrackerSessionProvider

/**
 * Desktop has no tracker-account session manager until Task 3B.
 * Restored track rows therefore remain unavailable to library filters.
 */
class DesktopTrackerSessionProvider : TrackerSessionProvider {
    override fun loggedInTrackerIds(): Flow<Set<Long>> = flowOf(emptySet())
}
