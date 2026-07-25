package tachiyomi.domain.track.service

/**
 * Wire-level status rules shared by Android's original trackers and Desktop adapters.
 * Provider HTTP clients remain platform adapters; numeric domain status values and
 * their remote representations have one source of truth here.
 */
object TrackerProviderContracts {
    val androidProviderIds = (1L..9L).toList()

    fun authentication(id: Long): TrackerAuthentication = when (id) {
        1L, 2L, 4L, 5L -> TrackerAuthentication.OAUTH
        3L, 7L -> TrackerAuthentication.USERNAME_PASSWORD
        6L, 8L, 9L -> TrackerAuthentication.API_KEY
        else -> throw IllegalArgumentException("Unknown Android tracker: $id")
    }

    val myAnimeList = StatusWireContract(
        mapOf(
            1L to "reading",
            2L to "completed",
            3L to "on_hold",
            4L to "dropped",
            6L to "plan_to_read",
            7L to "reading",
        ),
        fallbackStatus = 1L,
    )
    val aniList = StatusWireContract(
        mapOf(1L to "CURRENT", 2L to "COMPLETED", 3L to "PAUSED", 4L to "DROPPED", 5L to "PLANNING", 6L to "REPEATING"),
    )
    val kitsu = StatusWireContract(
        mapOf(1L to "current", 2L to "completed", 3L to "on_hold", 4L to "dropped", 5L to "planned"),
    )
    val shikimori = StatusWireContract(
        mapOf(
            1L to "watching",
            2L to "completed",
            3L to "on_hold",
            4L to "dropped",
            5L to "planned",
            6L to "rewatching",
        ),
    )
    val bangumi = StatusWireContract(
        mapOf(1L to "1", 2L to "2", 3L to "3", 4L to "4", 5L to "5"),
    )
}

class StatusWireContract internal constructor(
    private val values: Map<Long, String>,
    private val fallbackStatus: Long? = null,
) {
    fun statusToWire(status: Long): String =
        values[status]
            ?: fallbackStatus?.let(values::getValue)
            ?: throw IllegalArgumentException("Unknown status: $status")

    fun wireToStatus(value: String): Long =
        values.entries.firstOrNull { it.value == value }?.key
            ?: fallbackStatus
            ?: throw IllegalArgumentException("Unknown status: $value")
}
