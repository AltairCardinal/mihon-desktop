package tachiyomi.data.creator

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseMigration
import tachiyomi.domain.creator.model.CreatorArchivePhysicalSchema
import tachiyomi.domain.creator.model.CreatorArchiveV2Contract
import java.nio.file.Files

class CreatorArchiveMigration15Test {

    @Test
    fun `v27 migration creates local representative cache and preserves archive facts after reopen`() {
        val path = Files.createTempFile("creator-cache-migration-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        var driver = JdbcSqliteDriver(url)
        try {
            Database.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            driver.execute(null, "DROP TABLE author_archive_representative_work_cache", 0)
            driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
            driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
            driver.execute(null, "PRAGMA user_version = 27", 0)
            seedV27ArchiveFacts(driver)

            queryLong(driver, "PRAGMA user_version") shouldBe 27L
            val creatorCount = queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators")
            val aliasCount = queryLong(driver, "SELECT COUNT(*) FROM author_archive_aliases")
            val sourceWorkCount = queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_works")
            val relationCount = queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_work_creators")
            creatorCount shouldBe 1L
            aliasCount shouldBe 1L
            sourceWorkCount shouldBe 1L
            relationCount shouldBe 1L

            DatabaseMigration.migrateAtomically(driver, 27, 28)

            queryLong(driver, "PRAGMA user_version") shouldBe 28L
            Database.Schema.version shouldBe CreatorArchiveV2Contract.LATEST_SCHEMA_VERSION
            queryLong(
                driver,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'author_archive_representative_work_cache'",
            ) shouldBe 1L
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe creatorCount
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_aliases") shouldBe aliasCount
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_works") shouldBe sourceWorkCount
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe relationCount
            queryString(
                driver,
                "SELECT title FROM author_archive_source_works WHERE stable_source_url = '/kept-work'",
            ) shouldBe "Kept work"
            foreignKeyViolations(driver) shouldBe emptyList()

            val activeCreatorId = queryLong(
                driver,
                "SELECT _id FROM author_archive_creators WHERE status = 'ACTIVE' ORDER BY _id LIMIT 1",
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_representative_work_cache(creator_id, strategy_version, payload) " +
                    "VALUES (?, 1, '{\"selected\":[{\"workKey\":\"migration\",\"sourceId\":1," +
                    "\"stableSourceUrl\":\"/migration\"}] }')",
                1,
            ) {
                bindLong(0, activeCreatorId)
            }

            driver.close()
            driver = JdbcSqliteDriver(url)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            queryLong(driver, "PRAGMA user_version") shouldBe 28L
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_representative_work_cache") shouldBe 1L
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe creatorCount
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_aliases") shouldBe aliasCount
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_works") shouldBe sourceWorkCount
            queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe relationCount
            queryString(
                driver,
                "SELECT title FROM author_archive_source_works WHERE stable_source_url = '/kept-work'",
            ) shouldBe "Kept work"
        } finally {
            runCatching { driver.close() }
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `fresh schema is latest and contains the complete frozen archive`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)

        // The archive contract freezes its own migration milestone, not the application's latest schema.
        Database.Schema.version shouldBeGreaterThanOrEqual CreatorArchiveV2Contract.LATEST_SCHEMA_VERSION
        archiveTables(driver).shouldContainExactlyInAnyOrder(
            CreatorArchivePhysicalSchema.tables.map { it.name },
        )
        foreignKeyViolations(driver) shouldBe emptyList()
        driver.close()
    }

    @Test
    fun `fresh and upgraded schemas include rollback bridge fingerprint state`() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(fresh)
        val migrated = legacyDriver()
        DatabaseMigration.migrateAtomically(migrated, 15, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)

        archiveTables(fresh).shouldContainExactlyInAnyOrder(CreatorArchivePhysicalSchema.tables.map { it.name })
        archiveTables(migrated).size shouldBe 20
        queryLong(fresh, "SELECT COUNT(*) FROM author_archive_legacy_import_state") shouldBe 0L
        queryLong(migrated, "SELECT COUNT(*) FROM author_archive_legacy_import_state") shouldBe 0L
        fresh.close()
        migrated.close()
    }

    @Test
    fun `v15 fixture migrates every legacy state without duplicate relations`() {
        val driver = legacyDriver()
        seedLegacyArchive(driver)

        DatabaseMigration.migrateAtomically(driver, 15, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)

        queryLong(driver, "PRAGMA user_version") shouldBe 16L
        archiveTables(driver).shouldContainExactlyInAnyOrder(
            CreatorArchivePhysicalSchema.version16Tables.map { it.name },
        )
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe 2L
        queryLong(driver, "SELECT COUNT(DISTINCT portable_key) FROM author_archive_creators") shouldBe 2L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 9 AND creator_id = 1",
        ) shouldBe 1L
        queryString(
            driver,
            "SELECT role FROM author_archive_manga_links WHERE manga_id = 9 AND creator_id = 1",
        ) shouldBe "AUTHOR"
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_source_work_creators WHERE source_work_id = " +
                "(SELECT _id FROM author_archive_source_works WHERE legacy_candidate_id = 21) AND creator_id = 1",
        ) shouldBe 1L
        queryString(
            driver,
            "SELECT role FROM author_archive_source_work_creators WHERE source_work_id = " +
                "(SELECT _id FROM author_archive_source_works WHERE legacy_candidate_id = 21) AND creator_id = 1",
        ) shouldBe "AUTHOR"
        queryString(
            driver,
            "SELECT review_disposition FROM author_archive_discoveries WHERE source_work_id = " +
                "(SELECT _id FROM author_archive_source_works WHERE legacy_candidate_id = 21)",
        ) shouldBe "IGNORED"
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
        queryString(
            driver,
            "SELECT language_tag FROM author_archive_language_assertions " +
                "WHERE idempotency_key = 'legacy-language:21:reading'",
        ) shouldBe "und"
        queryString(
            driver,
            "SELECT state FROM author_archive_work_decisions WHERE idempotency_key = 'legacy-work-decision:9:31'",
        ) shouldBe "REJECTED"
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_canonical_versions WHERE source_work_id = " +
                "(SELECT _id FROM author_archive_source_works WHERE manga_id = 10)",
        ) shouldBe 1L
        queryString(
            driver,
            "SELECT language_tag FROM author_archive_language_assertions WHERE idempotency_key = " +
                "'legacy-canonical-language:31:original'",
        ) shouldBe "ja"
        queryString(
            driver,
            "SELECT subject_key FROM author_archive_language_assertions WHERE idempotency_key = " +
                "'legacy-canonical-language:31:original'",
        )!!.startsWith("canonical:") shouldBe true
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_watch_languages WHERE language_tag = 'pt-br'",
        ) shouldBe 1L
        queryString(
            driver,
            "SELECT actor || ':' || explicit FROM author_archive_work_decisions WHERE idempotency_key = " +
                "'legacy-work-decision:11:31'",
        ) shouldBe "ALGORITHM:0"
        foreignKeyViolations(driver) shouldBe emptyList()
        driver.close()
    }

    @Test
    fun `fresh and fully migrated schemas expose equivalent archive tables indexes and foreign keys`() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(fresh)
        fresh.execute(null, "PRAGMA foreign_keys = ON", 0)

        val migrated = legacyDriver()
        migrated.execute(
            null,
            "CREATE TABLE extension_repos(" +
                "base_url TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, short_name TEXT, " +
                "website TEXT NOT NULL, signing_key_fingerprint TEXT UNIQUE NOT NULL)",
            0,
        )
        DatabaseMigration.migrateAtomically(migrated, 15, CreatorArchiveV2Contract.LATEST_SCHEMA_VERSION)

        archiveTables(migrated) shouldBe archiveTables(fresh)
        archiveIndexes(migrated) shouldBe archiveIndexes(fresh)
        archiveForeignKeys(migrated) shouldBe archiveForeignKeys(fresh)
        fresh.close()
        migrated.close()
    }

    @Test
    fun `migration failure rolls back every v2 table and leaves version 15 readable`() {
        val driver = legacyDriver()
        driver.execute(
            null,
            "INSERT INTO manga_creators VALUES (404, 999, 'author', 'orphan', 1.0, 'broken fixture')",
            0,
        )

        shouldThrow<Exception> {
            DatabaseMigration.migrateAtomically(driver, 15, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)
        }

        archiveTables(driver) shouldBe emptyList()
        queryLong(driver, "PRAGMA user_version") shouldBe 15L
        queryLong(driver, "SELECT COUNT(*) FROM manga_creators WHERE creator_id = 999") shouldBe 1L
        driver.close()
    }

    @Test
    fun `creator names are not unique identities and relation role is not part of its key`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        seedManga(driver, id = 1, source = 1, url = "/same", title = "Same")

        driver.execute(
            null,
            "INSERT INTO author_archive_creators(portable_key, display_name, normalized_name, sort_name, status, " +
                "created_at, last_modified_at) VALUES ('first', 'Same', 'same', 'Same', 'ACTIVE', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(portable_key, display_name, normalized_name, sort_name, status, " +
                "created_at, last_modified_at) VALUES ('second', 'Same', 'same', 'Same', 'ACTIVE', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_manga_links(manga_id, creator_id, role, creator_order, origin, source_text, " +
                "confidence, evidence, created_at, last_modified_at) VALUES (1, 1, 'UNKNOWN', 0, 'MIGRATION', " +
                "'Same', 0.5, 'legacy', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "UPDATE author_archive_manga_links SET role = 'AUTHOR', confidence = 1.0 " +
                "WHERE manga_id = 1 AND creator_id = 1",
            0,
        )

        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_creators WHERE normalized_name = 'same'",
        ) shouldBe 2L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 1 AND creator_id = 1",
        ) shouldBe 1L
        queryString(
            driver,
            "SELECT role FROM author_archive_manga_links WHERE manga_id = 1 AND creator_id = 1",
        ) shouldBe "AUTHOR"
        driver.close()
    }

    @Test
    fun `migration normalizes source work urls and preserves blank manga decisions with fallback keys`() {
        val driver = legacyDriver()
        driver.execute(
            null,
            "INSERT INTO discovery_candidates VALUES " +
                "(22, 7, '  /spaced/work  ', 'Spaced Work', 'spaced work', NULL, NULL, " +
                "'en', 1.0, 'SOURCE_LANGUAGE', NULL, 1, 2, 2, 'NEW')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO canonical_works VALUES (31, 'Blank URL Work', 'blank url work', NULL, 'ja', 1, 2)",
            0,
        )
        seedManga(driver, id = 12, source = 7, url = "   ", title = "Blank URL Work")
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES " +
                "(12, 31, 1.0, 'blank url confirmation', 'CONFIRMED', 1, 1, 2)",
            0,
        )

        DatabaseMigration.migrateAtomically(driver, 15, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)

        queryString(
            driver,
            "SELECT stable_source_url FROM author_archive_source_works WHERE legacy_candidate_id = 22",
        ) shouldBe "/spaced/work"
        queryString(
            driver,
            "SELECT subject_key FROM author_archive_language_assertions " +
                "WHERE idempotency_key = 'legacy-language:22:reading'",
        ) shouldBe "source:7:/spaced/work"
        queryString(
            driver,
            "SELECT stable_source_url FROM author_archive_source_works WHERE manga_id = 12",
        ) shouldBe "legacy-manga:12"
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_work_decisions " +
                "WHERE idempotency_key = 'legacy-work-decision:12:31'",
        ) shouldBe 1L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_canonical_versions WHERE source_work_id = " +
                "(SELECT _id FROM author_archive_source_works WHERE manga_id = 12)",
        ) shouldBe 1L
        driver.close()
    }

    @Test
    fun `migration materializes one canonical binding from the complete collapsed decision history`() {
        val driver = legacyDriver()
        driver.execute(null, "INSERT INTO canonical_works VALUES (41, 'First Row', 'first row', NULL, NULL, 1, 20)", 0)
        driver.execute(
            null,
            "INSERT INTO canonical_works VALUES (42, 'Older Decision', 'older decision', NULL, NULL, 1, 20)",
            0,
        )
        seedManga(driver, id = 41, source = 7, url = "/collapsed/work", title = "First Row")
        seedManga(driver, id = 42, source = 7, url = "  /collapsed/work  ", title = "Older Decision")
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES " +
                "(41, 41, 1.0, 'inserted first but created later', 'CONFIRMED', 1, 20, 30)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES " +
                "(42, 42, 1.0, 'inserted second but created earlier', 'CONFIRMED', 1, 10, 40)",
            0,
        )

        DatabaseMigration.migrateAtomically(driver, 15, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)

        queryLong(
            driver,
            "SELECT COUNT(*) FROM author_archive_source_works WHERE source_id = 7 " +
                "AND stable_source_url = '/collapsed/work'",
        ) shouldBe 1L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_work_decisions") shouldBe 2L
        queryLong(
            driver,
            "SELECT CW.legacy_work_id FROM author_archive_canonical_versions V " +
                "JOIN author_archive_canonical_works CW ON CW._id = V.work_id",
        ) shouldBe 42L
        driver.close()
    }

    @Test
    fun `migration language projection matches runtime tag validity for every output column`() {
        val driver = legacyDriver()
        val vectors = listOf(
            LanguageVector("en", "en", valid = true),
            LanguageVector("zh-Hans", "zh-hans", valid = true),
            LanguageVector("abcd", "und", valid = false),
            LanguageVector("en-a", "und", valid = false),
            LanguageVector("en-abcdefghi", "und", valid = false),
            LanguageVector("en_US", "und", valid = false),
            LanguageVector("BL", "und", valid = false),
        )
        driver.execute(
            null,
            "INSERT INTO creators VALUES (90, 'Language Watch', 'language watch', 'Language Watch', '', 1, 2)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO creator_watches VALUES (90, 1, '7', ?, 1, 2, NULL, 1)",
            1,
        ) { bindString(0, vectors.joinToString("|") { it.raw }) }
        vectors.forEachIndexed { index, vector ->
            val candidateId = 100L + index
            val workId = 200L + index
            driver.execute(
                null,
                "INSERT INTO discovery_candidates VALUES " +
                    "($candidateId, 7, '/language/$index', 'Language $index', 'language $index', NULL, NULL, ?, " +
                    "0.9, 'SOURCE_LANGUAGE', NULL, 1, 2, 2, 'NEW')",
                1,
            ) { bindString(0, vector.raw) }
            driver.execute(
                null,
                "INSERT INTO canonical_works VALUES " +
                    "($workId, 'Language $index', 'language $index', NULL, ?, 1, 2)",
                1,
            ) { bindString(0, vector.raw) }
        }

        DatabaseMigration.migrateAtomically(driver, 15, CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION)

        vectors.forEachIndexed { index, vector ->
            val candidateId = 100L + index
            val workId = 200L + index
            val candidateExpected = if (vector.valid) {
                "${vector.normalized}:0.9:SINGLE_LANGUAGE_SOURCE"
            } else {
                "und:0.0:UNKNOWN"
            }
            val canonicalExpected = if (vector.valid) {
                "${vector.normalized}:1.0:MANUAL"
            } else {
                "und:0.0:UNKNOWN"
            }
            queryString(
                driver,
                "SELECT language_tag || ':' || printf('%.1f', confidence) || ':' || evidence_kind " +
                    "FROM author_archive_language_assertions " +
                    "WHERE idempotency_key = 'legacy-language:$candidateId:reading'",
            ) shouldBe candidateExpected
            queryString(
                driver,
                "SELECT language_tag || ':' || printf('%.1f', confidence) || ':' || evidence_kind " +
                    "FROM author_archive_language_assertions " +
                    "WHERE idempotency_key = 'legacy-canonical-language:$workId:original'",
            ) shouldBe canonicalExpected
        }
        queryStrings(
            driver,
            "SELECT language_tag FROM author_archive_watch_languages ORDER BY language_tag",
        ) shouldBe listOf("en", "und", "zh-hans")
        driver.close()
    }

    private fun legacyDriver(url: String = JdbcSqliteDriver.IN_MEMORY): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(url)
        val fixture = requireNotNull(javaClass.getResource("/creator/creator-schema-v15.sql")).readText()
        fixture.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { driver.execute(null, it, 0) }
        driver.execute(
            null,
            "CREATE TABLE mangas(" +
                "_id INTEGER NOT NULL PRIMARY KEY, source INTEGER NOT NULL, url TEXT NOT NULL, " +
                "artist TEXT, author TEXT, title TEXT NOT NULL, thumbnail_url TEXT, " +
                "favorite INTEGER NOT NULL DEFAULT 0, status INTEGER NOT NULL DEFAULT 0, " +
                "initialized INTEGER NOT NULL DEFAULT 0, " +
                "viewer INTEGER NOT NULL DEFAULT 0, " +
                "chapter_flags INTEGER NOT NULL DEFAULT 0, " +
                "cover_last_modified INTEGER NOT NULL DEFAULT 0, date_added INTEGER NOT NULL DEFAULT 0)",
            0,
        )
        driver.execute(null, "CREATE TABLE chapters(_id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL)", 0)
        return driver
    }

    private fun seedV27ArchiveFacts(driver: SqlDriver) {
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, sort_name, " +
                "status, needs_review, created_at, last_modified_at) " +
                "VALUES (1, 'creator:cache-migration', 'Cache migration author', 'cache migration author', " +
                "'Cache migration author', 'ACTIVE', 0, 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                "confidence, is_manual, created_at, last_modified_at) " +
                "VALUES (1, 'Kept alias', 'kept alias', 'USER', 'migration fixture', 1, 1, 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_source_works(_id, source_id, stable_source_url, title, normalized_title, " +
                "first_seen_at, last_seen_at) VALUES (1, 91, '/kept-work', 'Kept work', 'kept work', 2, 3)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_source_work_creators(source_work_id, creator_id, role, creator_order, " +
                "origin, verification, source_text, confidence, evidence, created_at, last_modified_at) " +
                "VALUES (1, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 'Cache migration author', 1, 'kept fact', 2, 3)",
            0,
        )
    }

    private fun seedLegacyArchive(driver: SqlDriver) {
        seedManga(driver, id = 9, source = 7, url = "/legacy/rejected", title = "Rejected")
        seedManga(driver, id = 10, source = 7, url = "/legacy/confirmed", title = "Confirmed")
        driver.execute(null, "INSERT INTO creators VALUES (1, 'ONE', 'one', 'ONE', 'One|ＯＮＥ', 1, 2)", 0)
        driver.execute(null, "INSERT INTO creators VALUES (2, 'Murata', 'murata', 'Murata', '', 1, 2)", 0)
        driver.execute(null, "INSERT INTO manga_creators VALUES (9, 1, 'unknown', 'ONE', 0.4, 'search')", 0)
        driver.execute(null, "INSERT INTO manga_creators VALUES (9, 1, 'author', 'ONE', 1.0, 'details')", 0)
        driver.execute(null, "INSERT INTO creator_watches VALUES (1, 1, '7|8', 'en|pt-BR', 3, 4, NULL, 1)", 0)
        driver.execute(
            null,
            "INSERT INTO discovery_candidates VALUES (21, 7, '/candidate', 'Candidate', 'candidate', 'ONE', NULL, " +
                "'BL', 0.9, 'SOURCE_LANGUAGE', NULL, 1, 2, 2, 'IGNORED')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO discovery_candidate_creators VALUES (21, 1, 'unknown', 'ONE', 0.4, 'search')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO discovery_candidate_creators VALUES (21, 1, 'author', 'ONE', 1.0, 'details')",
            0,
        )
        driver.execute(null, "INSERT INTO canonical_works VALUES (31, 'Legacy Work', 'legacy work', 1, 'ja', 1, 2)", 0)
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES (9, 31, 0.8, 'manual reject', 'REJECTED', 1, 1, 2)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES (10, 31, 1.0, 'manual confirm', 'CONFIRMED', 1, 1, 2)",
            0,
        )
        seedManga(driver, id = 11, source = 7, url = "/legacy/candidate", title = "Candidate Match")
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES " +
                "(11, 31, 0.6, 'legacy odd candidate', 'CANDIDATE', 1, 1, 2)",
            0,
        )
    }

    private fun seedManga(driver: SqlDriver, id: Long, source: Long, url: String, title: String) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, favorite, status, initialized, viewer, chapter_flags, " +
                "cover_last_modified, date_added) VALUES (?, ?, ?, ?, 1, 0, 0, 0, 0, 0, 0)",
            4,
        ) {
            bindLong(0, id)
            bindLong(1, source)
            bindString(2, url)
            bindString(3, title)
        }
    }

    private fun archiveTables(driver: SqlDriver): List<String> = queryStrings(
        driver,
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'author_archive_%' ORDER BY name",
    )

    private fun archiveIndexes(driver: SqlDriver): List<String> = queryStrings(
        driver,
        "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_author_archive_%' ORDER BY name",
    )

    private fun archiveForeignKeys(driver: SqlDriver): List<String> = archiveTables(driver).flatMap { table ->
        queryStrings(
            driver,
            "SELECT '$table:' || \"from\" || ':' || \"table\" || ':' || \"to\" || ':' || on_delete " +
                "FROM pragma_foreign_key_list('$table') ORDER BY id",
        )
    }.sorted()

    private fun foreignKeyViolations(driver: SqlDriver): List<String> = queryStrings(
        driver,
        "SELECT \"table\" || ':' || rowid || ':' || parent FROM pragma_foreign_key_check",
    )

    private fun queryLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryString(driver: SqlDriver, sql: String): String? = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        0,
    ).value

    private fun queryStrings(driver: SqlDriver, sql: String): List<String> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val values = mutableListOf<String>()
            while (cursor.next().value) values += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(values)
        },
        0,
    ).value

    private data class LanguageVector(
        val raw: String,
        val normalized: String,
        val valid: Boolean,
    )
}
