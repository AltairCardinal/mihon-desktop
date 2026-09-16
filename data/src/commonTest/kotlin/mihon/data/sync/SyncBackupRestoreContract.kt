package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncBackupRestorer
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncRestoreOutcome
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SyncBackupRestoreContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage
    private val repository = SyncRepository("owner", "sync-data", "mihon-sync")

    @Test
    fun `restored data is a frozen baseline without borrowing explicit user parents`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val manga = s.seed("/restored")
            s.mangas.update(MangaUpdate(manga.id, favorite = false, syncContext = SyncMutationContext.User))
            val original = s.journal.pendingEvents("space", 1).single()
            val id = requireNotNull(s.restore.begin())
            s.restore.restoreManga(id, 42, manga.url) {
                s.mangas.update(MangaUpdate(manga.id, favorite = true, title = "来自备份"))
            }
            s.mangas.update(MangaUpdate(manga.id, title = "之后刷新"))
            assertEquals(
                "来自备份",
                s.handler.await {
                    sync_importQueries.getImportEntries(id, 50).executeAsOne().title
                },
            )
            s.restore.finish(id, SyncRestoreOutcome.COMPLETED)
            assertEquals(1L, s.baseline.process(id).total)
            val events = s.journal.pendingEvents("space", 1)
            assertEquals(original, events.first())
            assertEquals(SyncOrigin.BACKUP_RESTORE, events.last().origin)
            assertEquals(id, events.last().importId)
            assertEquals(SyncEffectKind.ADD, events.last().effects.single().kind)
            assertTrue(events.last().effects.single().parents.isEmpty())
            assertEquals("device", events.last().actorId)
            assertEquals(1L, events.last().epoch)
        }
    }

    @Test
    fun `cancelled restore retains committed units and rolls back the failed unit`() = runBlocking {
        val file = File.createTempFile("sync-restore-", ".db")
        var id = ""
        try {
            open(file.absolutePath).use { s ->
                s.connect(repository)
                val first = s.seed("/first")
                val second = s.seed("/second")
                id = requireNotNull(s.restore.begin())
                s.restore.restoreManga(id, 42, first.url) {
                    s.mangas.update(MangaUpdate(first.id, favorite = true))
                }
                assertTrue(
                    runCatching {
                        s.restore.restoreManga(id, 42, second.url) {
                            s.mangas.update(MangaUpdate(second.id, favorite = true))
                            error("injected partial restore failure")
                        }
                    }.isFailure,
                )
                assertFalse(s.mangas.getMangaById(second.id).favorite)
                s.restore.finish(id, SyncRestoreOutcome.CANCELLED)
            }
            open(file.absolutePath, false).use { s ->
                assertEquals(1L, s.baseline.process(id).total)
                val event = s.journal.pendingEvents("space", 1).single()
                assertEquals("/first", event.effects.single().objectKey.originalUrl)
                assertEquals(SyncOrigin.BACKUP_RESTORE, event.origin)
                assertEquals(0L, s.baseline.process(id).remaining)
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `capture failure rolls back the business write and retries the same unit`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val manga = s.seed("/failure")
            val id = requireNotNull(s.restore.begin())
            s.driver.execute(
                null,
                "CREATE TRIGGER fail_restore BEFORE INSERT ON sync_import_entries " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END",
                0,
            )
            assertTrue(
                runCatching {
                    s.restore.restoreManga(id, 42, manga.url) {
                        s.mangas.update(MangaUpdate(manga.id, favorite = true))
                    }
                }.isFailure,
            )
            assertFalse(s.mangas.getMangaById(manga.id).favorite)
            s.driver.execute(null, "DROP TRIGGER fail_restore", 0)
            repeat(2) {
                s.restore.restoreManga(id, 42, manga.url) {
                    s.mangas.update(MangaUpdate(manga.id, favorite = true))
                }
            }
            assertEquals(1L, s.baseline.process(id).total)
            assertEquals(1, s.journal.pendingEvents("space", 1).size)
        }
    }

    @Test
    fun `unconfigured restore stays local and scope changes never retarget a running restore`() = runBlocking {
        open().use { s ->
            val manga = s.seed("/local")
            val local = s.restore.begin()
            assertEquals(null, local)
            s.restore.restoreManga(local, 42, manga.url) { s.mangas.update(MangaUpdate(manga.id, favorite = true)) }
            assertTrue(s.mangas.getMangaById(manga.id).favorite)
            s.connect(repository)
            val id = s.restore.begin()
            assertNotNull(id)
            s.journal.connect("other", 1, repository.copy(name = "other-data"), "other-device", 1)
            s.restore.restoreManga(id, 42, manga.url) { s.mangas.update(MangaUpdate(manga.id, title = "恢复标题")) }
            assertEquals(0L, s.handler.await { sync_importQueries.countImportEntries(id!!).executeAsOne() })
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            assertTrue(s.journal.pendingEvents("other", 1).isEmpty())
        }
    }

    @Test
    fun `restoration continues after earlier units have entered the upload queue`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val first = s.seed("/first")
            val second = s.seed("/second")
            val id = requireNotNull(s.restore.begin())
            s.restore.restoreManga(id, 42, first.url) {
                s.mangas.update(MangaUpdate(first.id, favorite = true))
            }
            assertEquals(1L, s.baseline.process(id).total)
            assertEquals(0L, s.baseline.process(id).remaining)
            s.restore.restoreManga(id, 42, second.url) {
                s.mangas.update(MangaUpdate(second.id, favorite = true))
            }
            assertEquals(2L, s.baseline.process(id).total)
            assertEquals(
                setOf("/first", "/second"),
                s.journal.pendingEvents("space", 1)
                    .flatMap { it.effects }.map { it.objectKey.originalUrl }.toSet(),
            )
        }
    }

    protected fun database(driver: SqlDriver, create: Boolean): Database {
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val mangas = MangaRepositoryImpl(handler, creators)
        val journal = SyncLocalJournal(handler)
        val baseline = SyncBaselineStore(handler, bootstrap)
        val restore = SyncBackupRestorer(handler, bootstrap)
        suspend fun connect(repository: SyncRepository) = journal.connect("space", 1, repository, "device", 1)
        suspend fun seed(url: String): Manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = 42, url = url, title = url)),
        ).single()
        override fun close() = driver.close()
    }
}
