package mihon.desktop.settings

import tachiyomi.core.common.preference.Preference
import java.util.prefs.Preferences

/**
 * Eagerly copies a legacy value when this preference is constructed and the new key is unset.
 * The legacy key is intentionally retained until migration verification is complete.
 */
internal fun <T> Preference<T>.migrateFrom(
    legacy: Preferences,
    legacyKey: String,
    readLegacy: (String) -> T?,
): Preference<T> = apply {
    if (!isSet() && legacy.get(legacyKey, null) != null) {
        readLegacy(legacyKey)?.let(::set)
    }
}
