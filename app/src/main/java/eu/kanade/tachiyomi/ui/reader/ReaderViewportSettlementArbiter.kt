package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal class ReaderViewportSettlementArbiter {
    private val sequence = AtomicLong()
    private val transactionMutex = Mutex()

    fun nextToken(): Long = sequence.incrementAndGet()

    fun isLatest(token: Long): Boolean = sequence.get() == token

    suspend fun runIfLatest(token: Long, block: suspend () -> Unit): Boolean =
        transactionMutex.withLock {
            if (!isLatest(token)) return@withLock false
            block()
            true
        }
}
