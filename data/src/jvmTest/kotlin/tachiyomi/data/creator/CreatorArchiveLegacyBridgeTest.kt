package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

class CreatorArchiveLegacyBridgeTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: JvmDatabaseHandler
    private lateinit var bridge: CreatorArchiveLegacyBridge

    @BeforeEach
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        handler = JvmDatabaseHandler(database, driver)
        bridge = CreatorArchiveLegacyBridge(
            handler = handler,
            clock = { 100L },
            portableKeyFactory = { "bridge-portable-${queryLong("SELECT COUNT(*) FROM author_archive_creators") + 1}" },
        )
    }

    @Test
    fun `legacy aliases merge occupied roots after readiness and preserve legacy keys`() = runBlocking<Unit> {
        val repository = CreatorRepositoryImpl(handler)
        val first = repository.upsertCreator("First")
        val second = repository.upsertCreator("Second")
        seedLegacyCreator(10L, "First")
        seedLegacyCreator(11L, "Second")
        bridge.importIncremental()
        seedLegacyCreator(12L, "Bridge")
        driver.execute(null, "UPDATE creators SET aliases = 'First|Second' WHERE _id = 12", 0)
        bridge.importIncremental()
        val root = repository.resolveCreatorIdByExactName("First")!!
        repository.resolveCreatorIdByExactName("Second") shouldBe root
        repository.resolveCreatorIdByExactName("Bridge") shouldBe root
        repository.getCreator(first.id)!!.id shouldBe root
        repository.getCreator(second.id)!!.id shouldBe root
        handler.await {
            listOf(10L, 11L, 12L).forEach { legacy ->
                author_archiveQueries.getArchiveCreatorIdByLegacyId(legacy).executeAsOne() shouldBe root
            }
        }
        bridge.importIncremental().imported shouldBe 0
    }

    @Test
    fun `unchanged legacy fingerprint never recreates a relation removed by a user split`() {
        seedManga(1L)
        seedLegacyCreator(legacyId = 10L, name = "ONE")
        driver.execute(null, "INSERT INTO manga_creators VALUES (1, 10, 'author', 'ONE', 1.0, 'legacy')", 0)
        seedMigratedCreator(archiveId = 20L, legacyId = 10L, name = "ONE")
        driver.execute(
            null,
            "INSERT INTO author_archive_manga_links(manga_id, creator_id, role, creator_order, origin, source_text, " +
                "confidence, evidence, created_at, last_modified_at) VALUES (1, 20, 'AUTHOR', 0, 'MIGRATION', " +
                "'ONE', 1.0, 'legacy', 1, 1)",
            0,
        )

        val baseline = runBlocking { bridge.importIncremental() }
        baseline.imported shouldBe 0
        queryLong("SELECT COUNT(*) FROM author_archive_legacy_import_state") shouldBe 2L

        driver.execute(null, "DELETE FROM author_archive_manga_links WHERE manga_id = 1 AND creator_id = 20", 0)
        val unchanged = runBlocking { bridge.importIncremental() }

        unchanged.imported shouldBe 0
        queryLong("SELECT COUNT(*) FROM author_archive_manga_links") shouldBe 0L
    }

    @Test
    fun `changed rollback relation imports exactly once after baseline`() {
        seedManga(1L)
        seedLegacyCreator(legacyId = 10L, name = "ONE")
        driver.execute(null, "INSERT INTO manga_creators VALUES (1, 10, 'author', 'ONE', 1.0, 'legacy')", 0)
        seedMigratedCreator(archiveId = 20L, legacyId = 10L, name = "ONE")
        driver.execute(
            null,
            "INSERT INTO author_archive_manga_links(manga_id, creator_id, role, creator_order, origin, source_text, " +
                "confidence, evidence, created_at, last_modified_at) VALUES (1, 20, 'AUTHOR', 0, 'MIGRATION', " +
                "'ONE', 1.0, 'legacy', 1, 1)",
            0,
        )
        runBlocking { bridge.importIncremental() }
        driver.execute(
            null,
            "UPDATE manga_creators SET role = 'artist', evidence = 'rollback edit' WHERE manga_id = 1",
            0,
        )

        val changed = runBlocking { bridge.importIncremental() }
        val repeated = runBlocking { bridge.importIncremental() }

        changed.imported shouldBe 1
        repeated.imported shouldBe 0
        queryString("SELECT role FROM author_archive_manga_links WHERE manga_id = 1") shouldBe "ARTIST"
        queryString("SELECT evidence FROM author_archive_manga_links WHERE manga_id = 1") shouldBe "rollback edit"
    }

    @Test
    fun `legacy local id collision creates a separate portable identity`() {
        seedLegacyCreator(legacyId = 1L, name = "Legacy ONE")
        seedMigratedCreator(archiveId = 1L, legacyId = 99L, name = "Existing Other")

        val result = runBlocking { bridge.importIncremental() }

        result.imported shouldBe 1
        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 2L
        queryLong("SELECT _id FROM author_archive_creators WHERE legacy_creator_id = 1") shouldBe 2L
        queryString(
            "SELECT portable_key FROM author_archive_creators WHERE legacy_creator_id = 1",
        ) shouldBe "bridge-portable-2"
    }

    @Test
    fun `fingerprint failure rolls back imported rows and can be retried`() {
        seedLegacyCreator(legacyId = 7L, name = "Retry Creator")
        driver.execute(
            null,
            "CREATE TRIGGER fail_legacy_fingerprint BEFORE INSERT ON author_archive_legacy_import_state " +
                "BEGIN SELECT RAISE(FAIL, 'fingerprint failure'); END",
            0,
        )

        shouldThrow<Exception> { runBlocking { bridge.importIncremental() } }

        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 0L
        queryLong("SELECT COUNT(*) FROM author_archive_legacy_import_state") shouldBe 0L
        driver.execute(null, "DROP TRIGGER fail_legacy_fingerprint", 0)
        runBlocking { bridge.importIncremental() }.imported shouldBe 1
        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 1L
    }

    @Test
    fun `rollback additions for candidates watches canonical works and decisions import exactly once`() {
        seedManga(99L)
        seedLegacyCreator(legacyId = 10L, name = "ONE")
        runBlocking { bridge.importIncremental() }

        driver.execute(
            null,
            "INSERT INTO discovery_candidates VALUES " +
                "(30, 7, '/rollback/work', 'Rollback Work', 'rollback work', 'ONE', NULL, " +
                "'pt-BR', 0.9, 'SOURCE_LANGUAGE', NULL, 10, 20, 20, 'IGNORED')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO discovery_candidate_creators VALUES " +
                "(30, 10, 'author', 'ONE', 0.9, 'rollback relation')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO creator_watches VALUES (10, 1, '7|8', 'pt-BR|ja', 30, 30, NULL, 5)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO canonical_works VALUES (50, 'Rollback Work', 'rollback work', 10, 'ja', 10, 20)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES " +
                "(99, 50, 1.0, 'rollback confirmation', 'CONFIRMED', 1, 10, 20)",
            0,
        )

        val changed = runBlocking { bridge.importIncremental() }
        val repeated = runBlocking { bridge.importIncremental() }

        changed.imported shouldBe 5
        repeated.imported shouldBe 0
        queryLong("SELECT COUNT(*) FROM author_archive_source_works WHERE legacy_candidate_id = 30") shouldBe 1L
        queryString(
            "SELECT role || ':' || verification FROM author_archive_source_work_creators",
        ) shouldBe "AUTHOR:VERIFIED"
        queryString(
            "SELECT language_tag FROM author_archive_language_assertions " +
                "WHERE subject_type = 'SOURCE_WORK' AND subject_key = 'source:7:/rollback/work'",
        ) shouldBe "pt-br"
        queryLong("SELECT COUNT(*) FROM author_archive_watch_sources") shouldBe 2L
        queryLong("SELECT COUNT(*) FROM author_archive_watch_languages") shouldBe 2L
        queryLong("SELECT COUNT(*) FROM author_archive_canonical_works WHERE legacy_work_id = 50") shouldBe 1L
        queryString(
            "SELECT state || ':' || actor || ':' || explicit FROM author_archive_work_decisions",
        ) shouldBe "CONFIRMED:RESTORE:1"
        queryLong("SELECT COUNT(*) FROM author_archive_canonical_versions") shouldBe 1L

        driver.execute(
            null,
            "UPDATE author_archive_watch_sources SET baseline_state = 'BASELINED', baseline_generation = 3 " +
                "WHERE source_id = 7",
            0,
        )
        driver.execute(null, "UPDATE creator_watches SET source_ids = '7|8|9' WHERE creator_id = 10", 0)

        runBlocking { bridge.importIncremental() }.imported shouldBe 1
        queryString(
            "SELECT baseline_state || ':' || baseline_generation FROM author_archive_watch_sources " +
                "WHERE source_id = 7",
        ) shouldBe "BASELINED:3"
        queryString(
            "SELECT baseline_state || ':' || baseline_generation FROM author_archive_watch_sources " +
                "WHERE source_id = 9",
        ) shouldBe "NEEDS_BASELINE:0"
    }

    @Test
    fun `legacy metadata refresh cannot reset an archived manual review to pending`() {
        seedLegacyCandidate(id = 30L, url = "/metadata-only", title = "Before", state = "IGNORED")
        runBlocking { bridge.importIncremental() }
        driver.execute(
            null,
            "UPDATE discovery_candidates SET title = 'After', normalized_title = 'after', last_seen_at = 50 " +
                ", state = 'NEW' WHERE _id = 30",
            0,
        )

        runBlocking { bridge.importIncremental() }.imported shouldBe 1

        queryString(
            "SELECT title || ':' || legacy_review_snapshot FROM author_archive_source_works " +
                "WHERE legacy_candidate_id = 30",
        ) shouldBe "After:IGNORED"
    }

    @Test
    fun `first natural key attach baselines stale review before importing later review changes`() {
        driver.execute(
            null,
            "INSERT INTO author_archive_source_works(" +
                "source_id, stable_source_url, title, normalized_title, first_seen_at, last_seen_at, " +
                "legacy_review_snapshot) VALUES (7, '/natural-attach', 'Current', 'current', 10, 20, 'IGNORED')",
            0,
        )
        seedLegacyCandidate(id = 32L, url = "/natural-attach", title = "Stale", state = "NEW")

        runBlocking { bridge.importIncremental() }.imported shouldBe 1

        queryString(
            "SELECT legacy_candidate_id || ':' || legacy_review_snapshot FROM author_archive_source_works " +
                "WHERE source_id = 7 AND stable_source_url = '/natural-attach'",
        ) shouldBe "32:IGNORED"
        driver.execute(null, "UPDATE discovery_candidates SET state = 'ACCEPTED' WHERE _id = 32", 0)

        runBlocking { bridge.importIncremental() }.imported shouldBe 1
        queryString(
            "SELECT legacy_review_snapshot FROM author_archive_source_works WHERE legacy_candidate_id = 32",
        ) shouldBe "ACCEPTED"
    }

    @Test
    fun `rollback candidate with blank url receives a stable legacy natural key`() {
        seedLegacyCandidate(id = 31L, url = "   ", title = "Blank URL", state = "NEW")

        runBlocking { bridge.importIncremental() }.imported shouldBe 1

        queryString(
            "SELECT stable_source_url FROM author_archive_source_works WHERE legacy_candidate_id = 31",
        ) shouldBe "legacy-candidate:31"
        queryString(
            "SELECT subject_key FROM author_archive_language_assertions " +
                "WHERE idempotency_key LIKE 'legacy-candidate-language:31:%'",
        ) shouldBe "source:7:legacy-candidate:31"
    }

    @Test
    fun `rollback work decisions cannot desynchronize a newer user canonical binding`() {
        seedManga(99L)
        seedLegacyCreator(legacyId = 10L, name = "ONE")
        driver.execute(
            null,
            "INSERT INTO canonical_works VALUES (50, 'Rollback Work', 'rollback work', 10, 'ja', 10, 20)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO manga_work_matches VALUES " +
                "(99, 50, 1.0, 'baseline confirmation', 'CONFIRMED', 1, 10, 20)",
            0,
        )
        runBlocking { bridge.importIncremental() }
        val sourceWorkId = queryLong("SELECT _id FROM author_archive_source_works WHERE manga_id = 99")
        val workId = queryLong("SELECT _id FROM author_archive_canonical_works WHERE legacy_work_id = 50")

        driver.execute(
            null,
            "INSERT INTO author_archive_work_decisions(" +
                "source_work_id, work_id, state, actor, explicit, algorithm_version, score, evidence, decided_at, " +
                "idempotency_key) VALUES ($sourceWorkId, $workId, 'REJECTED', 'USER', 1, NULL, 1.0, " +
                "'user rejected', 40, 'user-rejected')",
            0,
        )
        driver.execute(null, "DELETE FROM author_archive_canonical_versions WHERE source_work_id = $sourceWorkId", 0)
        driver.execute(
            null,
            "UPDATE manga_work_matches SET match_reason = 'late restore confirmed', last_modified_at = 50 " +
                "WHERE manga_id = 99",
            0,
        )

        runBlocking { bridge.importIncremental() }
        queryLong("SELECT COUNT(*) FROM author_archive_canonical_versions") shouldBe 0L

        driver.execute(
            null,
            "INSERT INTO author_archive_work_decisions(" +
                "source_work_id, work_id, state, actor, explicit, algorithm_version, score, evidence, decided_at, " +
                "idempotency_key) VALUES ($sourceWorkId, $workId, 'CONFIRMED', 'USER', 1, NULL, 1.0, " +
                "'user confirmed', 60, 'user-confirmed')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_canonical_versions(" +
                "work_id, source_work_id, confirmation, evidence, confirmed_at) VALUES " +
                "($workId, $sourceWorkId, 'CONFIRMED', 'user confirmed', 60)",
            0,
        )
        driver.execute(
            null,
            "UPDATE manga_work_matches SET state = 'REJECTED', match_reason = 'late restore rejected', " +
                "last_modified_at = 70 WHERE manga_id = 99",
            0,
        )

        runBlocking { bridge.importIncremental() }
        queryLong("SELECT work_id FROM author_archive_canonical_versions") shouldBe workId
    }

    private fun seedLegacyCreator(legacyId: Long, name: String) {
        driver.execute(
            null,
            "INSERT INTO creators VALUES (?, ?, ?, ?, '', 1, 1)",
            4,
        ) {
            bindLong(0, legacyId)
            bindString(1, name)
            bindString(2, name.lowercase())
            bindString(3, name)
        }
    }

    private fun seedLegacyCandidate(id: Long, url: String, title: String, state: String) {
        driver.execute(
            null,
            "INSERT INTO discovery_candidates VALUES " +
                "(?, 7, ?, ?, ?, NULL, NULL, 'en', 1.0, 'SOURCE_LANGUAGE', NULL, 1, 2, 2, ?)",
            5,
        ) {
            bindLong(0, id)
            bindString(1, url)
            bindString(2, title)
            bindString(3, title.lowercase())
            bindString(4, state)
        }
    }

    private fun seedMigratedCreator(archiveId: Long, legacyId: Long, name: String) {
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(" +
                "_id, portable_key, display_name, normalized_name, sort_name, status, needs_review, " +
                "legacy_creator_id, created_at, last_modified_at) " +
                "VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, ?, 1, 1)",
            6,
        ) {
            bindLong(0, archiveId)
            bindString(1, "existing-$archiveId")
            bindString(2, name)
            bindString(3, name.lowercase())
            bindString(4, name)
            bindLong(5, legacyId)
        }
        driver.execute(
            null,
            "INSERT INTO author_archive_aliases(" +
                "creator_id, raw_alias, normalized_alias, source, evidence, confidence, " +
                "is_manual, created_at, last_modified_at) " +
                "VALUES (?, ?, ?, 'PRIMARY', 'migration', 1, 0, 1, 1)",
            3,
        ) {
            bindLong(0, archiveId)
            bindString(1, name)
            bindString(2, name.lowercase())
        }
    }

    private fun seedManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
                "cover_last_modified, date_added) VALUES (?, 1, ?, ?, 0, 1, 0, 0, 0, 0, 0)",
            3,
        ) {
            bindLong(0, id)
            bindString(1, "/manga/$id")
            bindString(2, "Manga $id")
        }
    }

    private fun queryLong(sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryString(sql: String): String? = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        0,
    ).value
}
