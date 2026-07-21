package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
    fun `transaction body SQL stays on the JDBC transaction thread`() = runBlocking {
        AlternatingDispatcher().use { dispatcher ->
            Fixture(dispatcher).use { fixture ->
                fixture.handler.await(inTransaction = true) {
                    yield()
                    assertNotNull(
                        fixture.driver.currentTransaction(),
                        "transaction body escaped the JDBC transaction thread",
                    )
                    fixture.insert(1)
                }

                assertEquals(1L, fixture.count())
            }
        }
    }

    @Test
    fun `caught nested transaction failure rolls back the outer write`() = runBlocking {
        Fixture().use { fixture ->
            fixture.handler.await(inTransaction = true) {
                fixture.insert(1)
                runCatching {
                    fixture.handler.await(inTransaction = true) {
                        assertNotNull(fixture.driver.currentTransaction())
                        fixture.insert(2)
                        error("nested failure")
                    }
                }
            }

            assertEquals(0L, fixture.count(), "failed child transaction must make the outer transaction roll back")
        }
    }

    @Test
    fun `caller cancellation rolls back and releases the next transaction`() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val cancelled = async {
                fixture.handler.await(inTransaction = true) {
                    fixture.insert(1)
                    started.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        finished.complete(Unit)
                    }
                }
            }
            withTimeout(5_000) { started.await() }

            cancelled.cancel()
            val cancelledPromptly = withTimeoutOrNull(1_000) { finished.await() }
            release.complete(Unit)
            withTimeout(5_000) { cancelled.join() }

            assertNotNull(cancelledPromptly, "caller cancellation must cancel the transaction body")
            assertEquals(0L, fixture.count(), "cancelled transaction must roll back")
            fixture.handler.await(inTransaction = true) { fixture.insert(2) }
            assertEquals(1L, fixture.count(), "mutex and transaction thread must be released after cancellation")
        }
    }

    @Test
    fun `long lived parent owns and releases every transaction control job`() = runBlocking {
        Fixture().use { fixture ->
            val parentJob = requireNotNull(coroutineContext[Job])

            repeat(3) { round ->
                fixture.handler.await(inTransaction = true) {
                    val activeChildren = parentJob.children.count()
                    assertEquals(
                        2,
                        activeChildren,
                        "round $round must expose the transaction body and its structured control job",
                    )
                    fixture.insert(round)
                }

                assertTrue(
                    parentJob.children.none(),
                    "round $round must detach its completed transaction lifecycle from the long-lived parent",
                )
            }

            assertEquals(3L, fixture.count())
        }
    }

    @Test
    fun `non transactional work is not serialized behind an outer transaction`() = runBlocking {
        Fixture().use { fixture ->
            val transactionEntered = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val transaction = async {
                fixture.handler.await(inTransaction = true) {
                    transactionEntered.complete(Unit)
                    releaseTransaction.await()
                }
            }
            withTimeout(5_000) { transactionEntered.await() }
            val nonTransactionEntered = CompletableDeferred<Unit>()
            val nonTransaction = async {
                fixture.handler.await(inTransaction = false) {
                    nonTransactionEntered.complete(Unit)
                }
            }

            val enteredWhileTransactionSuspended = withTimeoutOrNull(1_000) { nonTransactionEntered.await() }
            releaseTransaction.complete(Unit)
            withTimeout(5_000) {
                transaction.await()
                nonTransaction.await()
            }

            assertNotNull(
                enteredWhileTransactionSuspended,
                "non-transactional work must not wait on the transaction mutex",
            )
        }
    }

    @Test
    fun `cross handler nesting returns to each active transaction thread`() = runBlocking {
        Fixture().use { first ->
            Fixture().use { second ->
                withTimeout(5_000) {
                    first.handler.await(inTransaction = true) {
                        assertNotNull(first.driver.currentTransaction())
                        second.handler.await(inTransaction = true) {
                            assertNotNull(second.driver.currentTransaction())
                            first.handler.await(inTransaction = true) {
                                assertNotNull(first.driver.currentTransaction())
                                first.insert(1)
                            }
                            second.insert(2)
                        }
                    }
                }

                assertEquals(1L, first.count())
                assertEquals(1L, second.count())
            }
        }
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

    private class Fixture(
        queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            Database.Schema.create(it)
            it.execute(null, "CREATE TABLE transaction_probe (value INTEGER NOT NULL)", 0).value
        }
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
            queryDispatcher,
        )

        fun insert(value: Int) {
            driver.execute(null, "INSERT INTO transaction_probe(value) VALUES ($value)", 0).value
        }

        fun count(): Long = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM transaction_probe",
            { cursor -> QueryResult.Value(if (cursor.next().value) requireNotNull(cursor.getLong(0)) else 0L) },
            0,
        ).value

        override fun close() = handler.close()
    }

    private class AlternatingDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val executors: List<ExecutorService> = listOf(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "jdbc-alternating-a").apply {
                    isDaemon =
                        true
                }
            },
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "jdbc-alternating-b").apply {
                    isDaemon =
                        true
                }
            },
        )
        private val next = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executors[Math.floorMod(next.getAndIncrement(), executors.size)].execute(block)
        }

        override fun close() {
            executors.forEach(ExecutorService::shutdownNow)
        }
    }
}
