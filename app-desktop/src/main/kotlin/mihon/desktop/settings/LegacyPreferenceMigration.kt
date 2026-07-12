package mihon.desktop.settings

import tachiyomi.core.common.preference.Preference
import java.util.prefs.Preferences

internal fun <T> Preference<T>.migrateFrom(
    legacy: Preferences,
    legacyKey: String,
    readLegacy: (String) -> T?,
): Preference<T> = apply {
    if (!isSet() && legacy.get(legacyKey, null) != null) {
        readLegacy(legacyKey)?.let(::set)
    }
}
