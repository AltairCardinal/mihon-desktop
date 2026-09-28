package mihon.data.reader

/** The same accepted-settlement assertions run against Android and Desktop production adapters. */
object AcceptedReaderProgressContract {
    data class Observation(
        val page: Int,
        val totalPages: Int,
        val wasRead: Boolean,
        val isRead: Boolean,
        val idempotencyKey: String,
    )

    interface Adapter {
        suspend fun settle(page: Int)
        suspend fun closeAndDrain()
        fun committed(): List<Observation>
    }

    /** The write has been accepted by a production reader but its storage port is still blocked. */
    interface PendingCloseAdapter {
        suspend fun accept()
        suspend fun awaitWriterStarted()
        suspend fun close()
        suspend fun releaseWrite()
        suspend fun drain()
        fun committedCount(): Int
    }

    suspend fun verifyCompletionBackTurnAndClose(adapter: Adapter) {
        adapter.settle(4)
        adapter.settle(2)
        adapter.closeAndDrain()
        val committed = adapter.committed()
        check(committed.size == 2) { "Expected two committed settlements, got $committed" }
        val (completion, backTurn) = committed
        check(completion.page == 4 && completion.totalPages == 5 && completion.isRead) {
            "The visible final page must complete the chapter: $completion"
        }
        check(backTurn.page == 2 && backTurn.wasRead && backTurn.isRead) {
            "A real back turn must save its lower position without reverting read: $backTurn"
        }
        check(completion.idempotencyKey != backTurn.idempotencyKey) {
            "Distinct settlements need distinct idempotency keys"
        }
    }

    suspend fun verifyPendingWriteSurvivesClose(adapter: PendingCloseAdapter) {
        adapter.accept()
        adapter.awaitWriterStarted()
        check(adapter.committedCount() == 0) { "A blocked write must not be reported as committed" }
        adapter.close()
        check(adapter.committedCount() == 0) { "Closing must not pretend that a blocked write committed" }
        adapter.releaseWrite()
        adapter.drain()
        check(adapter.committedCount() == 1) { "The accepted write must commit exactly once after close" }
    }
}
