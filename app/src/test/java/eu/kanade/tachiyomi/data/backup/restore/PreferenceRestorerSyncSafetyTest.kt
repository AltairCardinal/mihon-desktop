package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PreferenceRestorerSyncSafetyTest {
    @Test
    fun `suggestion preferences survive store restart but neither export nor restore across devices`() = runBlocking {
        mockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        try {
            every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
            every { BackupCreateJob.setupTask(any(), any()) } returns Unit
            val context = RuntimeEnvironment.getApplication()
            val firstFile = context.getSharedPreferences("suggestions-first", 0)
            val secondFile = context.getSharedPreferences("suggestions-second", 0)
            val store = AndroidPreferenceStore(context, firstFile)
            val preferences = mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(store)
            preferences.expanded.set(false)
            preferences.ignored.set("local-identity")
            val restarted = mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(
                AndroidPreferenceStore(context, firstFile),
            )
            assertEquals(false, restarted.expanded.get())
            assertEquals("local-identity", restarted.ignored.get())
            val exported = eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator(mockk(), store)
                .createApp(includePrivatePreferences = true)
            assertEquals(
                false,
                exported.any {
                    it.key == preferences.expanded.key() || it.key ==
                        preferences.ignored.key()
                },
            )
            val foreign = listOf(
                BackupPreference(preferences.expanded.key(), BooleanPreferenceValue(true)),
                BackupPreference(preferences.ignored.key(), StringPreferenceValue("foreign-identity")),
            )
            PreferenceRestorer(context, mockk(), store).restoreApp(foreign, null)
            assertEquals(false, preferences.expanded.get())
            assertEquals("local-identity", preferences.ignored.get())
            val secondStore = AndroidPreferenceStore(context, secondFile)
            PreferenceRestorer(context, mockk(), secondStore).restoreApp(foreign, null)
            val second = mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(secondStore)
            assertEquals(true, second.expanded.get())
            assertEquals("", second.ignored.get())
        } finally {
            unmockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        }
    }

    @Test
    fun `ordinary settings restore excludes app state and preserves normal preferences`() = runBlocking {
        mockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        try {
            every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
            every { BackupCreateJob.setupTask(any(), any()) } returns Unit
            val context = RuntimeEnvironment.getApplication()
            val store = AndroidPreferenceStore(context)
            val stateKey = Preference.appStateKey("sync-machine-state")
            store.getString(stateKey).set("local")
            store.getString("reader-theme").set("before")
            PreferenceRestorer(context, mockk(), store).restoreApp(
                listOf(
                    BackupPreference(stateKey, StringPreferenceValue("foreign")),
                    BackupPreference("reader-theme", StringPreferenceValue("after")),
                ),
                null,
            )
            assertEquals("local", store.getString(stateKey).get())
            assertEquals("after", store.getString("reader-theme").get())
        } finally {
            unmockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        }
    }
}
