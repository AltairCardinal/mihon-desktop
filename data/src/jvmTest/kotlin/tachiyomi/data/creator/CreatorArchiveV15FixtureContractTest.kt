package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorArchiveV2Contract

class CreatorArchiveV15FixtureContractTest {

    @Test
    fun `frozen creator v15 fixture records the legacy constraints that migration 15 must replace`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        loadFixture(driver)

        queryLong(driver, "PRAGMA user_version") shouldBe CreatorArchiveV2Contract.CURRENT_SCHEMA_VERSION
        queryStrings(
            driver,
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        ).shouldContainExactlyInAnyOrder(
            "creators",
            "manga_creators",
            "discovery_candidate_creators",
            "creator_watches",
            "canonical_works",
            "manga_work_matches",
            "discovery_candidates",
        )

        driver.execute(null, "INSERT INTO creators VALUES (1, 'ONE', 'one', 'ONE', '', 1, 1)", 0)
        shouldThrow<Exception> {
            driver.execute(null, "INSERT INTO creators VALUES (2, 'One', 'one', 'One', '', 1, 1)", 0)
        }

        driver.execute(null, "INSERT INTO manga_creators VALUES (9, 1, 'unknown', 'ONE', 0.5, 'legacy')", 0)
        driver.execute(null, "INSERT INTO manga_creators VALUES (9, 1, 'author', 'ONE', 1.0, 'details')", 0)
        queryLong(driver, "SELECT COUNT(*) FROM manga_creators WHERE manga_id = 9 AND creator_id = 1") shouldBe 2L
        driver.close()
    }

    @Test
    fun `current generated schema and target migration version match the frozen contract`() {
        DatabaseVersion.current() shouldBe CreatorArchiveV2Contract.CURRENT_SCHEMA_VERSION
        CreatorArchiveV2Contract.TARGET_SCHEMA_VERSION shouldBe CreatorArchiveV2Contract.CURRENT_SCHEMA_VERSION + 1
        CreatorArchiveV2Contract.TARGET_MIGRATION shouldBe "${CreatorArchiveV2Contract.CURRENT_SCHEMA_VERSION}.sqm"
    }

    private fun loadFixture(driver: JdbcSqliteDriver) {
        val sql = requireNotNull(javaClass.getResource("/creator/creator-schema-v15.sql")).readText()
        sql.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { statement -> driver.execute(null, statement, 0) }
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryStrings(driver: JdbcSqliteDriver, sql: String): List<String> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val values = mutableListOf<String>()
            while (cursor.next().value) values += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(values)
        },
        0,
    ).value

    private object DatabaseVersion {
        fun current(): Long = tachiyomi.data.Database.Schema.version
    }
}
