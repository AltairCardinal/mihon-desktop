package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Synthetic published v32 shapes shared by the Desktop and Android production-driver tests. */
object LegacySyncSchema32Contract {
    fun prepareWithoutRuntime(driver: SqlDriver) = prepare(driver, hasRuntime = false)

    fun prepareWithRuntime(driver: SqlDriver) = prepare(driver, hasRuntime = true)

    fun assertMigrated(driver: SqlDriver, hadRuntime: Boolean) {
        check(queryLong(driver, "PRAGMA user_version") == Database.Schema.version)
        check(queryLong(driver, "SELECT COUNT(*) FROM sync_remote_guards WHERE latest_head = 'preserved-head'") == 1L)
        if (hadRuntime) {
            check(queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_runs WHERE run_id = 'preserved-run'") == 1L)
            check(queryLong(driver, "SELECT uploaded FROM sync_runtime_runs WHERE run_id = 'preserved-run'") == 2L)
            check(
                queryLong(driver, "SELECT confirmed_items FROM sync_runtime_runs WHERE run_id = 'preserved-run'") == 5L,
            )
            check(queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_logs WHERE run_id = 'preserved-run'") == 1L)
        } else {
            check(queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_runs") == 0L)
            check(queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_logs") == 0L)
        }
        check(queryLong(driver, "SELECT COUNT(*) FROM sync_runtime_confirmations") == 0L)
        DatabaseMigration.validateCompatibility(driver)
    }

    fun assertRepairRolledBack(driver: SqlDriver) {
        check(queryLong(driver, "PRAGMA user_version") == 32L)
        check(queryLong(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sync_runtime_runs'") == 0L)
        check(queryLong(driver, "SELECT COUNT(*) FROM sync_remote_guards WHERE latest_head = 'preserved-head'") == 1L)
    }

    fun removeChapterDirectoryAdditions(driver: SqlDriver) {
        listOf(
            "chapter_id_floor_insert_guard",
            "chapter_id_floor_delete_guard",
            "chapter_id_floor_insert",
            "chapter_id_floor_delete",
        ).forEach {
            driver.execute(null, "DROP TRIGGER IF EXISTS $it", 0)
        }
        listOf("chapter_directory_phases", "chapter_url_aliases", "chapter_id_floor").forEach {
            driver.execute(null, "DROP TABLE IF EXISTS $it", 0)
        }
    }

    private fun prepare(driver: SqlDriver, hasRuntime: Boolean) {
        Database.Schema.create(driver)
        removeChapterDirectoryAdditions(driver)
        listOf("sync_events_by_batch_confirmation", "sync_pending_upload_round").forEach {
            driver.execute(null, "DROP INDEX IF EXISTS $it", 0)
        }
        listOf(
            "sync_runtime_confirmations",
            "sync_snapshot_manifest_entries",
            "sync_snapshot_manifest_batches",
            "sync_snapshot_manifests",
            "sync_http_account_gates",
        ).forEach { driver.execute(null, "DROP TABLE $it", 0) }
        driver.execute(null, "ALTER TABLE sync_remote_guards DROP COLUMN revision", 0)
        if (hasRuntime) {
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN confirmed_items", 0)
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN uploaded_baseline", 0)
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN downloaded_baseline", 0)
            driver.execute(null, "ALTER TABLE sync_runtime_runs RENAME COLUMN attempt_id TO attempt", 0)
            driver.execute(null, "ALTER TABLE sync_runtime_runs DROP COLUMN network_failure_count", 0)
            driver.execute(
                null,
                "INSERT INTO sync_runtime_runs(run_id, space_id, generation, trigger, state, phase, " +
                    "uploaded, downloaded, attempt, last_progress_at, created_at, updated_at) " +
                    "VALUES ('preserved-run', 'space', 1, 'MANUAL', 'SUCCEEDED', 'COMPLETE', 2, 3, 1, 1, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO sync_runtime_logs(run_id, log_key, title, detail, status, created_at) " +
                    "VALUES ('preserved-run', 'start', 'Preserved', 'Published v32 run', 'INFO', 1)",
                0,
            )
        } else {
            driver.execute(null, "DROP TABLE sync_runtime_logs", 0)
            driver.execute(null, "DROP TABLE sync_runtime_runs", 0)
        }
        driver.execute(
            null,
            "INSERT INTO sync_remote_guards(space_id, generation, repository_owner, repository_name, " +
                "repository_branch, latest_head) VALUES ('space', 1, 'owner', 'repo', 'main', 'preserved-head')",
            0,
        )
        driver.execute(null, "PRAGMA user_version = 32", 0)
    }

    private fun queryLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value
}
