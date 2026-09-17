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
    fun `actual Android restore respects settings exclusion and reports incompatible frequency`() = runBlocking<Unit> {
        mockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        try {
            every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
            every { BackupCreateJob.setupTask(any(), any()) } returns Unit
            val app = RuntimeEnvironment.getApplication()
            val store = AndroidPreferenceStore(app)
            val key = tachiyomi.domain.creator.service.CreatorDiscoveryPreferences.FREQUENCY_KEY
            store.getString(key).set("weekly")
            val backup = eu.kanade.tachiyomi.data.backup.models.Backup(
                emptyList(),
                backupPreferences = listOf(
                    BackupPreference(key, StringPreferenceValue("future")),
                ),
            )
            val bytes = tachiyomi.data.backup.BackupCodec.encode(
                eu.kanade.tachiyomi.data.backup.models.Backup.serializer(),
                backup,
            )
            for (include in listOf(false, true)) {
                val context = mockk<android.content.Context>(relaxed = true)
                val resolver = mockk<android.content.ContentResolver>()
                val uri = mockk<android.net.Uri>()
                every { context.contentResolver } returns resolver
                every { resolver.openInputStream(uri) } answers { bytes.inputStream() }
                val notifier = mockk<eu.kanade.tachiyomi.data.backup.BackupNotifier>(relaxed = true)
                BackupRestorer(
                    context, notifier, false,
                    categoriesRestorer = mockk(
                        relaxed = true,
                    ),
                    preferenceRestorer = PreferenceRestorer(app, mockk(), store),
                    extensionRepoRestorer = mockk(relaxed = true), mangaRestorer = mockk(relaxed = true),
                    authorArchiveBackupContributor = mockk(relaxed = true),
                    backupRestoreSync = mihon.data.sync.journal.NoopBackupRestoreSync,
                ).restore(
                    uri,
                    RestoreOptions(
                        libraryEntries = false,
                        categories = false,
                        appSettings = include,
                        extensionRepoSettings = false,
                        sourceSettings = false,
                    ),
                )
                io.mockk.verify { notifier.showRestoreComplete(any(), if (include) 1 else 0, any(), any(), false) }
                assertEquals("weekly", store.getString(key).get())
            }
        } finally {
            unmockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        }
    }

    @Test
    fun `creator frequency uses shared backup compatibility contract`() = runBlocking<Unit> {
        mockkObject(LibraryUpdateJob.Companion, BackupCreateJob.Companion)
        try {
            every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
            every { BackupCreateJob.setupTask(any(), any()) } returns Unit
            val context = RuntimeEnvironment.getApplication()
            val store = AndroidPreferenceStore(context)
            val restorer = PreferenceRestorer(context, mockk(), store)
            tachiyomi.data.creator.verifyCreatorFrequencyBackup(store) { values ->
                restorer.restoreApp(
                    values.map {
                        BackupPreference(it.first, StringPreferenceValue(it.second))
                    },
                    null,
                ).isNotEmpty()
            }
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
