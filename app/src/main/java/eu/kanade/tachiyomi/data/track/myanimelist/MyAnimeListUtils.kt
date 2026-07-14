package eu.kanade.tachiyomi.data.track.myanimelist

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.domain.track.service.TrackerProviderContracts

fun Track.toMyAnimeListStatus() = if (status in setOf(1L, 2L, 3L, 4L, 6L, 7L)) {
    TrackerProviderContracts.myAnimeList.statusToWire(status)
} else {
    null
}

fun getStatus(status: String?) = status?.let(TrackerProviderContracts.myAnimeList::wireToStatus) ?: MyAnimeList.READING
