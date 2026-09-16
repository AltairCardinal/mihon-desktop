package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.DomainModule
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorWatch
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.BackupRestoreSync
import mihon.data.sync.journal.SyncBackupRestorer
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncRestoreOutcome
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.backup.BackupCodec
import tachiyomi.data.backup.SqlDelightAuthorArchiveBackupContributor
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BackupRestoreSyncWiringTest {
    private val options = RestoreOptions(true, false, false, false, false)

    @Test
    fun `native Android restore freezes actual manga and author writes as backup baselines`() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val backup = Backup(
                backupManga = listOf(BackupManga(42, "/manga", "Restored manga")),
                backupAuthorArchive = BackupAuthorArchiveSection(
                    creators = listOf(BackupCreatorIdentity("author-key", "作者", "作者")),
                    watches = listOf(BackupAuthorWatch("author-key", true, 60000)),
                ),
            )
            f.restore(backup).restore(f.uri, options)
            val id = f.sync.id
            assertNotNull(id, "native restore must start a durable backup import")
            assertEquals("COMPLETED", f.handler.await { sync_restoreQueries.getRun(id!!).executeAsOne().state })
            assertTrue(f.journal.pendingEvents("space", 1).isEmpty())
            SyncBaselineStore(f.handler, f.bootstrap).process(id!!)
            val events = f.journal.pendingEvents("space", 1)
            assertEquals(setOf(SyncCategory.FAVORITE, SyncCategory.FOLLOW), events.map { it.category }.toSet())
            assertTrue(events.all { it.origin == SyncOrigin.BACKUP_RESTORE })
            assertEquals("Restored manga", f.mangas.getMangaByUrlAndSourceId("/manga", 42)?.title)
        }
    }

    @Test
    fun `manga failure records partial outcome while preserving committed units`() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.driver.execute(
                null,
                "CREATE TRIGGER fail_manga BEFORE INSERT ON mangas " +
                    "WHEN NEW.url = '/bad' BEGIN SELECT RAISE(ABORT, 'injected'); END",
                0,
            )
            f.restore(Backup(backupManga = listOf(BackupManga(42, "/good", "Good"), BackupManga(42, "/bad", "Bad"))))
                .restore(f.uri, options)
            assertEquals(SyncRestoreOutcome.PARTIAL, f.sync.outcome)
            val id = requireNotNull(f.sync.id)
            assertEquals("PARTIAL", f.handler.await { sync_restoreQueries.getRun(id).executeAsOne().state })
            SyncBaselineStore(f.handler, f.bootstrap).process(id)
            assertEquals(
                "/good",
                f.journal.pendingEvents("space", 1).single().effects.single().objectKey.originalUrl,
            )
        }
    }

    @Test
    fun `cancellation from a manga restore propagates and durably finishes the run`() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val manga = mockk<MangaRestorer>()
            coEvery { manga.sortByNew(any()) } answers { firstArg() }
            coEvery { manga.restore(any(), any()) } throws CancellationException("cancel restore")
            val result = runCatching {
                f.restore(Backup(backupManga = listOf(BackupManga(42, "/cancel", "Cancel"))), manga)
                    .restore(f.uri, options)
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(SyncRestoreOutcome.CANCELLED, f.sync.outcome)
            assertEquals(
                "CANCELLED",
                f.handler.await { sync_restoreQueries.getRun(f.sync.id!!).executeAsOne().state },
            )
        }
    }

    @Test
    fun `fatal restore failure is persisted and settings only never starts an import`() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val manga = mockk<MangaRestorer>()
            coEvery { manga.sortByNew(any()) } throws IllegalStateException("sorting failed")
            assertTrue(
                runCatching {
                    f.restore(Backup(backupManga = listOf(BackupManga(42, "/failed", "Fail"))), manga)
                        .restore(f.uri, options)
                }.isFailure,
            )
            assertEquals(SyncRestoreOutcome.FAILED, f.sync.outcome)
        }
        Fixture().use { f ->
            f.connect()
            f.restore(Backup(backupManga = listOf(BackupManga(42, "/ignored", "Not selected"))))
                .restore(f.uri, options.copy(libraryEntries = false))
            assertEquals(0, f.sync.begins)
            assertEquals(null, f.sync.outcome)
        }
    }

    @Test
    fun `default Android restore uses the DomainModule service and records a baseline`() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.bootstrap.awaitReady()
            val previous = Injekt
            Injekt = InjektScope(DefaultRegistrar())
            try {
                Injekt.addSingleton<DatabaseHandler>(f.handler)
                Injekt.importModule(DomainModule())
                f.prepareBackup(Backup(backupManga = listOf(BackupManga(42, "/default", "Default binding"))))
                BackupRestorer(
                    context = f.context,
                    notifier = mockk(relaxed = true),
                    isSync = false,
                    categoriesRestorer = mockk(relaxed = true),
                    preferenceRestorer = mockk(relaxed = true),
                    extensionRepoRestorer = mockk(relaxed = true),
                    mangaRestorer = f.nativeManga(),
                    authorArchiveBackupContributor = SqlDelightAuthorArchiveBackupContributor(f.handler),
                ).restore(f.uri, options)
                val importId = f.driver.executeQuery(
                    null,
                    "SELECT import_id FROM sync_restore_runs",
                    { cursor ->
                        assertTrue(cursor.next().value, "default constructor must start the production sync import")
                        QueryResult.Value(requireNotNull(cursor.getString(0)))
                    },
                    0,
                ).value
                assertEquals(
                    "COMPLETED",
                    f.handler.await { sync_restoreQueries.getRun(importId).executeAsOne().state },
                )
                SyncBaselineStore(f.handler, f.bootstrap).process(importId)
                assertEquals(SyncOrigin.BACKUP_RESTORE, f.journal.pendingEvents("space", 1).single().origin)
            } finally {
                Injekt = previous
            }
        }
    }

    private class TrackingSync(private val actual: BackupRestoreSync) : BackupRestoreSync {
        var id: String? = null
        var begins = 0
        var outcome: SyncRestoreOutcome? = null
        override suspend fun begin(): String? {
            begins++
            return actual.begin().also { id = it }
        }
        override suspend fun restoreManga(id: String?, sourceId: Long, url: String, restore: suspend () -> Unit) =
            actual.restoreManga(id, sourceId, url, restore)
        override suspend fun restoreAuthors(id: String?, portableKeys: List<String>, restore: suspend () -> Unit) =
            actual.restoreAuthors(id, portableKeys, restore)
        override suspend fun finish(id: String?, outcome: SyncRestoreOutcome) {
            actual.finish(id, outcome)
            this.outcome = outcome
        }
    }

    private class Fixture : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database: Database
        val handler: DatabaseHandler
        init {
            Database.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            handler = AndroidDatabaseHandler(database, driver)
        }
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val mangas = MangaRepositoryImpl(handler, creators)
        val chapters = ChapterRepositoryImpl(handler)
        val journal = SyncLocalJournal(handler)
        val sync = TrackingSync(SyncBackupRestorer(handler, bootstrap))
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>(relaxed = true)
        suspend fun connect() = journal.connect(
            "space",
            1,
            SyncRepository("owner", "sync", "mihon-sync"),
            "device",
            1,
        )
        fun prepareBackup(backup: Backup) {
            val bytes = BackupCodec.encode(Backup.serializer(), backup)
            every { context.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } answers { bytes.inputStream() }
        }
        fun nativeManga(): MangaRestorer = MangaRestorer(
            handler = handler,
            getCategories = GetCategories(CategoryRepositoryImpl(handler)),
            getMangaByUrlAndSourceId = GetMangaByUrlAndSourceId(mangas),
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            updateManga = mockk(relaxed = true),
            getTracks = mockk { coEvery { await(any()) } returns emptyList() },
            insertTrack = mockk(relaxed = true),
            creatorIndexWriter = creators,
            fetchInterval = FetchInterval(GetChaptersByMangaId(chapters)),
        )
        fun restore(backup: Backup, manga: MangaRestorer? = null): BackupRestorer {
            prepareBackup(backup)
            val nativeManga = manga ?: nativeManga()
            return BackupRestorer(
                context,
                mockk<BackupNotifier>(relaxed = true),
                false,
                categoriesRestorer = mockk(relaxed = true),
                preferenceRestorer = mockk(relaxed = true),
                extensionRepoRestorer = mockk(relaxed = true),
                mangaRestorer = nativeManga,
                authorArchiveBackupContributor = SqlDelightAuthorArchiveBackupContributor(handler),
                backupRestoreSync = sync,
            )
        }
        override fun close() = driver.close()
    }
}
