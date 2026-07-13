package tachiyomi.domain.track.service

import kotlinx.coroutines.flow.Flow

/** Platform bridge for the tracker accounts that are currently authenticated. */
fun interface TrackerSessionProvider {
    fun loggedInTrackerIds(): Flow<Set<Long>>
}
