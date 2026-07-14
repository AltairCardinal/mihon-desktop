package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.domain.track.service.TrackerProviderContracts

fun Track.toApiStatus(): String {
    if (status !in Kitsu.READING..Kitsu.PLAN_TO_READ) throw Exception("Unknown status")
    return TrackerProviderContracts.kitsu.statusToWire(status)
}

fun Track.toApiScore(): String? {
    return if (score > 0) (score * 2).toInt().toString() else null
}
