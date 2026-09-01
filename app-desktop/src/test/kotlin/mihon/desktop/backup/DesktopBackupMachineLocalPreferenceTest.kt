package mihon.desktop.backup

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.backup.models.StringPreferenceValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

class DesktopBackupMachineLocalPreferenceTest {

    @Test
    fun `desktop backup keeps ordinary preferences and excludes machine local app state`() = runTest {
        val downloadDirectoryKey = Preference.appStateKey("download_directory")
        val appPreferences = preferenceStoreOf(
            "theme" to "dark",
            downloadDirectoryKey to "D:\\Manga\\Downloads",
        )

        val backup = DesktopBackupCreator.createFromDatabase(
            mangaRepository = FakeMangaRepository(),
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            trackRepository = emptyTrackRepository(),
            preferenceStore = appPreferences,
            sourcePreferenceStore = { preferenceStoreOf() },
            extensionRepoRepository = FakeExtensionRepoRepository(),
        )

        assertEquals(StringPreferenceValue("dark"), backup.backupPreferences.single { it.key == "theme" }.value)
        assertFalse(backup.backupPreferences.any { it.key == downloadDirectoryKey })
        assertTrue(backup.backupPreferences.none { Preference.isAppState(it.key) })
    }

    private fun preferenceStoreOf(vararg entries: Pair<String, Any>): PreferenceStore {
        val delegate = InMemoryPreferenceStore()
        return object : PreferenceStore by delegate {
            override fun getAll(): Map<String, *> = entries.toMap()
        }
    }

    private fun emptyTrackRepository() = object : TrackRepository {
        override suspend fun getTrackById(id: Long): Track? = null
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = emptyList()
        override fun getTracksAsFlow() = flowOf(emptyList<Track>())
        override fun getTracksByMangaIdAsFlow(mangaId: Long) = flowOf(emptyList<Track>())
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }
}
