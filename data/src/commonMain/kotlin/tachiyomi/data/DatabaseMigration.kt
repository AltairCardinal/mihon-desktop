package tachiyomi.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver

/** Runs generated SQLDelight migrations as one atomic database change. */
object DatabaseMigration {
    const val COMPATIBILITY_SCHEMA_VERSION = 31L

    fun migrateAtomically(driver: SqlDriver, oldVersion: Long, newVersion: Long) {
        require(oldVersion <= newVersion) {
            "Cannot downgrade database from version $oldVersion to $newVersion"
        }
        object : TransacterImpl(driver) {}.transaction {
            rejectIncompatibleExistingObjects(driver)
            Database.Schema.migrate(driver, oldVersion, newVersion)
            if (newVersion >= COMPATIBILITY_SCHEMA_VERSION) {
                validateCompatibility(driver)
            }
            driver.execute(null, "PRAGMA user_version = $newVersion", 0)
        }
    }

    /**
     * Validates the objects that are shared by the published sync and author schema families.
     *
     * Migration 30 creates missing objects with IF NOT EXISTS so both families can converge. A
     * pre-existing object with the same name still has to have the expected shape; otherwise the
     * database is rejected instead of silently accepting an incompatible same-number migration.
     */
    fun validateCompatibility(driver: SqlDriver) {
        requireTable(
            driver,
            table = "author_archive_representative_work_cache",
            columns = setOf("creator_id", "strategy_version", "payload"),
        )
        requireTable(
            driver,
            table = "sync_runtime_runs",
            columns = setOf(
                "run_id",
                "space_id",
                "generation",
                "trigger",
                "state",
                "phase",
                "processed",
                "total",
                "completed",
                "skipped",
                "failed",
                "attempt",
                "next_retry_at",
                "last_progress_at",
                "stop_reason",
                "owner_session",
                "created_at",
                "updated_at",
            ),
        )
        requireTable(
            driver,
            table = "sync_runtime_logs",
            columns = setOf("run_id", "log_key", "title", "detail", "status", "created_at"),
        )
        requireObject(driver, "index", "sync_runtime_active")
        requireObject(driver, "index", "sync_runtime_log_order")
    }

    private fun rejectIncompatibleExistingObjects(driver: SqlDriver) {
        listOf(
            "author_archive_representative_work_cache" to setOf("creator_id", "strategy_version", "payload"),
            "sync_runtime_runs" to setOf(
                "run_id", "space_id", "generation", "trigger", "state", "phase", "processed", "total",
                "completed", "skipped", "failed", "attempt", "next_retry_at", "last_progress_at",
                "stop_reason", "owner_session", "created_at", "updated_at",
            ),
            "sync_runtime_logs" to setOf("run_id", "log_key", "title", "detail", "status", "created_at"),
        ).forEach { (table, columns) ->
            if (hasObject(driver, "table", table)) requireTable(driver, table, columns)
        }
    }

    private fun requireTable(driver: SqlDriver, table: String, columns: Set<String>) {
        requireObject(driver, "table", table)
        val actualColumns = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM pragma_table_info(?)",
            parameters = 1,
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(requireNotNull(cursor.getString(0)))
                    },
                )
            },
            binders = { bindString(0, table) },
        ).value
        require(actualColumns.containsAll(columns)) {
            "Incompatible $table schema; missing columns: ${columns - actualColumns}"
        }
    }

    private fun requireObject(driver: SqlDriver, type: String, name: String) {
        require(hasObject(driver, type, name)) { "Missing required SQLite $type: $name" }
    }

    private fun hasObject(driver: SqlDriver, type: String, name: String): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?)",
        parameters = 2,
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
        },
        binders = {
            bindString(0, type)
            bindString(1, name)
        },
    ).value
}
