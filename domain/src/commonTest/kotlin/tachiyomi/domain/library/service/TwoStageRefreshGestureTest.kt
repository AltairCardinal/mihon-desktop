package tachiyomi.domain.library.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TwoStageRefreshGestureTest {
    @Test
    fun `inertia beyond the absolute armed deadline cannot rearm without a new segment`() {
        val actual = TwoStageRefreshGesture()
        actual.scroll(80f, 0)
        for (time in 200L..2800L step 200) actual.scroll(1f, time) shouldBe false
        actual.scroll(80f, 3000) shouldBe false
        actual.stage shouldBe TwoStageRefreshGesture.Stage.IDLE
        actual.scroll(80f, 3200) shouldBe false
        actual.stage shouldBe TwoStageRefreshGesture.Stage.IDLE
        actual.scroll(80f, 3600) shouldBe false
        actual.stage shouldBe TwoStageRefreshGesture.Stage.ARMED
    }

    @Test
    fun `two distinct segments commit once only after all time and distance thresholds`() {
        val actual = TwoStageRefreshGesture()
        actual.scroll(40f, 0) shouldBe false
        actual.stage shouldBe TwoStageRefreshGesture.Stage.HINTING
        actual.scroll(40f, 100) shouldBe false
        actual.stage shouldBe TwoStageRefreshGesture.Stage.ARMED
        actual.scroll(48f, 499) shouldBe false
        actual.scroll(47f, 899) shouldBe false
        actual.scroll(1f, 910) shouldBe true
        actual.stage shouldBe TwoStageRefreshGesture.Stage.REFRESHING
        actual.scroll(100f, 1400) shouldBe false
        actual.complete(1500)
        actual.stage shouldBe TwoStageRefreshGesture.Stage.COOLDOWN
        actual.scroll(10f, 2200) shouldBe false
        actual.tick(2999)
        actual.stage shouldBe TwoStageRefreshGesture.Stage.COOLDOWN
        actual.tick(3000)
        actual.stage shouldBe TwoStageRefreshGesture.Stage.IDLE
    }

    @Test
    fun `armed deadline is absolute and invalid inputs revoke unsubmitted intent`() {
        val actual = TwoStageRefreshGesture()
        actual.scroll(80f, 0)
        actual.stage shouldBe TwoStageRefreshGesture.Stage.ARMED
        actual.scroll(1f, 200)
        actual.scroll(1f, 400)
        actual.tick(3000)
        actual.stage shouldBe TwoStageRefreshGesture.Stage.IDLE
        for (cancel in listOf<(TwoStageRefreshGesture) -> Unit>(
            { it.scroll(-1f, 3100) },
            { it.scroll(10f, 3100, false) },
            { it.cancel() },
        )) {
            val armed = TwoStageRefreshGesture()
            armed.scroll(80f, 3050)
            armed.stage shouldBe TwoStageRefreshGesture.Stage.ARMED
            cancel(armed)
            armed.stage shouldBe TwoStageRefreshGesture.Stage.IDLE
        }
        actual.scroll(0f, 4000)
        actual.stage shouldBe TwoStageRefreshGesture.Stage.IDLE
    }
}
