package mihon.domain.easteregg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PigEasterEggTest {

    @Test
    fun `trigger detects pig emoji in search query`() {
        assertTrue(PigEasterEggTrigger.isTrigger("🐖"))
        assertTrue(PigEasterEggTrigger.isTrigger("海贼王 🐖"))
        assertTrue(PigEasterEggTrigger.isTrigger("test🐖test"))
        assertTrue(PigEasterEggTrigger.isTrigger("🐷"))
        assertTrue(PigEasterEggTrigger.isTrigger("火影 🐷"))
        assertTrue(PigEasterEggTrigger.isTrigger("test🐷test"))
        assertFalse(PigEasterEggTrigger.isTrigger("海贼王"))
        assertFalse(PigEasterEggTrigger.isTrigger(""))
        assertFalse(PigEasterEggTrigger.isTrigger(null))
    }

    @Test
    fun `trigger checks query and fires controller`() {
        val controller = PigEasterEggController()
        assertEquals(0, controller.triggerCount.value)

        val triggered = PigEasterEggTrigger.check("海贼王 🐖", controller)
        assertTrue(triggered)
        assertEquals(1, controller.triggerCount.value)

        val notTriggered = PigEasterEggTrigger.check("普通漫画", controller)
        assertFalse(notTriggered)
        assertEquals(1, controller.triggerCount.value)
    }

    @Test
    fun `session starts with 7 pigs and 3000ms lifetime`() {
        val startTime = 10_000L
        val session = PigEasterEggSession(startTime = startTime)

        assertEquals(7, session.totalPigCount)
        assertEquals(7, session.activePigCount)
        assertFalse(session.isAllPigsClicked)
        assertEquals(3_000L, session.remainingMillis(startTime))
        assertFalse(session.isEndingSoon(startTime))
        assertFalse(session.isExpired(startTime))
    }

    @Test
    fun `clicking a pig dismisses it and extends duration by 250ms`() {
        val startTime = 10_000L
        val session = PigEasterEggSession(startTime = startTime)

        val clicked = session.clickPig(0, clickTime = 10_500L)
        assertTrue(clicked)
        assertEquals(6, session.activePigCount)
        assertTrue(session.isPigClicked(0))
        assertFalse(session.isPigClicked(1))

        // Initial 3000ms + 250ms = 3250ms total. At clickTime 10_500L (500ms elapsed), remaining is 2750ms.
        assertEquals(2_750L, session.remainingMillis(10_500L))

        // Clicking the same pig again does nothing
        val clickedAgain = session.clickPig(0, clickTime = 10_600L)
        assertFalse(clickedAgain)
        assertEquals(6, session.activePigCount)
    }

    @Test
    fun `ending soon triggers in the last 500ms and resets if extended by clicking`() {
        val startTime = 10_000L
        val session = PigEasterEggSession(startTime = startTime)

        // At 12_400L, 600ms remaining -> not ending soon
        assertFalse(session.isEndingSoon(12_400L))

        // At 12_600L, 400ms remaining -> ending soon (< 500ms)
        assertTrue(session.isEndingSoon(12_600L))

        // Click a pig at 12_600L -> adds 250ms -> remaining becomes 650ms -> no longer ending soon!
        session.clickPig(1, clickTime = 12_600L)
        assertEquals(650L, session.remainingMillis(12_600L))
        assertFalse(session.isEndingSoon(12_600L))

        // Later at 13_000L, remaining is 250ms -> ending soon again
        assertTrue(session.isEndingSoon(13_000L))
    }

    @Test
    fun `when time expires without clicking all pigs session finishes without giant pig`() {
        val startTime = 10_000L
        val session = PigEasterEggSession(startTime = startTime)

        session.clickPig(0, 10_100L)
        // End time is 10_000 + 3_000 + 250 = 13_250L
        val expireTime = 13_251L
        assertTrue(session.isExpired(expireTime))
        assertFalse(session.isGiantPigActive(expireTime))
        assertTrue(session.isFinished(expireTime))
    }

    @Test
    fun `clicking all 7 pigs triggers giant pig for 1000ms then finishes`() {
        val startTime = 10_000L
        val session = PigEasterEggSession(startTime = startTime)

        for (id in 0 until 7) {
            session.clickPig(id, clickTime = 11_000L)
        }

        assertTrue(session.isAllPigsClicked)
        val giantStartTime = 11_000L
        // Giant pig should be active right after all clicked
        assertTrue(session.isGiantPigActive(giantStartTime))
        assertEquals(0, session.activePigCount)

        // At 11_500L, 500ms into giant pig -> still active
        assertTrue(session.isGiantPigActive(11_500L))
        assertFalse(session.isFinished(11_500L))

        // At 12_001L, giant pig finished (1000ms elapsed)
        assertFalse(session.isGiantPigActive(12_001L))
        assertTrue(session.isFinished(12_001L))
    }

    @Test
    fun `placement planner creates 7 spread placements`() {
        val planner = PigPlacementPlanner()
        val placements = planner.plan(PigEasterEggDefaults.PIG_COUNT)
        assertEquals(7, placements.size)
        placements.forEach {
            assertTrue(it.centerXFraction in 0f..1f)
            assertTrue(it.centerYFraction in 0f..1f)
        }
    }
}
