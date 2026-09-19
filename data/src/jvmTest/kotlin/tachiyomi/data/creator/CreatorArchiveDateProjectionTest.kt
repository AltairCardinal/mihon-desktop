package tachiyomi.data.creator

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database

class CreatorArchiveDateProjectionTest {
    @Test
    fun `source work stores frozen first seen date and conservative catalog state`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            driver.execute(
                null,
                """
                INSERT INTO author_archive_source_works(
                    source_id, stable_source_url, title, normalized_title, first_seen_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                6,
            ) {
                bindLong(0, 31)
                bindString(1, "/date-contract")
                bindString(2, "Date contract")
                bindString(3, "date contract")
                bindLong(4, 1_736_294_400_000)
                bindLong(5, 1_736_294_400_000)
            }

            val values = driver.executeQuery(
                null,
                """
                SELECT first_seen_date, first_seen_zone, chapter_count_state,
                    catalog_chapter_count, latest_chapter_at
                FROM author_archive_source_works
                WHERE source_id = ? AND stable_source_url = ?
                """.trimIndent(),
                { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(
                        listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getLong(3),
                            cursor.getLong(4),
                        ),
                    )
                },
                2,
            ) {
                bindLong(0, 31)
                bindString(1, "/date-contract")
            }.value
            assertEquals(listOf("2025-01-08", "UTC", "UNKNOWN", 0L, null), values)
        } finally {
            driver.close()
        }
    }
}
