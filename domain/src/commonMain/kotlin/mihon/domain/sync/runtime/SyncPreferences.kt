package mihon.domain.sync.runtime

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.MessageDigest
import java.util.Base64

/** Installation-local options and presentation history; never included in ordinary backups. */
class SyncPreferences(private val store: PreferenceStore) {
    val startup = store.getBoolean(Preference.appStateKey("sync_startup"), true)
    val periodMinutes = store.getInt(Preference.appStateKey("sync_period_minutes"), 60)
    val lastAttempt = store.getLong(Preference.appStateKey("sync_last_attempt"), 0)
    val lastSuccess = store.getLong(Preference.appStateKey("sync_last_success"), 0)
    val history = store.getString(Preference.appStateKey("sync_history"), "[]")
    val deviceName = store.getString(Preference.appStateKey("sync_device_name"), "")
    val scheduleAnchor = store.getLong(Preference.appStateKey("sync_schedule_anchor"), 0)
    val importPaused = store.getBoolean(Preference.appStateKey("sync_import_paused"), false)

    fun activeBulkJob(spaceId: String, generation: Long): Preference<String> {
        // java.util.prefs restricts keys to 80 characters, including the app-state prefix.
        val scope = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("$generation:$spaceId".toByteArray(Charsets.UTF_8)),
        )
        return store.getString(Preference.appStateKey("sync_bulk_$scope"), "")
    }

    fun intervalMinutes(): Int = periodMinutes.get().takeIf { it in intervals } ?: 0

    fun setInterval(minutes: Int) {
        require(minutes in intervals)
        periodMinutes.set(minutes)
    }

    companion object {
        val intervals = setOf(0, 15, 60, 360, 1440)
    }
}
