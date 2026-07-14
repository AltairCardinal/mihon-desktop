package mihon.feature.migration.list

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.BatchMigrationEvent
import mihon.domain.migration.BatchMigrationWaitingForUserException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AndroidBatchMigrationRunnerTest {
    @Test
    fun `runner consumes shared batch events and preserves per-item failure checkpoint`() = runTest {
        val events = mutableListOf<BatchMigrationEvent<Int>>()

        AndroidBatchMigrationRunner<Int>().run(listOf(1, 2, 3)) { item ->
            if (item == 2) error("offline")
            item * 10
        }.collect(events::add)

        assertEquals(
            listOf(
                BatchMigrationEvent.Succeeded(0, 1, 10),
                BatchMigrationEvent.Failed(1, 2, "offline"),
                BatchMigrationEvent.Succeeded(2, 3, 30),
                BatchMigrationEvent.Completed(3),
            ),
            events,
        )
    }

    @Test
    fun `runner exposes waiting checkpoint and propagates cancellation`() = runTest {
        val waiting = mutableListOf<BatchMigrationEvent<Int>>()
        AndroidBatchMigrationRunner<Int>().run(listOf(1, 2)) {
            if (it == 2) throw BatchMigrationWaitingForUserException()
            it
        }.collect(waiting::add)

        assertEquals(BatchMigrationEvent.WaitingForUser(1, 2), waiting[1])
        assertEquals(BatchMigrationEvent.Completed(1), waiting[2])
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                AndroidBatchMigrationRunner<Int>().run(listOf(1)) {
                    throw CancellationException("cancelled")
                }.collect {}
            }
        }
    }
}
