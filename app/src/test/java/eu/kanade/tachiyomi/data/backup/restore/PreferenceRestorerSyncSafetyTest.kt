package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
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
