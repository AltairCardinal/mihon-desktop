package mihon.domain.sync.runtime

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Installation-local options and presentation history; never included in ordinary backups. */
class SyncPreferences(store: PreferenceStore) {
    val startup = store.getBoolean(Preference.appStateKey("sync_startup"), true)
    val periodMinutes = store.getInt(Preference.appStateKey("sync_period_minutes"), 60)
    val lastAttempt = store.getLong(Preference.appStateKey("sync_last_attempt"), 0)
    val lastSuccess = store.getLong(Preference.appStateKey("sync_last_success"), 0)
    val history = store.getString(Preference.appStateKey("sync_history"), "[]")
    val deviceName = store.getString(Preference.appStateKey("sync_device_name"), "")

    fun intervalMinutes(): Int = periodMinutes.get().takeIf { it in intervals } ?: 0

    fun setInterval(minutes: Int) {
        require(minutes in intervals)
        periodMinutes.set(minutes)
    }

    companion object {
        val intervals = setOf(0, 15, 60, 360, 1440)
    }
}
