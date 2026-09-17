package tachiyomi.domain.creator.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

enum class CreatorCheckFrequency(val value: String) {
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    ;

    companion object {
        fun parse(value: String): CreatorCheckFrequency? = entries.firstOrNull { it.value == value }
    }
}

/** Device preference; legacy per-author durations are retained only in recovery data. */
class CreatorDiscoveryPreferences(private val store: PreferenceStore) {
    fun frequency() = store.getString(FREQUENCY_KEY, CreatorCheckFrequency.DAILY.value)

    @Synchronized
    fun migrate() {
        val migrated = store.getBoolean(Preference.appStateKey("creator_global_frequency_migrated"))
        if (migrated.get()) return
        if (!frequency().isSet()) frequency().set(CreatorCheckFrequency.DAILY.value)
        migrated.set(true)
    }

    fun current(): CreatorCheckFrequency {
        migrate()
        return CreatorCheckFrequency.parse(frequency().get()) ?: CreatorCheckFrequency.DAILY
    }

    companion object {
        const val FREQUENCY_KEY = "creator_discovery_frequency"
    }
}

/** Re-read frequency and current device zone at calculation time, including after resume. */
class CreatorDiscoverySchedule(
    private val frequency: () -> CreatorCheckFrequency = { CreatorCheckFrequency.DAILY },
    val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun nextDue(successAt: Long = clock.millis()): Long {
        val currentZone = zone()
        val local = Instant.ofEpochMilli(successAt).atZone(currentZone).toLocalDateTime()
        val due = when (frequency()) {
            CreatorCheckFrequency.DAILY -> local.plusDays(1)
            CreatorCheckFrequency.WEEKLY -> local.plusDays(7)
            CreatorCheckFrequency.MONTHLY -> local.plusMonths(1)
        }
        // atZone advances gaps and chooses the earlier offset for repeated wall times.
        return due.atZone(currentZone).toInstant().toEpochMilli()
    }
}
