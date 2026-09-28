package eu.kanade.tachiyomi.ui.reader

import java.util.concurrent.atomic.AtomicLong

internal class ReaderViewportSettlementArbiter {
    private val sequence = AtomicLong()

    fun nextToken(): Long = sequence.incrementAndGet()

    fun isLatest(token: Long): Boolean = sequence.get() == token
}
