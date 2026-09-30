package mihon.desktop.settings

import tachiyomi.core.common.preference.Preference

/** A UI save boundary for one preference, retaining its exact previous value/unset state on failure. */
internal fun <T> saveDesktopPreference(
    preference: Preference<T>,
    value: T,
    write: (T) -> Unit = preference::set,
): Boolean {
    val previous = try {
        preference.get() to preference.isSet()
    } catch (_: Exception) {
        return false
    }
    if (previous.first == value) return true
    return try {
        write(value)
        true
    } catch (_: Exception) {
        // When restoration also fails, consumers re-read authority and feedback asks the user to check it.
        runCatching { if (previous.second) preference.set(previous.first) else preference.delete() }
        false
    }
}
