package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DatabaseMigrationCompatibilityTest {
    @Test
    fun `current schema reserves compatibility migration after published sync schema`() {
        Database.Schema.version shouldBe 38L
    }

    @Test
    fun `schema 37 preserves confirmed downloads only for successful runs`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE sync_runtime_confirmations", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN confirmed_items", 0)
        driver.execute(null, "DROP INDEX sync_events_by_batch_confirmation", 0)
        driver.execute(null, "DROP INDEX sync_pending_upload_round", 0)
        listOf("SUCCEEDED", "RUNNING").forEach { state ->
            driver.execute(
                null,
                "INSERT INTO sync_runtime_runs(run_id, space_id, generation, trigger, state, phase, " +
                    "last_progress_at, created_at, updated_at, uploaded, downloaded) VALUES " +
                    "('$state', 'space', 1, 'MANUAL', '$state', 'CONFIRMING', 1, 1, 1, 2, 3)",
                0,
            )
        }
        driver.execute(null, "PRAGMA user_version = 37", 0)

        DatabaseMigration.migrateAtomically(driver, 37, Database.Schema.version)

        queryLong(driver, "SELECT confirmed_items FROM sync_runtime_runs WHERE run_id = 'SUCCEEDED'") shouldBe 5L
        queryLong(driver, "SELECT confirmed_items FROM sync_runtime_runs WHERE run_id = 'RUNNING'") shouldBe 2L
        queryLong(driver, "PRAGMA user_version") shouldBe 38L
    }

    @Test
    fun `schema 36 migration adds guard revision and normalized snapshot rows without deleting metadata`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        dropSnapshotManifestAdditions(driver)
        driver.execute(
            null,
            "INSERT INTO sync_remote_guards(space_id, generation, repository_owner, repository_name, " +
                "repository_branch, latest_head) VALUES ('space', 1, 'owner', 'repo', 'branch', 'head')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_snapshot_manifests(space_id, generation, account_id, repository_id, " +
                "repository_owner, repository_name, repository_branch, api_origin, validator_version, " +
                "validation_scope, connection_revision, head_sha, head_fingerprint, checksum, manifest_json) " +
                "VALUES ('space', 1, 7, 9, 'owner', 'repo', 'branch', 'https://api.example.test', 'sync-v1', " +
                "'scope', 'revision', 'head', 'fingerprint', 'checksum', '{\"legacy\":true}')",
            0,
        )
        driver.execute(null, "PRAGMA user_version = 36", 0)

        DatabaseMigration.migrateAtomically(driver, 36, Database.Schema.version)

        queryLong(driver, "SELECT revision FROM sync_remote_guards WHERE space_id = 'space'") shouldBe 0L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM sync_snapshot_manifests WHERE space_id = 'space' " +
                "AND manifest_json = '{\"legacy\":true}' AND tree_sha = ''",
        ) shouldBe 1L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'sync_snapshot_manifest_entries'",
        ) shouldBe 1L
        queryLong(driver, "PRAGMA user_version") shouldBe 38L
    }

    @Test
    fun `schema 33 migration creates the durable snapshot manifest table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE sync_snapshot_manifests", 0)
        driver.execute(null, "PRAGMA user_version = 33", 0)

        DatabaseMigration.migrateAtomically(driver, 33, 34)

        queryLong(driver, "PRAGMA user_version") shouldBe 34L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM pragma_table_info('sync_snapshot_manifests') " +
                "WHERE name IN ('account_id', 'repository_id', 'api_origin', 'validator_version', " +
                "'validation_scope', 'connection_revision', 'head_fingerprint', 'checksum', 'manifest_json')",
        ) shouldBe 9L
    }

    @Test
    fun `schema 34 migration separates retry failures from the persisted claim id`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE sync_http_account_gates", 0)
        driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
        driver.execute(
            null,
            """
            CREATE TABLE sync_runtime_runs(
                run_id TEXT NOT NULL PRIMARY KEY,
                space_id TEXT NOT NULL,
                generation INTEGER NOT NULL,
                trigger TEXT NOT NULL,
                state TEXT NOT NULL,
                phase TEXT NOT NULL,
                processed INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                skipped INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0,
                uploaded INTEGER NOT NULL DEFAULT 0,
                downloaded INTEGER NOT NULL DEFAULT 0,
                uploaded_baseline INTEGER NOT NULL DEFAULT 0,
                downloaded_baseline INTEGER NOT NULL DEFAULT 0,
                attempt INTEGER NOT NULL DEFAULT 0,
                next_retry_at INTEGER NOT NULL DEFAULT 0,
                last_progress_at INTEGER NOT NULL,
                stop_reason TEXT,
                owner_session TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX sync_runtime_active ON sync_runtime_runs(space_id, generation, state)", 0)
        driver.execute(
            null,
            """
            CREATE TABLE sync_runtime_logs(
                run_id TEXT NOT NULL REFERENCES sync_runtime_runs(run_id) ON DELETE CASCADE,
                log_key TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (run_id, log_key)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX sync_runtime_log_order ON sync_runtime_logs(run_id, created_at DESC)", 0)
        driver.execute(
            null,
            """
            INSERT INTO sync_runtime_runs(
                run_id, space_id, generation, trigger, state, phase, attempt, next_retry_at,
                last_progress_at, stop_reason, created_at, updated_at
            ) VALUES ('legacy-run', 'space', 1, 'RECOVERY', 'WAITING_RETRY', 'CHECKING', 2, 12000, 10, 'network', 1, 10)
            """.trimIndent(),
            0,
        )
        dropSnapshotManifestAdditions(driver)
        driver.execute(null, "PRAGMA user_version = 34", 0)

        DatabaseMigration.migrateAtomically(driver, 34, Database.Schema.version)

        queryLong(driver, "PRAGMA user_version") shouldBe 38L
        queryLong(driver, "SELECT attempt_id FROM sync_runtime_runs WHERE run_id = 'legacy-run'") shouldBe 2L
        queryLong(driver, "SELECT network_failure_count FROM sync_runtime_runs WHERE run_id = 'legacy-run'") shouldBe 2L
        queryLong(
            driver,
            "SELECT COUNT(*) FROM pragma_table_info('sync_http_account_gates') " +
                "WHERE name IN ('account_id', 'not_before_ms', 'updated_at')",
        ) shouldBe 3L
    }

    @Test
    fun `same-number manifest table with incompatible shape fails without advancing schema`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE sync_snapshot_manifests", 0)
        driver.execute(null, "CREATE TABLE sync_snapshot_manifests(space_id TEXT NOT NULL PRIMARY KEY)", 0)
        driver.execute(null, "PRAGMA user_version = 33", 0)

        val failure = shouldThrow<IllegalArgumentException> {
            DatabaseMigration.migrateAtomically(driver, 33, Database.Schema.version)
        }

        failure.message.orEmpty() shouldContain "sync_snapshot_manifests"
        queryLong(driver, "PRAGMA user_version") shouldBe 33L
    }

    @Test
    fun `published sync schema upgrades without losing runtime data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        prepareSyncSchema28(driver)

        DatabaseMigration.migrateAtomically(driver, 28, Database.Schema.version)

        queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_runs") shouldBe 1L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_representative_work_cache") shouldBe 0L
        queryLong(driver, "SELECT COUNT(*) FROM author_archive_source_date_quality") shouldBe 0L
        queryLong(driver, "PRAGMA user_version") shouldBe 38L
    }

    @Test
    fun `author schema 29 receives sync tables while preserving author data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        prepareAuthorSchema29(driver)

        DatabaseMigration.migrateAtomically(driver, 29, Database.Schema.version)

        queryLong(driver, "SELECT COUNT(*) FROM author_archive_creators") shouldBe 1L
        queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_runs") shouldBe 0L
        queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_logs") shouldBe 0L
        queryLong(driver, "PRAGMA user_version") shouldBe 38L
    }

    @Test
    fun `schema 28 sync file migrates after close and reopen without losing runtime data`() {
        withTemporaryDatabase { file ->
            JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}").use(::prepareSyncSchema28)

            JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}").use { reopened ->
                queryLong(reopened, "PRAGMA user_version") shouldBe 28L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM pragma_table_info('sync_runtime_runs') WHERE name = 'attempt'",
                ) shouldBe 1L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM pragma_table_info('sync_runtime_runs') WHERE name = 'attempt_id'",
                ) shouldBe 0L

                DatabaseMigration.migrateAtomically(reopened, 28, Database.Schema.version)

                queryLong(reopened, "SELECT COUNT(*) FROM sync_runtime_runs WHERE run_id = 'legacy-run'") shouldBe 1L
                queryLong(reopened, "SELECT attempt_id FROM sync_runtime_runs WHERE run_id = 'legacy-run'") shouldBe 3L
                queryLong(
                    reopened,
                    "SELECT network_failure_count FROM sync_runtime_runs WHERE run_id = 'legacy-run'",
                ) shouldBe 3L
                queryLong(reopened, "SELECT COUNT(*) FROM sync_runtime_logs WHERE run_id = 'legacy-run'") shouldBe 1L
                queryLong(reopened, "PRAGMA user_version") shouldBe Database.Schema.version
                DatabaseMigration.validateCompatibility(reopened)
            }
        }
    }

    @Test
    fun `schema 29 author file migrates after close and reopen without losing author data`() {
        withTemporaryDatabase { file ->
            JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}").use(::prepareAuthorSchema29)

            JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}").use { reopened ->
                queryLong(reopened, "PRAGMA user_version") shouldBe 29L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'author_archive_source_date_quality'",
                ) shouldBe 0L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM author_archive_creators WHERE portable_key = 'creator-1'",
                ) shouldBe 1L

                DatabaseMigration.migrateAtomically(reopened, 29, Database.Schema.version)

                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM author_archive_creators WHERE portable_key = 'creator-1'",
                ) shouldBe 1L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM author_archive_source_date_quality",
                ) shouldBe 0L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM author_archive_source_date_quality_current WHERE source_id = 42",
                ) shouldBe 0L
                queryLong(
                    reopened,
                    "SELECT COUNT(*) FROM author_archive_source_date_quality_samples WHERE source_id = 42",
                ) shouldBe 0L
                queryLong(reopened, "SELECT COUNT(*) FROM sync_runtime_runs") shouldBe 0L
                queryLong(reopened, "SELECT COUNT(*) FROM sync_runtime_logs") shouldBe 0L
                queryLong(reopened, "PRAGMA user_version") shouldBe Database.Schema.version
                DatabaseMigration.validateCompatibility(reopened)
            }
        }
    }

    @Test
    fun `unknown same-number sync table shape fails without advancing the database`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
        driver.execute(
            null,
            "CREATE TABLE sync_runtime_runs(run_id TEXT NOT NULL PRIMARY KEY, space_id TEXT NOT NULL)",
            0,
        )
        driver.execute(null, "PRAGMA user_version = 29", 0)

        val failure = shouldThrow<IllegalArgumentException> {
            DatabaseMigration.migrateAtomically(driver, 29, Database.Schema.version)
        }

        failure.message.orEmpty() shouldContain "sync_runtime_runs"
        queryLong(driver, "PRAGMA user_version") shouldBe 29L
    }

    private fun dropAuthorAdditions(driver: JdbcSqliteDriver) {
        listOf(
            "author_archive_source_date_quality_samples",
            "author_archive_source_date_quality_current",
            "author_archive_source_date_quality",
            "author_archive_representative_work_cache",
        ).forEach { table ->
            driver.execute(null, "DROP TABLE IF EXISTS $table", 0)
        }
        listOf(
            "first_seen_date",
            "first_seen_zone",
            "chapter_count_state",
            "catalog_chapter_count",
            "latest_chapter_at",
        ).forEach { column ->
            if (hasColumn(driver, "author_archive_source_works", column)) {
                driver.execute(null, "ALTER TABLE author_archive_source_works DROP COLUMN $column", 0)
            }
        }
    }

    private fun hasColumn(driver: JdbcSqliteDriver, table: String, column: String): Boolean = driver.executeQuery(
        null,
        "SELECT EXISTS(SELECT 1 FROM pragma_table_info(?) WHERE name = ?)",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
        },
        2,
        {
            bindString(0, table)
            bindString(1, column)
        },
    ).value

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun prepareSyncSchema28(driver: JdbcSqliteDriver) {
        Database.Schema.create(driver)
        driver.execute(
            null,
            "INSERT INTO sync_runtime_runs(" +
                "run_id, space_id, generation, trigger, state, phase, attempt_id, next_retry_at, " +
                "last_progress_at, stop_reason, created_at, updated_at" +
                ") VALUES ('legacy-run', 'space-1', 1, 'RECOVERY', 'WAITING_RETRY', 'CHECKING', " +
                "3, 12000, 10, 'network', 1, 10)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_runtime_logs(run_id, log_key, title, detail, status, created_at) " +
                "VALUES ('legacy-run', 'start', 'Recovered', 'Retrying old run', 'INFO', 10)",
            0,
        )
        driver.execute(null, "DROP TRIGGER IF EXISTS author_archive_source_work_first_seen_defaults", 0)
        dropAuthorAdditions(driver)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN uploaded", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN downloaded", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN uploaded_baseline", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN downloaded_baseline", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs RENAME COLUMN attempt_id TO attempt", 0)
        driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN network_failure_count", 0)
        driver.execute(null, "DROP TABLE sync_snapshot_manifests", 0)
        driver.execute(null, "DROP TABLE sync_http_account_gates", 0)
        dropSnapshotManifestAdditions(driver)
        driver.execute(null, "PRAGMA user_version = 28", 0)
    }

    private fun prepareAuthorSchema29(driver: JdbcSqliteDriver) {
        Database.Schema.create(driver)
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(" +
                "portable_key, display_name, normalized_name, status, created_at, last_modified_at" +
                ") VALUES ('creator-1', 'Creator', 'creator', 'ACTIVE', 10, 10)",
            0,
        )
        driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
        driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
        driver.execute(null, "DROP TABLE sync_snapshot_manifests", 0)
        driver.execute(null, "DROP TABLE sync_http_account_gates", 0)
        dropSnapshotManifestAdditions(driver)
        driver.execute(null, "DROP TABLE author_archive_representative_work_cache", 0)
        listOf(
            "author_archive_source_date_quality_samples",
            "author_archive_source_date_quality_current",
            "author_archive_source_date_quality",
        ).forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
        driver.execute(null, "PRAGMA user_version = 29", 0)
    }

    private fun dropSnapshotManifestAdditions(driver: JdbcSqliteDriver) {
        driver.execute(null, "DROP INDEX IF EXISTS sync_events_by_batch_confirmation", 0)
        driver.execute(null, "DROP INDEX IF EXISTS sync_pending_upload_round", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_runtime_confirmations", 0)
        if (hasColumn(driver, "sync_runtime_runs", "confirmed_items")) {
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN confirmed_items", 0)
        }
        driver.execute(null, "DROP TABLE IF EXISTS sync_snapshot_manifest_entries", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_snapshot_manifest_batches", 0)
        if (hasColumn(driver, "sync_remote_guards", "revision")) {
            driver.execute(null, "ALTER TABLE sync_remote_guards DROP COLUMN revision", 0)
        }
        listOf("tree_sha", "truncated").forEach { column ->
            if (hasColumn(driver, "sync_snapshot_manifests", column)) {
                driver.execute(null, "ALTER TABLE sync_snapshot_manifests DROP COLUMN $column", 0)
            }
        }
    }

    private fun withTemporaryDatabase(block: (Path) -> Unit) {
        val file = Files.createTempFile("mihon-schema-migration-", ".sqlite")
        try {
            block(file)
        } finally {
            listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
                Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + suffix))
            }
        }
    }
}
