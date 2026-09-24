package tachiyomi.data.creator

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class CreatorUnreadProjectionPerformanceTest {

    @Test
    fun `unread subscription projects many authors with bounded query count`() = runBlocking {
        val driver = UnreadCountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
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
        val repository = CreatorRepositoryImpl(JvmDatabaseHandler(database, driver))
        repeat(24) { index ->
            val creator = repository.upsertCreator("Unread Author $index")
            val sourceId = 100L + index
            val key = SourceWorkNaturalKey(sourceId, "/work")
            repository.upsertWatchPolicy(
                ArchiveWatchPolicy(creator.id, true, 1_000L, setOf(sourceId), emptySet()),
                now = 1L,
            )
            repository.upsertSourceWork(sourceId, key.stableSourceUrl, null, "Work $index", null, null, null, 2L)
            repository.upsertSourceWorkCreator(
                key, creator.id, CreatorRole.AUTHOR, 0L, CreatorRelationOrigin.USER,
                CreatorRelationVerification.VERIFIED, "fixture", 1.0, "fixture",
            )
            repository.commitDiscovery(
                DiscoveryCommit(
                    creator.id,
                    key,
                    DiscoveryKind.NEW_WORK_CANDIDATE,
                    "fixture",
                    1L,
                    100L + index,
                    "TEST",
                    "unread-performance-$index",
                ),
            )
        }

        driver.reset()
        val unread = repository.observeUnreadWorkDiscoveries(1_000L).first()

        assertEquals(24, unread.size)
        assertEquals(2, driver.selectCount, "projection should read candidates and grouped inputs once each")
        assertEquals(1, driver.unreadCandidateQueryCount)
    }
}

private class UnreadCountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var selectCount = 0
        private set
    var unreadCandidateQueryCount = 0
        private set

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        selectCount++
        if (sql.contains("ORDER BY D.first_discovered_at, D._id")) unreadCandidateQueryCount++
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    fun reset() {
        selectCount = 0
        unreadCandidateQueryCount = 0
    }
}
