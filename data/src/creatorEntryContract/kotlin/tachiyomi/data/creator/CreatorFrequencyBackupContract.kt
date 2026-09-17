package tachiyomi.data.creator

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences

suspend fun verifyCreatorFrequencyBackup(
    store: PreferenceStore,
    restore: suspend (List<Pair<String, String>>) -> Boolean,
) {
    val key = CreatorDiscoveryPreferences.FREQUENCY_KEY
    store.getString(key).set("weekly")
    restore(emptyList())
    check(store.getString(key).get() == "weekly") { "Legacy backup must preserve configured frequency" }
    for (value in listOf("daily", "weekly", "monthly")) {
        restore(listOf(key to value))
        check(store.getString(key).get() == value) { "Valid backed up frequency must restore" }
    }
    check(restore(listOf(key to "future-frequency", "ordinary-setting" to "restored"))) {
        "Unknown frequency must be reported"
    }
    check(store.getString(key).get() == "monthly") { "Unknown frequency must not replace valid local choice" }
    check(store.getString("ordinary-setting").get() == "restored") {
        "Compatibility issue must not block unrelated settings"
    }
}
