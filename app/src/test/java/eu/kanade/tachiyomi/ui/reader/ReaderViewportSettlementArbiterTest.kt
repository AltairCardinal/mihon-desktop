package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderViewportSettlementArbiterTest {

    @Test
    fun `issuing a new token invalidates every older unaccepted viewport`() {
        val arbiter = ReaderViewportSettlementArbiter()
        val older = arbiter.nextToken()
        val latest = arbiter.nextToken()

        assertFalse(arbiter.isLatest(older))
        assertTrue(arbiter.isLatest(latest))
    }
}
