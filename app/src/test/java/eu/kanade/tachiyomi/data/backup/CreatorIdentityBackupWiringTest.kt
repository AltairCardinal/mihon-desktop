package eu.kanade.tachiyomi.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.NoopBackupRestoreSync
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.backup.BackupCodec
import tachiyomi.data.creator.verifyCreatorIdentityBackup
import tachiyomi.domain.manga.interactor.GetFavorites

class CreatorIdentityBackupWiringTest {
    @Test
    fun `actual Android creator codec and restorer transfer shared complete identity graph`() = runBlocking<Unit> {
        val jdbc = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        java.sql.DriverManager.registerDriver(jdbc)
        try {
            Storage().use { source ->
                Storage().use { target ->
                    verifyCreatorIdentityBackup(source.handler, target.handler) { exporter, importer ->
                        val favorites = mockk<GetFavorites>()
                        coEvery { favorites.await() } returns emptyList()
                        val creator = BackupCreator(
                            context = mockk(relaxed = true), isAutoBackup = false,
                            getFavorites = favorites, backupPreferences = mockk(relaxed = true),
                            mangaRepository = mockk(relaxed = true), categoriesBackupCreator = mockk(relaxed = true),
                            mangaBackupCreator = mockk(relaxed = true), preferenceBackupCreator = mockk(relaxed = true),
                            extensionRepoBackupCreator = mockk(
                                relaxed = true,
                            ),
                            sourcesBackupCreator = mockk(relaxed = true),
                            authorArchiveBackupContributor = exporter,
                        )
                        val backup = creator.createPayload(
                            BackupOptions(
                                libraryEntries = true,
                                categories = false,
                                readEntries = false,
                                appSettings = false,
                                extensionRepoSettings = false,
                                sourceSettings = false,
                            ),
                        )
                        val bytes = BackupCodec.encode(Backup.serializer(), backup)
                        val uri = mockk<Uri>()
                        val resolver = mockk<ContentResolver>()
                        val context = mockk<Context>(relaxed = true)
                        every { context.contentResolver } returns resolver
                        every { resolver.openInputStream(uri) } answers { bytes.inputStream() }
                        BackupRestorer(
                            context = context, notifier = mockk(relaxed = true), isSync = false,
                            categoriesRestorer = mockk(relaxed = true), preferenceRestorer = mockk(relaxed = true),
                            extensionRepoRestorer = mockk(relaxed = true), mangaRestorer = mockk(relaxed = true),
                            authorArchiveBackupContributor = importer, backupRestoreSync = NoopBackupRestoreSync,
                        ).restore(
                            uri,
                            RestoreOptions(
                                libraryEntries = true,
                                categories = false,
                                appSettings = false,
                                extensionRepoSettings = false,
                                sourceSettings = false,
                            ),
                        )
                        checkNotNull(BackupCodec.decode(Backup.serializer(), bytes).backupAuthorArchive)
                    }
                }
            }
        } finally {
            java.sql.DriverManager.deregisterDriver(jdbc)
        }
    }

    private class Storage : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val handler: AndroidDatabaseHandler
        init {
            Database.Schema.create(driver)
            handler = AndroidDatabaseHandler(
                Database(
                    driver,
                    historyAdapter = History.Adapter(DateColumnAdapter),
                    mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
                ),
                driver,
            )
        }
        override fun close() {
            driver.close()
        }
    }
}
