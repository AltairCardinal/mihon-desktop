package mihon.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DatabaseMigration
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.io.File

class ExtensionRepoRepositoryPersistenceTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `equivalent signing key spellings still require repository replacement confirmation`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            open(File(directory, "canonical-identity.db")).use { fixture ->
                Database.Schema.create(fixture.driver)
                val existing = ExtensionRepo("https://old.example", "Old", null, "https://old.example", "AA:BB")
                fixture.repository.insertRepo(existing)
                val network = mockk<NetworkHelper> { every { client } returns OkHttpClient() }
                val service = ExtensionRepoService(network, Json { ignoreUnknownKeys = true })
                server.enqueue(
                    MockResponse(
                        body = """{"name":"New",
                    "badgeLabel":"New",
                    "signingKey":"aabb",
                    "contact":{"website":"https://store.example"},
                    "extensionList":{"extensions":[]}}""",
                    ),
                )
                val result = CreateExtensionRepo(
                    fixture.repository,
                    service,
                ).await(server.url("/index.json").toString())
                val conflict = assertInstanceOf(CreateExtensionRepo.Result.DuplicateFingerprint::class.java, result)
                assertEquals(existing, conflict.oldRepo)
                assertEquals(listOf(existing), fixture.repository.getAll())
                assertInstanceOf(
                    ExtensionRepoActionResult.Success::class.java,
                    service.replace(conflict.oldRepo, conflict.newRepo) {
                        ReplaceExtensionRepo(fixture.repository).await(it)
                    },
                )
                assertEquals(existing.signingKeyFingerprint, fixture.repository.getAll().single().signingKeyFingerprint)
                server.enqueue(
                    MockResponse(
                        body = """{"name":"Updated","badgeLabel":"New","signingKey":"aa:bb",
                            "contact":{"website":"https://store.example"},"extensionList":{"extensions":[]}}""",
                    ),
                )
                UpdateExtensionRepo(fixture.repository, service).await(fixture.repository.getAll().single())
                assertEquals("Updated", fixture.repository.getAll().single().name)
                assertEquals(existing.signingKeyFingerprint, fixture.repository.getAll().single().signingKeyFingerprint)
            }
        }
    }

    @Test
    fun `real HTTP discovery duplicate migration and reopen retain repository identity`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val file = File(directory, "discovery.db")
            val root = server.url("/").toString().removeSuffix("/")
            val index = server.url("/catalog/index.json").toString()
            val network = mockk<NetworkHelper> { every { client } returns OkHttpClient() }
            val service = ExtensionRepoService(network, Json { ignoreUnknownKeys = true })
            var expected: ExtensionRepo? = null
            open(file).use { fixture ->
                Database.Schema.create(fixture.driver)
                val create = CreateExtensionRepo(fixture.repository, service)
                server.enqueue(
                    MockResponse(
                        body = """{"meta":{"name":"Store",
                    "website":"https://store.example",
                    "signingKeyFingerprint":"trusted"},
                    "index_v2":"$index"}""",
                    ),
                )
                assertEquals(CreateExtensionRepo.Result.Success, create.await("$root/index.min.json"))
                val old = requireNotNull(fixture.repository.getRepo(root))
                assertNull(old.shortName)
                assertEquals(index, old.indexUrl)

                val store = """{"name":"Store",
                    "badgeLabel":"New",
                    "signingKey":"trusted",
                    "contact":{"website":"https://store.example"},
                    "extensionList":{"extensions":[]}}"""
                server.enqueue(MockResponse(body = store))
                val duplicate =
                    assertInstanceOf(CreateExtensionRepo.Result.DuplicateFingerprint::class.java, create.await(index))
                assertInstanceOf(
                    ExtensionRepoActionResult.Success::class.java,
                    service.replace(duplicate.oldRepo, duplicate.newRepo) {
                        ReplaceExtensionRepo(fixture.repository).await(it)
                    },
                )
                assertNull(fixture.repository.getRepo(root))
                expected = duplicate.newRepo
                assertEquals(expected, fixture.repository.getRepo(index))

                server.enqueue(MockResponse(body = store.replace("trusted", "untrusted")))
                UpdateExtensionRepo(fixture.repository, service).await(requireNotNull(expected))
                assertEquals(expected, fixture.repository.getRepo(index))
                server.enqueue(MockResponse(code = 500, body = "unavailable"))
                UpdateExtensionRepo(fixture.repository, service).await(requireNotNull(expected))
                assertEquals(expected, fixture.repository.getRepo(index))
            }
            open(file).use { fixture -> assertEquals(expected, fixture.repository.getRepo(index)) }
        }
    }

    @Test
    fun `create rejects credential bearing input before HTTP and preserves the database`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            open(File(directory, "invalid-input.db")).use { fixture ->
                Database.Schema.create(fixture.driver)
                val network = mockk<NetworkHelper> { every { client } returns OkHttpClient() }
                val create = CreateExtensionRepo(fixture.repository, ExtensionRepoService(network, Json))
                val url = server.url("/repo.json").newBuilder().username("user").password("pass").build()
                assertEquals(CreateExtensionRepo.Result.InvalidUrl, create.await(url.toString()))
                assertEquals(0, server.requestCount)
                assertEquals(emptyList<ExtensionRepo>(), fixture.repository.getAll())
            }
        }
    }

    @Test
    fun `repository identity and catalog metadata survive a real database reopen`() = runTest {
        val databaseFile = File(directory, "extension-repos.db")
        val expected = ExtensionRepo(
            baseUrl = "https://repo.example",
            name = "Example Store",
            shortName = "Example",
            website = "https://repo.example/about",
            signingKeyFingerprint = "aa:bb",
            indexUrl = "https://repo.example/index.pb",
            extensionListUrl = "https://repo.example/extensions.pb",
            contactDiscord = "https://discord.example/store",
        )

        open(databaseFile).use { fixture ->
            Database.Schema.create(fixture.driver)
            fixture.repository.upsertRepo(expected)
        }

        open(databaseFile).use { fixture ->
            assertEquals(expected, fixture.repository.getRepo(expected.baseUrl))
        }
    }

    @Test
    fun `version eighteen repository table migrates before metadata can be reopened`() = runTest {
        val databaseFile = File(directory, "extension-repos-v18.db")
        JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}").use { driver ->
            createVersion18Fixture(driver)
            driver.execute(
                null,
                """INSERT INTO extension_repos(base_url, name, short_name, website, signing_key_fingerprint)
                    VALUES ('https://legacy.example', 'Legacy', 'L', 'https://legacy.example/about', 'legacy-key')""",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 18", 0)
        }

        val expected = ExtensionRepo(
            baseUrl = "https://migrated.example",
            name = "Migrated",
            shortName = "M",
            website = "https://migrated.example/about",
            signingKeyFingerprint = "migrated-fingerprint",
            indexUrl = "https://migrated.example/index.pb",
            extensionListUrl = "https://migrated.example/extensions.pb",
            contactDiscord = "discord:migrated",
        )
        open(databaseFile).use { fixture ->
            DatabaseMigration.migrateAtomically(fixture.driver, 18, Database.Schema.version)
            fixture.repository.upsertRepo(expected)
        }

        open(databaseFile).use { fixture ->
            assertEquals(expected, fixture.repository.getRepo(expected.baseUrl))
            assertEquals(
                ExtensionRepo(
                    baseUrl = "https://legacy.example",
                    name = "Legacy",
                    shortName = "L",
                    website = "https://legacy.example/about",
                    signingKeyFingerprint = "legacy-key",
                ),
                fixture.repository.getRepo("https://legacy.example"),
            )
        }
    }

    private fun createVersion18Fixture(driver: JdbcSqliteDriver) {
        // v18 already contained the full author archive. Keep that history while removing only
        // additions from later migrations, so this test still exercises the complete upgrade.
        Database.Schema.create(driver)
        // The simulated historical schema must not retain migration 40 objects from the latest create.
        listOf("insert_guard", "delete_guard", "insert", "delete").forEach { suffix ->
            driver.execute(null, "DROP TRIGGER chapter_id_floor_$suffix", 0)
        }
        listOf("chapter_url_aliases", "chapter_directory_phases", "chapter_id_floor").forEach { table ->
            driver.execute(null, "DROP TABLE $table", 0)
        }

        driver.execute(null, "DROP TRIGGER IF EXISTS author_archive_source_work_first_seen_defaults", 0)
        listOf(
            "author_archive_source_date_quality_samples",
            "author_archive_source_date_quality_current",
            "author_archive_source_date_quality",
            "author_archive_representative_work_cache",
        ).forEach { table -> driver.execute(null, "DROP TABLE IF EXISTS $table", 0) }
        val laterObjects = driver.executeQuery(
            null,
            """SELECT type, name FROM sqlite_master
                WHERE (type = 'table' AND (
                    name GLOB 'sync_*' OR name GLOB 'author_archive_identity_*' OR
                    name = 'author_archive_representative_work_cache' OR
                    name GLOB 'author_archive_source_date_quality*' OR
                    name = 'author_archive_presentation_exclusions'
                ))
                   OR (type = 'trigger' AND (
                    name GLOB 'author_archive_*_revision' OR
                    name = 'author_archive_source_work_first_seen_defaults'
                ))
                ORDER BY CASE type WHEN 'trigger' THEN 0 ELSE 1 END""",
            { cursor ->
                QueryResult.Value(
                    buildList {
                        while (cursor.next().value) {
                            add(requireNotNull(cursor.getString(0)) to requireNotNull(cursor.getString(1)))
                        }
                    },
                )
            },
            0,
        ).value
        laterObjects.forEach { (type, name) -> driver.execute(null, "DROP $type $name", 0) }
        driver.execute(null, "ALTER TABLE author_archive_creators DROP COLUMN identity_revision", 0)
        driver.execute(null, "ALTER TABLE mangas DROP COLUMN memo", 0)
        driver.execute(null, "ALTER TABLE chapters DROP COLUMN memo", 0)
        listOf(
            "first_seen_date",
            "first_seen_zone",
            "chapter_count_state",
            "catalog_chapter_count",
            "latest_chapter_at",
            "published_date_snapshot_at",
            "published_date_snapshot_basis",
            "published_date_snapshot_reason",
        ).forEach { column ->
            driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $column", 0)
        }
        listOf("index_url", "extension_list_url", "contact_discord").forEach { column ->
            driver.execute(null, "ALTER TABLE extension_repos DROP COLUMN $column", 0)
        }
    }

    private fun open(file: File): Fixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        val handler = JvmDatabaseHandler(database, driver)
        return Fixture(driver, handler, ExtensionRepoRepositoryImpl(handler))
    }

    private class Fixture(
        val driver: JdbcSqliteDriver,
        private val handler: JvmDatabaseHandler,
        val repository: ExtensionRepoRepositoryImpl,
    ) : AutoCloseable {
        override fun close() {
            handler.close()
        }
    }
}
