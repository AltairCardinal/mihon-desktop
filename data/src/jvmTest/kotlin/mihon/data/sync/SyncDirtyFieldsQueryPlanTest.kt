package mihon.data.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.concurrent.TimeUnit

/** Diagnostic for the real generated query used by the production projector. */
class SyncDirtyFieldsQueryPlanTest {
    @Test
    fun `ten thousand dirty fields report actual query plan and page cost`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        try {
            database.transaction {
                repeat(10_000) { index ->
                    driver.execute(
                        null,
                        "INSERT INTO sync_field_state(space_id,generation,object_key,field,object_json) " +
                            "VALUES ('space',1,'key-${index.toString().padStart(5, '0')}','FAVORITE','{}')",
                        0,
                    )
                }
            }
            val plan = explain(
                driver,
                "SELECT * FROM sync_field_state INDEXED BY sync_dirty_fields " +
                    "WHERE space_id='space' AND generation=1 AND dirty=1 ORDER BY object_key, field LIMIT 50",
            )
            var queryNanos = 0L
            var updateNanos = 0L
            var rows = 0
            var pages = 0
            while (true) {
                val queryStart = System.nanoTime()
                val fields = database.sync_inboxQueries.getDirtyFields("space", 1, 50).executeAsList()
                queryNanos += System.nanoTime() - queryStart
                if (fields.isEmpty()) break
                rows += fields.size
                pages++
                val updateStart = System.nanoTime()
                val keys = fields.joinToString(",") { "'${it.object_key}'" }
                driver.execute(null, "UPDATE sync_field_state SET dirty=0 WHERE object_key IN ($keys)", 0)
                updateNanos += System.nanoTime() - updateStart
            }
            assertEquals(10_000, rows)
            println(
                "SYNC_SQL_DIAGNOSTIC rows=$rows pages=$pages " +
                    "queryMs=${TimeUnit.NANOSECONDS.toMillis(queryNanos)} " +
                    "updateMs=${TimeUnit.NANOSECONDS.toMillis(updateNanos)} plan=$plan",
            )
        } finally {
            driver.close()
        }
    }

    private fun explain(driver: SqlDriver, query: String): List<String> = driver.executeQuery(
        identifier = null,
        sql = "EXPLAIN QUERY PLAN $query",
        parameters = 0,
        mapper = { cursor ->
            val details = mutableListOf<String>()
            while (cursor.next().value) details += requireNotNull(cursor.getString(3))
            QueryResult.Value(details)
        },
    ).value
}
