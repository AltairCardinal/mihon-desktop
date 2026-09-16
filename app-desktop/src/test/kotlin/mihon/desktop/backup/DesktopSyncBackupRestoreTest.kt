package mihon.desktop.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorWatch
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.journal.SyncBackupRestorer
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupHistory
import mihon.desktop.backup.models.BackupManga
import mihon.desktop.backup.models.BackupPreference
import mihon.desktop.backup.models.StringPreferenceValue
import mihon.desktop.ui.settings.BackupRestoreUiState
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.backup.SqlDelightAuthorArchiveBackupContributor
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.history.model.HistoryUpdate
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.prefs.Preferences

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DesktopSyncBackupRestoreTest {
    @TempDir lateinit var directory: File

    @Test
    fun `restorer captures actual manga reading and author units as baseline`() = runBlocking {
        Storage().use { s ->
            s.connect()
            val backup = backup().copy(
                backupAuthorArchive = BackupAuthorArchiveSection(
                    creators = listOf(BackupCreatorIdentity("author-one", "ONE", "one")),
                    watches = listOf(BackupAuthorWatch("author-one", true, 60_000)),
                ),
            )
            assertFalse(s.restorer().restore(backup).hasErrors)
            val id = requireNotNull(s.text("SELECT import_id FROM sync_restore_runs"))
            assertEquals("COMPLETED", s.text("SELECT state FROM sync_restore_runs"))
            assertEquals(3L, s.baseline.process(id).total)
            val events = s.journal.pendingEvents("space", 1)
            assertEquals(
                setOf(SyncCategory.FAVORITE, SyncCategory.READING, SyncCategory.FOLLOW),
                events.map { it.category }.toSet(),
            )
            assertTrue(events.all { it.origin == SyncOrigin.BACKUP_RESTORE && it.actorId == "device" })
            assertTrue(events.flatMap { it.effects }.all { it.parents.isEmpty() })
        }
    }

    @Test
    fun `restore respects a local history clear and preserves later visible reading`() = runBlocking {
        Storage().use { s ->
            s.restorer().restore(backup())
            assertNotNull(s.history.getLastHistory())
            assertTrue(s.history.deleteAllHistory())
            s.restorer().restore(backup())
            assertNull(s.history.getLastHistory())
            val manga = requireNotNull(s.mangas.getMangaByUrlAndSourceId("/manga", 42))
            val chapter = s.chapters.getChapterByMangaId(manga.id).single()
            s.history.upsertHistory(HistoryUpdate(chapter.id, Date(100), 0))
            s.restorer().restore(backup())
            assertEquals(100L, s.history.getHistoryByMangaId(manga.id).single().readAt?.time)
        }
    }

    @Test
    fun `partial and cancelled restores retain only committed units`() = runBlocking {
        Storage().use { s ->
            s.connect()
            s.driver.execute(
                null,
                "CREATE TRIGGER reject_manga BEFORE INSERT ON mangas " +
                    "WHEN NEW.url = '/bad' BEGIN SELECT RAISE(ABORT, 'injected'); END",
                0,
            )
            val partial = backup().copy(
                backupManga = backup().backupManga +
                    BackupManga(source = 42, url = "/bad", title = "Bad", favorite = true),
            )
            assertTrue(s.restorer().restore(partial).hasErrors)
            assertEquals("PARTIAL", s.text("SELECT state FROM sync_restore_runs"))
            val id = requireNotNull(s.text("SELECT import_id FROM sync_restore_runs"))
            assertEquals(2L, s.baseline.process(id).total)
            assertTrue(
                s.journal.pendingEvents("space", 1).flatMap { it.effects }
                    .none { it.objectKey.originalUrl == "/bad" },
            )
        }
        Storage().use { s ->
            s.connect()
            val result = runCatching { s.restorer().restore(backup()) { throw CancellationException("cancel") } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals("CANCELLED", s.text("SELECT state FROM sync_restore_runs"))
            val id = requireNotNull(s.text("SELECT import_id FROM sync_restore_runs"))
            assertEquals(2L, s.baseline.process(id).total)
        }
    }

    @Test
    fun `settings restore cannot clone app state and does not open a sync import`() = runBlocking {
        Storage().use { s ->
            s.connect()
            val key = Preference.appStateKey("sync-device")
            s.preferences.getString(key).set("this-device")
            val preferences = listOf(
                BackupPreference(key, StringPreferenceValue("other-device")),
                BackupPreference("theme", StringPreferenceValue("dark")),
            )
            assertFalse(s.restorer().restore(Backup(emptyList(), backupPreferences = preferences)).hasErrors)
            assertEquals("this-device", s.preferences.getString(key).get())
            assertEquals("dark", s.preferences.getString("theme").get())
            assertNull(s.text("SELECT import_id FROM sync_restore_runs"))
        }
    }

    @Test
    fun `settings factory restores selected backup through the shared sync binding`() = runBlocking {
        Storage().use { s ->
            s.connect()
            val file = DesktopBackupCreator.writeBackupFile(backup(), directory)
            val factory = BackupRestoreScreenModelFactory(
                s.mangas, s.chapters, s.categories, s.history,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                s.preferences, mockk(relaxed = true), s.authors, s.sync,
            )
            val model = factory.create(this)
            model.select(file)
            withTimeout(10_000) { model.state.first { it is BackupRestoreUiState.Preview } }
            model.confirmRestore()
            val state = withTimeout(10_000) {
                model.state.first {
                    it is BackupRestoreUiState.Completed || it is BackupRestoreUiState.Failure
                }
            }
            assertTrue(state is BackupRestoreUiState.Completed, state.toString())
            assertEquals("COMPLETED", s.text("SELECT state FROM sync_restore_runs"))
            model.onDispose()
        }
    }

    private fun backup() = Backup(
        listOf(
            BackupManga(
                source = 42,
                url = "/manga",
                title = "Restored",
                favorite = true,
                chapters = listOf(BackupChapter("/chapter", "Chapter", read = true, lastPageRead = 7)),
                history = listOf(BackupHistory("/chapter", 500, 20)),
            ),
        ),
    )

    private class Storage : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: Database
        init {
            Database.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            db = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val handler = JvmDatabaseHandler(db, driver)
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val mangas = MangaRepositoryImpl(handler, CreatorRepositoryImpl(handler, bootstrap = bootstrap))
        val chapters = ChapterRepositoryImpl(handler)
        val categories = CategoryRepositoryImpl(handler)
        val history = HistoryRepositoryImpl(handler)
        val authors = SqlDelightAuthorArchiveBackupContributor(handler)
        private val preferenceNode = Preferences.userRoot().node("/mihon-test/sync-restore-${UUID.randomUUID()}")
        val preferences = DesktopPreferenceStore(preferenceNode)
        val sync = SyncBackupRestorer(handler, bootstrap)
        val journal = SyncLocalJournal(handler)
        val baseline = SyncBaselineStore(handler, bootstrap)
        suspend fun connect() = journal.connect(
            "space",
            1,
            SyncRepository("owner", "sync", "mihon-sync"),
            "device",
            1,
        )
        fun restorer() = DesktopBackupRestorer(
            mangas,
            chapters,
            categories,
            history,
            preferenceStore = preferences,
            authorArchiveBackupContributor = authors,
            backupRestoreSync = sync,
        )
        fun text(sql: String): String? = driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            0,
        ).value
        override fun close() {
            driver.close()
            preferenceNode.removeNode()
        }
    }
}

/** Used by the real DI setup test so a disconnected factory binding breaks the acceptance test. */
internal suspend fun verifyNativeBackupSync(
    factory: BackupRestoreScreenModelFactory,
    handler: DatabaseHandler,
    driver: app.cash.sqldelight.db.SqlDriver,
    directory: File,
    scope: CoroutineScope,
) {
    SyncLocalJournal(handler).connect("di-space", 1, SyncRepository("owner", "sync", "mihon-sync"), "device", 1)
    val backup = Backup(listOf(BackupManga(source = 42, url = "/di-restore", title = "Restore", favorite = true)))
    val model = factory.create(scope)
    try {
        model.select(DesktopBackupCreator.writeBackupFile(backup, directory))
        withTimeout(10_000) { model.state.first { it is BackupRestoreUiState.Preview } }
        model.confirmRestore()
        val result = withTimeout(10_000) {
            model.state.first {
                it is BackupRestoreUiState.Completed || it is BackupRestoreUiState.Failure
            }
        }
        assertTrue(result is BackupRestoreUiState.Completed, result.toString())
        val total = driver.executeQuery(
            null,
            "SELECT total FROM sync_imports WHERE space_id = 'di-space' AND origin = 'BACKUP_RESTORE'",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value
        assertEquals(1L, total)
    } finally {
        model.onDispose()
    }
}
