package tachiyomi.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver

/** Runs generated SQLDelight migrations as one atomic database change. */
object DatabaseMigration {
    const val COMPATIBILITY_SCHEMA_VERSION = 38L

    fun migrateAtomically(driver: SqlDriver, oldVersion: Long, newVersion: Long) {
        require(oldVersion <= newVersion) {
            "Cannot downgrade database from version $oldVersion to $newVersion"
        }
        object : TransacterImpl(driver) {}.transaction {
            rejectIncompatibleExistingObjects(driver)
            repairMissingRuntimeFamilyForSchema32(driver, oldVersion)
            Database.Schema.migrate(driver, oldVersion, newVersion)
            if (newVersion >= COMPATIBILITY_SCHEMA_VERSION) {
                validateCompatibility(driver)
            }
            driver.execute(null, "PRAGMA user_version = $newVersion", 0)
        }
    }

    /**
     * Replays the schema-30 bridge and schema-31 additions for the published v32 author branch.
     * Call only inside the same upgrade transaction as the remaining generated migrations.
     */
    fun repairMissingRuntimeFamilyForSchema32(driver: SqlDriver, recordedVersion: Long) {
        if (recordedVersion != 32L) return
        val objects = listOf(
            "table" to "sync_runtime_runs",
            "index" to "sync_runtime_active",
            "table" to "sync_runtime_logs",
            "index" to "sync_runtime_log_order",
        )
        val present = objects.map { (type, name) -> hasObject(driver, type, name) }
        if (present.all { it }) return
        require(present.none { it }) {
            "Incomplete sync_runtime schema for version 32; refusing to reconstruct a partial family"
        }
        Database.Schema.migrate(driver, 30, 32)
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
                "uploaded",
                "downloaded",
                "confirmed_items",
                "uploaded_baseline",
                "downloaded_baseline",
                "attempt_id",
                "network_failure_count",
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
        requireTable(
            driver,
            table = "sync_runtime_confirmations",
            columns = setOf("run_id", "direction", "batch_id", "item_count", "status"),
        )
        requireTable(
            driver,
            table = "sync_snapshot_manifests",
            columns = setOf(
                "space_id",
                "generation",
                "account_id",
                "repository_id",
                "repository_owner",
                "repository_name",
                "repository_branch",
                "api_origin",
                "validator_version",
                "validation_scope",
                "connection_revision",
                "head_sha",
                "head_fingerprint",
                "checksum",
                "tree_sha",
                "truncated",
                "manifest_json",
            ),
        )
        requireTable(
            driver,
            "sync_remote_guards",
            setOf("space_id", "generation", "latest_head", "blocked", "revision"),
        )
        requireTable(
            driver,
            "sync_snapshot_manifest_entries",
            setOf("space_id", "generation", "path", "mode", "type", "sha", "size", "content"),
        )
        requireTable(
            driver,
            "sync_snapshot_manifest_batches",
            setOf(
                "space_id", "generation", "batch_id", "path", "digest_hex", "first_seq", "last_seq",
                "actor_id", "epoch", "index_path", "index_ciphertext_digest_hex",
            ),
        )
        requireTable(
            driver,
            table = "sync_http_account_gates",
            columns = setOf("account_id", "not_before_ms", "updated_at"),
        )
        requireObject(driver, "index", "sync_runtime_active")
        requireObject(driver, "index", "sync_runtime_log_order")
    }

    private fun rejectIncompatibleExistingObjects(driver: SqlDriver) {
        listOf(
            "author_archive_representative_work_cache" to setOf("creator_id", "strategy_version", "payload"),
            "sync_runtime_runs" to setOf(
                "run_id", "space_id", "generation", "trigger", "state", "phase", "processed", "total",
                "completed", "skipped", "failed", "next_retry_at", "last_progress_at", "stop_reason",
                "owner_session", "created_at", "updated_at",
            ),
            "sync_runtime_logs" to setOf("run_id", "log_key", "title", "detail", "status", "created_at"),
            "sync_snapshot_manifests" to setOf(
                "space_id", "generation", "account_id", "repository_id", "repository_owner", "repository_name",
                "repository_branch", "api_origin", "validator_version", "validation_scope", "connection_revision",
                "head_sha", "head_fingerprint", "checksum", "manifest_json",
            ),
            "sync_remote_guards" to setOf(
                "space_id",
                "generation",
                "repository_owner",
                "repository_name",
                "repository_branch",
                "blocked",
                "latest_head",
            ),
            "sync_http_account_gates" to setOf("account_id", "not_before_ms", "updated_at"),
        ).forEach { (table, columns) ->
            if (hasObject(driver, "table", table)) {
                if (table == "sync_runtime_runs") {
                    val legacyShape = columns + "attempt"
                    val currentShape = columns + setOf("attempt_id", "network_failure_count")
                    requireTableWithOneShape(driver, table, legacyShape, currentShape)
                } else {
                    requireTable(driver, table, columns)
                }
            }
        }
    }

    private fun requireTableWithOneShape(
        driver: SqlDriver,
        table: String,
        vararg shapes: Set<String>,
    ) {
        requireObject(driver, "table", table)
        val actualColumns = tableColumns(driver, table)
        require(shapes.any(actualColumns::containsAll)) {
            "Incompatible $table schema; expected one of ${shapes.toList()}, found $actualColumns"
        }
    }

    private fun requireTable(driver: SqlDriver, table: String, columns: Set<String>) {
        requireObject(driver, "table", table)
        val actualColumns = tableColumns(driver, table)
        require(actualColumns.containsAll(columns)) {
            "Incompatible $table schema; missing columns: ${columns - actualColumns}"
        }
    }

    private fun tableColumns(driver: SqlDriver, table: String): Set<String> = driver.executeQuery(
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
