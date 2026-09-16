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
    fun `suggestion choices persist locally and both backup directions exclude them`() = runTest {
        val node = java.util.prefs.Preferences.userRoot().node("/mihon-eis-test-${java.util.UUID.randomUUID()}")
        try {
            val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node.node("first"))
            val preferences = mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(store)
            preferences.expanded.set(false)
            preferences.ignored.set("local-identity")
            val restarted = mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(
                tachiyomi.core.common.preference.DesktopPreferenceStore(node.node("first")))
            assertFalse(restarted.expanded.get())
            assertEquals("local-identity", restarted.ignored.get())
            val backup = DesktopBackupCreator.createFromDatabase(
                mangaRepository = FakeMangaRepository(), chapterRepository = FakeChapterRepository(),
                categoryRepository = FakeCategoryRepository(), historyRepository = FakeHistoryRepository(),
                trackRepository = emptyTrackRepository(), preferenceStore = store,
                sourcePreferenceStore = { preferenceStoreOf() }, extensionRepoRepository = FakeExtensionRepoRepository(),
            )
            assertTrue(backup.backupPreferences.none { it.key in setOf(preferences.expanded.key(), preferences.ignored.key()) })
            val foreign = backup.copy(backupPreferences = listOf(
                mihon.desktop.backup.models.BackupPreference(preferences.expanded.key(),
                    mihon.desktop.backup.models.BooleanPreferenceValue(true)),
                mihon.desktop.backup.models.BackupPreference(preferences.ignored.key(), StringPreferenceValue("foreign")),
            ))
            val secondStore = tachiyomi.core.common.preference.DesktopPreferenceStore(node.node("second"))
            for (target in listOf(store, secondStore)) {
                DesktopBackupRestorer(FakeMangaRepository(), FakeChapterRepository(), FakeCategoryRepository(),
                    FakeHistoryRepository(), preferenceStore = target).restore(foreign)
            }
            assertFalse(preferences.expanded.get())
            assertEquals("local-identity", preferences.ignored.get())
            val second = mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(secondStore)
            assertTrue(second.expanded.get())
            assertEquals("", second.ignored.get())
        } finally { node.removeNode() }
    }

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
