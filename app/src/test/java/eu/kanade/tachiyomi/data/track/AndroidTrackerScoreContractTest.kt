package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class AndroidTrackerScoreContractTest {
    @Test
    fun `actual Android tracker wrappers share the storage projection for every account format`() {
        val preferences = TrackPreferences(InMemoryPreferenceStore())
        Injekt.addSingleton(preferences)
        val ani = Anilist(2)
        val kitsu = Kitsu(3)
        val raw = Track(-1, 1, 2, 13, 44, "Rated", 0.0, 12, 1, 80.0, "", 0, 0, false)
        for (format in listOf(
            Anilist.POINT_100,
            Anilist.POINT_10,
            Anilist.POINT_5,
            Anilist.POINT_3,
            Anilist.POINT_10_DECIMAL,
        )) {
            preferences.anilistScoreType().set(format)
            assertEquals(8.0, ani.get10PointScore(raw))
            assertEquals(80.0, raw.score)
        }
        assertEquals(8.5, kitsu.get10PointScore(raw.copy(trackerId = 3, score = 8.5)))
        assertEquals(0.0, kitsu.get10PointScore(raw.copy(trackerId = 3, score = 0.0)))
    }
}
