package tachiyomi.domain.library.service

/** Product policy shared by the two Desktop content owners. Distances are already normalized dp. */
class TwoStageRefreshGesture {
    enum class Stage { IDLE, HINTING, ARMED, REFRESHING, COOLDOWN }
    var stage: Stage = Stage.IDLE
        private set

    private var hintAt = 0L
    private var armedAt = 0L
    private var lastInput: Long? = null
    private var distance = 0f
    private var secondSegment = false
    private var awaitingFreshSegment = false

    fun scroll(upwardDp: Float, now: Long, eligible: Boolean = true): Boolean {
        val previous = lastInput
        tick(now)
        lastInput = now
        if (stage == Stage.REFRESHING || stage == Stage.COOLDOWN) return false
        if (!eligible || !upwardDp.isFinite() || upwardDp < 0f || (previous != null && now < previous)) {
            cancel()
            return false
        }
        if (upwardDp == 0f) return false
        if (awaitingFreshSegment) {
            if (previous != null && now - previous < 400) return false
            awaitingFreshSegment = false
        }
        when (stage) {
            Stage.IDLE -> {
                stage = Stage.HINTING
                hintAt = now
                distance = upwardDp
            }
            Stage.HINTING -> distance += upwardDp
            Stage.ARMED -> {
                if (!secondSegment) {
                    if (previous == null || now - previous < 400 || now - hintAt < 300) return false
                    secondSegment = true
                    distance = 0f
                }
                distance += upwardDp
                if (distance >= 48f) {
                    stage = Stage.REFRESHING
                    return true
                }
            }
            else -> Unit
        }
        if (stage == Stage.HINTING && distance >= 80f) {
            stage = Stage.ARMED
            armedAt = now
            distance = 0f
            secondSegment = false
        }
        return false
    }

    /** Called on all actual content scrolling, including input that cannot arm refresh. */
    fun activity(now: Long) {
        if (stage == Stage.COOLDOWN || stage == Stage.REFRESHING) lastInput = now
    }

    fun tick(now: Long) {
        if (stage == Stage.ARMED && now - armedAt >= 3000) {
            cancel(force = true)
            awaitingFreshSegment = true
            return
        }
        if ((stage == Stage.HINTING && lastInput?.let { now - it >= 3000 } == true) ||
            (stage == Stage.COOLDOWN && lastInput?.let { now - it >= 800 } == true)
        ) {
            cancel(force = true)
        }
    }

    fun cancel(force: Boolean = false) {
        if (!force && stage in setOf(Stage.REFRESHING, Stage.COOLDOWN)) return
        stage = Stage.IDLE
        distance = 0f
        secondSegment = false
        awaitingFreshSegment = false
    }

    fun complete(now: Long) {
        stage = Stage.COOLDOWN
        lastInput = now
    }
}
