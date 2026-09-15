package eu.kanade.tachiyomi.data.backup

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionRepoBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRepoRestorer
import kotlinx.coroutines.runBlocking
import mihon.data.repository.ExtensionRepoRepositoryImpl
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.backup.BackupCodec
import java.util.Base64
import java.util.UUID

/** Uses isolated Android SQLite files and the production repository, backup creator and restorer on ART. */
class ExtensionRepoPersistenceInstrumentationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun productionBackupRestoresMetadataAndTrustAfterAndroidDatabaseReopen() = runBlocking {
        withDatabaseNames { sourceName, targetName ->
            val expected = expectedRepo()
            open(sourceName).use { it.repository.insertRepo(expected) }
            val backup = open(sourceName).use { source ->
                val created = ExtensionRepoBackupCreator(GetExtensionRepo(source.repository))()
                BackupCodec.decode(
                    Backup.serializer(),
                    BackupCodec.encode(
                        Backup.serializer(),
                        Backup(backupManga = emptyList(), backupExtensionRepo = created),
                    ),
                )
            }
            val existing = expected.copy(baseUrl = "https://existing.example", signingKeyFingerprint = "existing-trust")
            open(targetName).use { target ->
                target.repository.insertRepo(existing)
                val restorer = ExtensionRepoRestorer(target.handler, GetExtensionRepo(target.repository))
                backup.backupExtensionRepo.forEach { restorer(it) }
            }
            open(targetName).use { target ->
                assertEquals(setOf(expected, existing), target.repository.getAll().toSet())
            }
        }
    }

    @Test
    fun oldBackupWithoutOptionalFieldsRestoresAndConflictsPreserveExistingTrust() = runBlocking {
        withDatabaseNames { _, targetName ->
            // Fixed pre-v2 protobuf record containing only fields 1, 2, 4 and 5.
            val legacy = BackupCodec.decode(
                BackupExtensionRepos.serializer(),
                Base64.getDecoder().decode(
                    "ChZodHRwczovL2xlZ2FjeS5leGFtcGxlEgZMZWdhY3kiHGh0dHBzOi8v" +
                        "bGVnYWN5LmV4YW1wbGUvYWJvdXQqCmxlZ2FjeS1rZXk=",
                ),
            )
            val expected = ExtensionRepo(
                baseUrl = "https://legacy.example",
                name = "Legacy",
                shortName = null,
                website = "https://legacy.example/about",
                signingKeyFingerprint = "legacy-key",
            )
            open(targetName).use { target ->
                val restore = ExtensionRepoRestorer(target.handler, GetExtensionRepo(target.repository))
                restore(legacy)
                for (conflict in listOf(
                    legacy.copy(signingKeyFingerprint = "untrusted-key"),
                    legacy.copy(baseUrl = "https://different.example"),
                )) {
                    val failure = runCatching { restore(conflict) }.exceptionOrNull()
                    assertNotNull("A conflicting restore must fail", failure)
                    assertEquals(listOf(expected), target.repository.getAll())
                }
            }
            open(targetName).use { assertEquals(listOf(expected), it.repository.getAll()) }
        }
    }

    @Test
    fun versionEighteenMigrationRetainsLegacyIdentityAndAllowsMetadataAfterReopen() = runBlocking {
        withDatabaseNames { sourceName, _ ->
            context.openOrCreateDatabase(sourceName, Context.MODE_PRIVATE, null).use { legacy ->
                legacy.execSQL(
                    """CREATE TABLE extension_repos (
                        base_url TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        short_name TEXT,
                        website TEXT NOT NULL,
                        signing_key_fingerprint TEXT UNIQUE NOT NULL
                    )""",
                )
                legacy.execSQL(
                    """INSERT INTO extension_repos VALUES (
                        'https://legacy.example', 'Legacy', NULL, 'https://legacy.example/about', 'legacy-key'
                    )""",
                )
                legacy.version = 18
            }
            val oldRepo = ExtensionRepo(
                baseUrl = "https://legacy.example",
                name = "Legacy",
                shortName = null,
                website = "https://legacy.example/about",
                signingKeyFingerprint = "legacy-key",
            )
            open(sourceName).use { migrated ->
                assertEquals(oldRepo, migrated.repository.getRepo(oldRepo.baseUrl))
                migrated.repository.insertRepo(expectedRepo())
            }
            open(sourceName).use { reopened ->
                assertEquals(setOf(oldRepo, expectedRepo()), reopened.repository.getAll().toSet())
            }
        }
    }

    private suspend fun withDatabaseNames(block: suspend (String, String) -> Unit) {
        val sourceName = "aex02-source-${UUID.randomUUID()}.db"
        val targetName = "aex02-target-${UUID.randomUUID()}.db"
        try {
            block(sourceName, targetName)
        } finally {
            context.deleteDatabase(sourceName)
            context.deleteDatabase(targetName)
        }
    }

    private fun open(name: String): Storage {
        val driver = AndroidSqliteDriver(Database.Schema, context, name)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        return Storage(driver, AndroidDatabaseHandler(database, driver))
    }

    private class Storage(
        private val driver: AndroidSqliteDriver,
        val handler: AndroidDatabaseHandler,
    ) : AutoCloseable {
        val repository = ExtensionRepoRepositoryImpl(handler)
        override fun close() = driver.close()
    }

    private fun expectedRepo() = ExtensionRepo(
        baseUrl = "https://repo.example",
        name = "Example",
        shortName = "EX",
        website = "https://repo.example/about",
        signingKeyFingerprint = "trusted-fingerprint",
        indexUrl = "https://repo.example/index.pb",
        extensionListUrl = "https://repo.example/extensions.pb",
        contactDiscord = "discord:example",
    )
}
