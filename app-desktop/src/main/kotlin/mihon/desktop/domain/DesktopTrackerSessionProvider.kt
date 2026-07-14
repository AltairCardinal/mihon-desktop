package mihon.desktop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.domain.track.service.TrackerServiceRegistry

/**
 * Desktop has no tracker-account session manager until Task 3B.
 * Restored track rows therefore remain unavailable to library filters.
 */
class DesktopTrackerSessionProvider(
    private val registry: TrackerServiceRegistry,
) : TrackerSessionProvider {
    override fun loggedInTrackerIds(): Flow<Set<Long>> {
        val profiles = registry.services.map { it.profile }
        if (profiles.isEmpty()) return flowOf(emptySet())
        return combine(profiles) { current ->
            current.filter { it.loggedIn }.mapTo(mutableSetOf()) { it.id }
        }
    }
}
