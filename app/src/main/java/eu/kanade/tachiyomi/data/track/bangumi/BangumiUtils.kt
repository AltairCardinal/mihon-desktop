package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.domain.track.service.TrackerProviderContracts

fun Track.toApiStatus(): Int {
    if (status !in Bangumi.PLAN_TO_READ..Bangumi.DROPPED) throw NotImplementedError("Unknown status: $status")
    return TrackerProviderContracts.bangumi.statusToWire(status).toInt()
}
