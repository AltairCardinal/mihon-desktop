package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.domain.track.service.TrackerProviderContracts

fun Track.toShikimoriStatus(): String {
    if (status !in Shikimori.READING..Shikimori.REREADING) throw NotImplementedError("Unknown status: $status")
    return TrackerProviderContracts.shikimori.statusToWire(status)
}

fun toTrackStatus(status: String): Long {
    if (status !in setOf("watching", "completed", "on_hold", "dropped", "planned", "rewatching")) {
        throw NotImplementedError("Unknown status: $status")
    }
    return TrackerProviderContracts.shikimori.wireToStatus(status)
}
