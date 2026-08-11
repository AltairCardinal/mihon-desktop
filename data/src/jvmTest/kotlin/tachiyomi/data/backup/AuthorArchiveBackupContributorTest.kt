package tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

class AuthorArchiveBackupContributorTest {

    @Test
    fun `round trip preserves same-name identities merge redirect and natural-key bindings`() {
        runBlocking {
            val source = fixture()
            source.seedManga(1L, 10L, "/one")
            source.seedManga(2L, 10L, "/two")
            source.seedArchive()

            val section = source.contributor.createSection()

            val target = fixture()
            target.seedManga(101L, 10L, "/one")
            target.seedManga(102L, 10L, "/two")
            target.contributor.restoreSection(checkNotNull(section))
            target.contributor.restoreSection(checkNotNull(section))

            target.long("SELECT COUNT(*) FROM author_archive_creators") shouldBe 3L
            target.long("SELECT COUNT(*) FROM author_archive_creators WHERE normalized_name = 'same'") shouldBe 3L
            target.long("SELECT COUNT(*) FROM author_archive_aliases") shouldBe 3L
            target.long("SELECT COUNT(*) FROM author_archive_source_works") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_manga_links") shouldBe 2L
            target.long(
                "SELECT COUNT(*) FROM author_archive_watches WHERE enabled = 1 AND period_millis = 60000",
            ) shouldBe
                1L
            target.long("SELECT COUNT(*) FROM author_archive_watch_sources") shouldBe 2L
            target.long(
                "SELECT COUNT(*) FROM author_archive_watch_sources WHERE baseline_state = 'NEEDS_BASELINE'",
            ) shouldBe
                2L
            target.long("SELECT COUNT(*) FROM author_archive_watch_languages WHERE language_tag = 'ja'") shouldBe 1L
            target.long(
                "SELECT COUNT(*) FROM author_archive_discoveries " +
                    "WHERE read_state = 'SEEN' AND review_disposition = 'IGNORED'",
            ) shouldBe 1L
            target.long("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
            target.long(
                "SELECT COUNT(*) FROM author_archive_watch_result_policies " +
                    "WHERE include_probable = 1 AND notify_probable = 1",
            ) shouldBe 1L
            target.string(
                "SELECT TARGET.portable_key FROM author_archive_creators SOURCE " +
                    "JOIN author_archive_creators TARGET ON TARGET._id = SOURCE.merged_into_creator_id " +
                    "WHERE SOURCE.portable_key = 'creator-old'",
            ) shouldBe "creator-a"
            target.long(
                "SELECT manga_id FROM author_archive_manga_links ML " +
                    "JOIN author_archive_creators C ON C._id = ML.creator_id WHERE C.portable_key = 'creator-a'",
            ) shouldBe 101L
            target.long(
                "SELECT manga_id FROM author_archive_manga_links ML " +
                    "JOIN author_archive_creators C ON C._id = ML.creator_id WHERE C.portable_key = 'creator-b'",
            ) shouldBe 102L
        }
    }

    @Test
    fun `invalid redirect cycle is rejected before transaction writes`() {
        runBlocking {
            val target = fixture()
            val invalid = BackupAuthorArchiveSection(
                creators = listOf(
                    BackupCreatorIdentity(
                        portableKey = "a",
                        displayName = "A",
                        normalizedName = "a",
                        status = "MERGED",
                        mergedIntoPortableKey = "b",
                    ),
                    BackupCreatorIdentity(
                        portableKey = "b",
                        displayName = "B",
                        normalizedName = "b",
                        status = "MERGED",
                        mergedIntoPortableKey = "a",
                    ),
                ),
            )

            shouldThrow<IllegalArgumentException> { target.contributor.restoreSection(invalid) }

            target.long("SELECT COUNT(*) FROM author_archive_creators") shouldBe 0L
        }
    }

    @Test
    fun `portable backup reattaches trimmed and blank urls across different local manga ids`() {
        runBlocking {
            val source = fixture()
            source.seedManga(10L, 42L, " /trimmed ", title = "Trimmed", author = "Portable Author")
            source.seedManga(20L, 42L, "", title = "Blank URL", author = "Portable Author")
            source.seedPortableUrlArchive()

            val section = checkNotNull(source.contributor.createSection())

            section.sourceWorks.map { it.stableSourceUrl }.toSet().let { urls ->
                ("/trimmed" in urls) shouldBe true
                urls.single { it != "/trimmed" }.startsWith("blank-manga:") shouldBe true
            }
            val target = fixture()
            target.seedManga(110L, 42L, "  /trimmed  ", title = "Trimmed", author = "Portable Author")
            target.seedManga(220L, 42L, "", title = "Blank URL", author = "Portable Author")
            target.contributor.restoreSection(section)

            target.long("SELECT COUNT(*) FROM author_archive_manga_links") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 110") shouldBe 1L
            target.long("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 220") shouldBe 1L
        }
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        return Fixture(
            driver,
            SqlDelightAuthorArchiveBackupContributor(JvmDatabaseHandler(database, driver), clock = { 500L }),
        )
    }

    private data class Fixture(
        val driver: JdbcSqliteDriver,
        val contributor: AuthorArchiveBackupContributor,
    ) {
        fun seedManga(
            id: Long,
            source: Long,
            url: String,
            title: String = "Manga $id",
            author: String? = null,
        ) {
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, author, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added, last_modified_at, version) " +
                    "VALUES (?, ?, ?, ?, ?, 0, 1, 0, 0, 0, 0, 0, 1, 1)",
                5,
            ) {
                bindLong(0, id)
                bindLong(1, source)
                bindString(2, url)
                bindString(3, title)
                bindString(4, author)
            }
        }

        fun seedArchive() {
            driver.execute(
                null,
                "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, sort_name, " +
                    "status, needs_review, created_at, last_modified_at) VALUES " +
                    "(1, 'creator-a', 'Same A', 'same', 'Same A', 'ACTIVE', 0, 1, 1), " +
                    "(2, 'creator-b', 'Same B', 'same', 'Same B', 'ACTIVE', 0, 1, 1), " +
                    "(3, 'creator-old', 'Same Old', 'same', 'Same Old', 'ACTIVE', 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "UPDATE author_archive_creators SET status = 'MERGED', merged_into_creator_id = 1 WHERE _id = 3",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                    "confidence, is_manual, created_at, last_modified_at) VALUES " +
                    "(1, 'Same', 'same', 'USER', 'manual a', 1, 1, 1, 1), " +
                    "(2, 'Same', 'same', 'USER', 'manual b', 1, 1, 1, 1), " +
                    "(3, 'Old Same', 'old same', 'USER', 'merge alias', 1, 1, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(_id, source_id, stable_source_url, manga_id, title, " +
                    "normalized_title, first_seen_at, last_seen_at) VALUES " +
                    "(1, 10, '/one', 1, 'One', 'one', 1, 1), " +
                    "(2, 10, '/two', 2, 'Two', 'two', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_work_creators(source_work_id, creator_id, role, creator_order, " +
                    "origin, verification, confidence, evidence, created_at, last_modified_at) VALUES " +
                    "(1, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'split a', 1, 1), " +
                    "(2, 2, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'split b', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watches(" +
                    "_id, creator_id, enabled, period_millis, created_at, last_modified_at" +
                    ") " +
                    "VALUES (1, 1, 1, 60000, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watch_sources(watch_id, source_id, baseline_state, baseline_generation, " +
                    "next_due_at, created_at, last_modified_at) VALUES " +
                    "(1, 10, 'BASELINED', 4, 100, 1, 1), (1, 20, 'BASELINED', 4, 100, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watch_result_policies(_id, watch_id, include_probable, include_unknown, " +
                    "notify_probable, notify_unknown, created_at, last_modified_at) " +
                    "VALUES (1, 1, 1, 0, 1, 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watch_languages(policy_id, language_tag) VALUES (1, 'ja')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_discoveries(" +
                    "_id, watch_id, source_work_id, kind, reason, baseline_generation, read_state, " +
                    "review_disposition, first_discovered_at, last_modified_at" +
                    ") VALUES (1, 1, 1, 'NEW_WORK_CANDIDATE', 'verified', 4, 'SEEN', 'IGNORED', 10, 11)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_notification_outbox(" +
                    "discovery_id, channel, idempotency_key, attempt_count, state, created_at" +
                    ") VALUES (1, 'DESKTOP', 'do-not-restore', 0, 'PENDING', 10)",
                0,
            )
        }

        fun seedPortableUrlArchive() {
            driver.execute(
                null,
                "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, sort_name, " +
                    "status, needs_review, created_at, last_modified_at) VALUES " +
                    "(1, 'portable-author', 'Portable Author', 'portable author', 'Portable Author', " +
                    "'ACTIVE', 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                    "confidence, is_manual, created_at, last_modified_at) VALUES " +
                    "(1, 'Portable Author', 'portable author', 'USER', 'portable alias', 1, 1, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(_id, source_id, stable_source_url, manga_id, title, " +
                    "normalized_title, author_text, first_seen_at, last_seen_at) VALUES " +
                    "(1, 42, '/trimmed', 10, 'Trimmed', 'trimmed', 'Portable Author', 1, 1), " +
                    "(2, 42, 'legacy-manga:20', 20, 'Blank URL', 'blank url', 'Portable Author', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_work_creators(source_work_id, creator_id, role, creator_order, " +
                    "origin, verification, confidence, evidence, created_at, last_modified_at) VALUES " +
                    "(1, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'trimmed binding', 1, 1), " +
                    "(2, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'blank binding', 1, 1)",
                0,
            )
        }

        fun long(sql: String): Long = driver.executeQuery(
            null,
            sql,
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
            },
            0,
        ).value

        fun string(sql: String): String = driver.executeQuery(
            null,
            sql,
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0)!! else "")
            },
            0,
        ).value
    }
}
