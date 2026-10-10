package tachiyomi.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver

/** Runs generated SQLDelight migrations as one atomic database change. */
object DatabaseMigration {
    const val COMPATIBILITY_SCHEMA_VERSION = 39L

    fun migrateAtomically(driver: SqlDriver, oldVersion: Long, newVersion: Long) {
        require(oldVersion <= newVersion) {
            "Cannot downgrade database from version $oldVersion to $newVersion"
        }
        object : TransacterImpl(driver) {}.transaction {
            rejectIncompatibleExistingObjects(driver)
            repairMissingRuntimeFamilyForSchema32(driver, oldVersion)
            repairMissingAuthorColumnsForSyncSchema(driver, oldVersion)
            // Both published branches used migration 40 for different object families. Converge
            // at schema 41 before the unchanged sync 41/42 migrations consume the pause clock.
            val familyBoundary = 41L
            if (oldVersion < familyBoundary) {
                Database.Schema.migrate(driver, oldVersion, minOf(newVersion, familyBoundary))
            }
            if (newVersion > familyBoundary) {
                repairPublishedSchema40Families(driver, maxOf(oldVersion, familyBoundary))
                Database.Schema.migrate(driver, maxOf(oldVersion, familyBoundary), newVersion)
            }
            if (newVersion >= COMPATIBILITY_SCHEMA_VERSION) {
                validateCompatibility(driver)
            }
            driver.execute(null, "PRAGMA user_version = $newVersion", 0)
        }
    }

    /** Repairs only completely absent released families; partial families fail the transaction. */
    private fun repairPublishedSchema40Families(driver: SqlDriver, recordedVersion: Long) {
        val chapterFamily = listOf(
            "table" to "chapter_url_aliases",
            "index" to "chapter_url_alias_chapter",
            "trigger" to "consistent_chapter_url_alias_insert",
            "trigger" to "consistent_chapter_url_alias_update",
            "table" to "chapter_id_floor",
            "trigger" to "chapter_id_floor_insert_guard",
            "trigger" to "chapter_id_floor_delete_guard",
            "trigger" to "chapter_id_floor_insert",
            "trigger" to "chapter_id_floor_delete",
            "table" to "chapter_directory_phases",
        )
        val pauseFamily = listOf(
            "table" to "sync_runtime_pause_clock",
            "trigger" to "sync_runtime_pause_transition",
        )
        val chaptersPresent = requireCompleteOrAbsentFamily(driver, "chapter identity", chapterFamily)
        val pausePresent = requireCompleteOrAbsentFamily(driver, "sync pause clock", pauseFamily)
        require(pausePresent || recordedVersion == 41L) {
            "Missing sync pause clock in published schema $recordedVersion; refusing to reconstruct lost clocks"
        }
        if (chaptersPresent) requirePublishedChapterIdentity(driver)
        if (pausePresent) requirePublishedPauseClock(driver, recordedVersion >= 42)

        if (!chaptersPresent) {
            // Reuse the authoritative, unchanged chapter DDL. This repairs a missing physical
            // family in the sync branch; it never changes or replays an existing chapter family.
            Database.Schema.migrate(driver, 40, 41)
        }
        if (!pausePresent) {
            driver.execute(
                null,
                """
                CREATE TABLE sync_runtime_pause_clock (
                    run_id TEXT NOT NULL PRIMARY KEY REFERENCES sync_runtime_runs(run_id) ON DELETE CASCADE,
                    paused_millis INTEGER NOT NULL DEFAULT 0 CHECK(paused_millis >= 0),
                    paused_at INTEGER
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TRIGGER sync_runtime_pause_transition
                AFTER UPDATE OF state ON sync_runtime_runs
                WHEN old.state != new.state AND (old.state = 'PAUSED_USER' OR new.state = 'PAUSED_USER')
                BEGIN
                    INSERT OR IGNORE INTO sync_runtime_pause_clock(run_id) VALUES (new.run_id);
                    UPDATE sync_runtime_pause_clock SET
                        paused_millis = paused_millis + CASE WHEN old.state = 'PAUSED_USER'
                            THEN MAX(0, new.updated_at - COALESCE(paused_at, old.updated_at)) ELSE 0 END,
                        paused_at = CASE WHEN new.state = 'PAUSED_USER' THEN new.updated_at ELSE NULL END
                    WHERE run_id = new.run_id;
                END
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                "INSERT INTO sync_runtime_pause_clock(run_id, paused_millis, paused_at) " +
                    "SELECT run_id, 0, updated_at FROM sync_runtime_runs WHERE state = 'PAUSED_USER'",
                0,
            )
        }
        requirePublishedChapterIdentity(driver)
        requirePublishedPauseClock(driver, requirePlanClock = pausePresent && recordedVersion >= 42)
    }

    private fun requireCompleteOrAbsentFamily(
        driver: SqlDriver,
        family: String,
        objects: List<Pair<String, String>>,
    ): Boolean {
        val present = objects.map { (type, name) -> hasObject(driver, type, name) }
        require(present.all { it } || present.none { it }) {
            "Incomplete $family schema; refusing partial repair"
        }
        return present.all { it }
    }

    private fun requirePublishedChapterIdentity(driver: SqlDriver) {
        requireTable(driver, "chapter_url_aliases", setOf("chapter_id", "manga_id", "url", "canonical_url"))
        requireTable(driver, "chapter_id_floor", setOf("singleton", "value"))
        requireTable(driver, "chapter_directory_phases", setOf("manga_id", "phase_id", "payload"))
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('chapter_url_aliases') " +
                    "WHERE (name='manga_id' AND pk=1) OR (name='url' AND pk=2)",
            ) == 2L,
        ) { "Incompatible chapter URL alias key" }
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_foreign_key_list('chapter_url_aliases') " +
                    "WHERE on_delete='CASCADE' AND ((\"from\"='chapter_id' " +
                    "AND \"table\"='chapters' AND \"to\"='_id') " +
                    "OR (\"from\"='manga_id' AND \"table\"='mangas' " +
                    "AND \"to\"='_id'))",
            ) == 2L,
        ) { "Incompatible chapter URL alias cascade" }
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('chapter_id_floor') " +
                    "WHERE name='singleton' AND pk=1",
            ) == 1L &&
                queryCount(driver, "SELECT COUNT(*) FROM chapter_id_floor") == 1L &&
                queryCount(
                    driver,
                    "SELECT COUNT(*) FROM chapter_id_floor WHERE singleton=1 " +
                        "AND value >= (SELECT coalesce(max(_id),0) FROM chapters)",
                ) == 1L,
        ) { "Incompatible chapter identity floor" }
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('chapter_directory_phases') " +
                    "WHERE name='manga_id' AND pk=1",
            ) == 1L &&
                queryCount(
                    driver,
                    "SELECT COUNT(*) FROM pragma_foreign_key_list('chapter_directory_phases') " +
                        "WHERE \"from\"='manga_id' AND \"table\"='mangas' " +
                        "AND \"to\"='_id' AND on_delete='CASCADE'",
                ) == 1L,
        ) { "Incompatible chapter directory identity" }
    }

    private fun requirePublishedPauseClock(driver: SqlDriver, requirePlanClock: Boolean) {
        requireTable(driver, "sync_runtime_pause_clock", setOf("run_id", "paused_millis", "paused_at"))
        val planColumns = tableColumns(driver, "sync_runtime_pause_clock") intersect
            setOf("planned_at", "planned_paused_millis")
        require(planColumns.size == 2 || (!requirePlanClock && planColumns.isEmpty())) {
            "Incomplete sync pause plan clock; refusing partial repair"
        }
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('sync_runtime_pause_clock') " +
                    "WHERE name='run_id' AND pk=1",
            ) == 1L &&
                queryCount(
                    driver,
                    "SELECT COUNT(*) FROM pragma_foreign_key_list('sync_runtime_pause_clock') " +
                        "WHERE \"from\"='run_id' AND \"table\"='sync_runtime_runs' AND \"to\"='run_id' " +
                        "AND on_delete='CASCADE'",
                ) == 1L,
        ) { "Incompatible sync pause clock identity" }
    }

    /** Repairs the published author v31/v32 family before the generated sync migrations run. */
    fun repairMissingRuntimeFamilyForSchema32(driver: SqlDriver, recordedVersion: Long) {
        if (recordedVersion !in 31L..32L) return
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
        driver.execute(
            null,
            """
            CREATE TABLE sync_runtime_runs (
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
            CREATE TABLE sync_runtime_logs (
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
        if (recordedVersion == 32L) {
            driver.execute(null, "ALTER TABLE sync_runtime_runs ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0", 0)
            driver.execute(null, "ALTER TABLE sync_runtime_runs ADD COLUMN downloaded INTEGER NOT NULL DEFAULT 0", 0)
        }
    }

    /** The published sync v32-v38 family predates author date snapshots. */
    fun repairMissingAuthorColumnsForSyncSchema(driver: SqlDriver, recordedVersion: Long) {
        if (recordedVersion < 32L) return
        val columns = tableColumns(driver, "author_archive_source_works")
        val missing = setOf(
            "published_date_snapshot_at",
            "published_date_snapshot_basis",
            "published_date_snapshot_reason",
        ) - columns
        if (missing.isEmpty()) return
        require(missing.size == 3) {
            "Incomplete author date snapshot schema for version $recordedVersion; refusing partial repair"
        }
        driver.execute(
            null,
            "ALTER TABLE author_archive_source_works ADD COLUMN published_date_snapshot_at INTEGER " +
                "CHECK(published_date_snapshot_at IS NULL OR published_date_snapshot_at > 0)",
            0,
        )
        driver.execute(null, "ALTER TABLE author_archive_source_works ADD COLUMN published_date_snapshot_basis TEXT", 0)
        driver.execute(
            null,
            "ALTER TABLE author_archive_source_works ADD COLUMN published_date_snapshot_reason TEXT",
            0,
        )
        backfillAuthorPublishedDateSnapshots(driver)
    }

    private fun backfillAuthorPublishedDateSnapshots(driver: SqlDriver) {
        driver.execute(
            null,
            """
            UPDATE author_archive_source_works
            SET published_date_snapshot_at = (
                    SELECT MIN(S.value_at)
                    FROM author_archive_source_date_quality_samples S
                    JOIN author_archive_source_date_quality Q
                        ON Q.extension_package = S.extension_package
                        AND Q.extension_version = S.extension_version
                        AND Q.source_id = S.source_id
                        AND Q.field_kind = S.field_kind
                    WHERE S.source_id = author_archive_source_works.source_id
                        AND S.field_kind = 'WORK_PUBLISHED'
                        AND S.work_natural_key = author_archive_source_works.stable_source_url
                        AND S.value_at > 0
                        AND S.precision = 'DAY'
                        AND S.semantic_confirmed = 1
                        AND S.network_failure = 0
                        AND Q.status = 'TRUSTED'
                ),
                published_date_snapshot_basis = (
                    SELECT S.extension_package || '@' || S.extension_version
                    FROM author_archive_source_date_quality_samples S
                    JOIN author_archive_source_date_quality Q
                        ON Q.extension_package = S.extension_package
                        AND Q.extension_version = S.extension_version
                        AND Q.source_id = S.source_id
                        AND Q.field_kind = S.field_kind
                    WHERE S.source_id = author_archive_source_works.source_id
                        AND S.field_kind = 'WORK_PUBLISHED'
                        AND S.work_natural_key = author_archive_source_works.stable_source_url
                        AND S.value_at > 0
                        AND S.precision = 'DAY'
                        AND S.semantic_confirmed = 1
                        AND S.network_failure = 0
                        AND Q.status = 'TRUSTED'
                    ORDER BY S.value_at ASC, S.observed_at DESC, S._id DESC
                    LIMIT 1
                )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            UPDATE author_archive_source_works
            SET published_date_snapshot_reason = CASE
                WHEN NOT EXISTS(
                    SELECT 1 FROM author_archive_source_date_quality_current C
                    WHERE C.source_id = author_archive_source_works.source_id
                        AND C.field_kind = 'WORK_PUBLISHED'
                ) THEN 'date_quality_not_available'
                WHEN NOT EXISTS(
                    SELECT 1 FROM author_archive_source_date_quality_current C
                    WHERE C.source_id = author_archive_source_works.source_id
                        AND C.field_kind = 'WORK_PUBLISHED'
                        AND C.extension_package || '@' || C.extension_version = published_date_snapshot_basis
                ) THEN 'extension_version_changed'
                WHEN EXISTS(
                    SELECT 1 FROM author_archive_source_date_quality_current C
                    JOIN author_archive_source_date_quality Q
                        ON Q.extension_package = C.extension_package
                        AND Q.extension_version = C.extension_version
                        AND Q.source_id = C.source_id
                        AND Q.field_kind = C.field_kind
                    WHERE C.source_id = author_archive_source_works.source_id
                        AND C.field_kind = 'WORK_PUBLISHED'
                        AND Q.status != 'TRUSTED'
                ) THEN 'date_quality_downgraded'
                ELSE NULL
            END
            WHERE published_date_snapshot_at IS NOT NULL
            """.trimIndent(),
            0,
        )
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
            table = "author_archive_source_works",
            columns = setOf(
                "published_date_snapshot_at",
                "published_date_snapshot_basis",
                "published_date_snapshot_reason",
            ),
        )
        requireTable(
            driver,
            table = "author_archive_presentation_exclusions",
            columns = setOf("creator_id", "source_work_id", "created_at"),
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
            "chapter_pairings" to setOf("chapter_id", "format_version", "page_count", "revision"),
            "chapter_pairing_boundaries" to setOf("chapter_id", "page_ordinal"),
            "chapter_pairing_revisions" to setOf("chapter_id", "revision"),
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
        if (hasObject(driver, "table", "chapter_pairings")) requireChapterPairingShape(driver)
        if (hasObject(driver, "table", "chapter_pairing_boundaries")) requireChapterPairingBoundaryShape(driver)
        if (hasObject(driver, "table", "chapter_pairing_revisions")) requireChapterPairingRevisionShape(driver)
    }

    private fun requireChapterPairingShape(driver: SqlDriver) {
        requireTable(driver, "chapter_pairings", setOf("chapter_id", "format_version", "page_count", "revision"))
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('chapter_pairings') WHERE name='chapter_id' AND pk=1",
            ) ==
                1L,
        )
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_foreign_key_list('chapter_pairings') " +
                    "WHERE \"from\"='chapter_id' AND \"table\"='chapters' AND \"to\"='_id' AND on_delete='CASCADE'",
            ) == 1L,
        ) {
            "Incompatible chapter_pairings key or chapter cascade"
        }
    }

    private fun requireChapterPairingBoundaryShape(driver: SqlDriver) {
        requireTable(driver, "chapter_pairing_boundaries", setOf("chapter_id", "page_ordinal"))
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('chapter_pairing_boundaries') " +
                    "WHERE (name='chapter_id' AND pk=1) OR (name='page_ordinal' AND pk=2)",
            ) == 2L,
        )
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_foreign_key_list('chapter_pairing_boundaries') " +
                    "WHERE \"from\"='chapter_id' AND \"table\"='chapter_pairings' AND \"to\"='chapter_id' " +
                    "AND on_delete='CASCADE'",
            ) == 1L,
        ) {
            "Incompatible chapter_pairing_boundaries key or pairing cascade"
        }
    }

    private fun requireChapterPairingRevisionShape(driver: SqlDriver) {
        requireTable(driver, "chapter_pairing_revisions", setOf("chapter_id", "revision"))
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_table_info('chapter_pairing_revisions') " +
                    "WHERE name='chapter_id' AND pk=1",
            ) == 1L,
        )
        require(
            queryCount(
                driver,
                "SELECT COUNT(*) FROM pragma_foreign_key_list('chapter_pairing_revisions') " +
                    "WHERE \"from\"='chapter_id' AND \"table\"='chapters' AND \"to\"='_id' AND on_delete='CASCADE'",
            ) == 1L,
        ) {
            "Incompatible chapter_pairing_revisions key or chapter cascade"
        }
    }

    private fun queryCount(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        parameters = 0,
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) requireNotNull(cursor.getLong(0)) else 0L)
        },
        binders = null,
    ).value

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
