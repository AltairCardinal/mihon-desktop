package mihon.domain.easteregg

class PigEasterEggSession(
    val startTime: Long,
    val totalPigCount: Int = PigEasterEggDefaults.PIG_COUNT,
    val initialLifetimeMillis: Long = PigEasterEggDefaults.INITIAL_LIFETIME_MILLIS,
    val extensionPerClickMillis: Long = PigEasterEggDefaults.EXTENSION_PER_CLICK_MILLIS,
    val warningBeforeEndMillis: Long = PigEasterEggDefaults.WARNING_BEFORE_END_MILLIS,
    val giantPigLifetimeMillis: Long = PigEasterEggDefaults.GIANT_PIG_LIFETIME_MILLIS,
) {
    private val clickedPigIds = mutableSetOf<Int>()
    private var additionalMillis = 0L
    private var allPigsClickedTime: Long? = null

    val activePigCount: Int get() = totalPigCount - clickedPigIds.size
    val isAllPigsClicked: Boolean get() = clickedPigIds.size >= totalPigCount

    val endTime: Long
        get() = startTime + initialLifetimeMillis + additionalMillis

    fun remainingMillis(currentTime: Long): Long =
        (endTime - currentTime).coerceAtLeast(0L)

    fun isPigClicked(id: Int): Boolean = id in clickedPigIds

    fun clickPig(id: Int, clickTime: Long): Boolean {
        if (id !in 0 until totalPigCount || id in clickedPigIds) return false
        if (isExpired(clickTime)) return false
        clickedPigIds.add(id)
        additionalMillis += extensionPerClickMillis
        if (isAllPigsClicked && allPigsClickedTime == null) {
            allPigsClickedTime = clickTime
        }
        return true
    }

    fun isEndingSoon(currentTime: Long): Boolean {
        if (isAllPigsClicked) return false
        val remaining = remainingMillis(currentTime)
        return remaining in 1..warningBeforeEndMillis
    }

    fun isExpired(currentTime: Long): Boolean =
        !isAllPigsClicked && remainingMillis(currentTime) <= 0L

    fun isGiantPigActive(currentTime: Long): Boolean {
        val giantStart = allPigsClickedTime ?: return false
        return currentTime in giantStart until (giantStart + giantPigLifetimeMillis)
    }

    fun isFinished(currentTime: Long): Boolean {
        if (isAllPigsClicked) {
            val giantStart = allPigsClickedTime ?: return false
            return currentTime >= giantStart + giantPigLifetimeMillis
        }
        return isExpired(currentTime)
    }
}
