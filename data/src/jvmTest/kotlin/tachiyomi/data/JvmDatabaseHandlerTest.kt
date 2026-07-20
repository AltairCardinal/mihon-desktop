package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Basic structural test for JvmDatabaseHandler.
 * Integration tests with a real database require SQLDelight code generation.
 */
class JvmDatabaseHandlerTest {

    @Test
    fun `JvmDatabaseHandler class exists and implements DatabaseHandler`() {
        // Verify the class is loadable and implements the correct interface
        val clazz = JvmDatabaseHandler::class
        assertNotNull(clazz)
        assertTrue(DatabaseHandler::class.java.isAssignableFrom(JvmDatabaseHandler::class.java))
    }

    @Test
    fun `outer transactions are serialized while nested transactions reuse the active transaction`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        val activeTransactions = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        try {
            val first = async {
                handler.await(inTransaction = true) {
                    val active = activeTransactions.incrementAndGet()
                    try {
                        check(active == 1) { "$active outer transactions overlapped" }
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        handler.await(inTransaction = true) { "nested" }
                    } finally {
                        activeTransactions.decrementAndGet()
                    }
                }
            }
            withTimeout(5_000) { firstEntered.await() }
            val second = async {
                handler.await(inTransaction = true) {
                    val active = activeTransactions.incrementAndGet()
                    try {
                        secondEntered.complete(Unit)
                        check(active == 1) { "$active outer transactions overlapped" }
                        "second"
                    } finally {
                        activeTransactions.decrementAndGet()
                    }
                }
            }

            val prematureEntry = withTimeoutOrNull(500) { secondEntered.await() }
            releaseFirst.complete(Unit)
            val firstResult = withTimeout(5_000) { first.await() }
            val secondResult = runCatching { withTimeout(5_000) { second.await() } }

            assertNull(prematureEntry, "second outer transaction must wait before entering")
            assertTrue(secondResult.isSuccess, "second transaction failed: ${secondResult.exceptionOrNull()}")
            assertTrue(firstResult == "nested" && secondResult.getOrNull() == "second")
        } finally {
            releaseFirst.complete(Unit)
            handler.close()
        }
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.jupiter.api.Assertions.assertTrue(condition)
    }
}
