package mihon.domain.easteregg

object PigEasterEggTrigger {
    const val TRIGGER_CHAR = "🐖"
    const val TRIGGER_FACE_CHAR = "🐷"
    val TRIGGER_CHARS = listOf(TRIGGER_CHAR, TRIGGER_FACE_CHAR)

    fun isTrigger(query: String?): Boolean =
        query != null && TRIGGER_CHARS.any { query.contains(it) }

    fun check(query: String?, controller: PigEasterEggController = PigEasterEggController.Default): Boolean {
        if (isTrigger(query)) {
            controller.fire()
            return true
        }
        return false
    }
}
