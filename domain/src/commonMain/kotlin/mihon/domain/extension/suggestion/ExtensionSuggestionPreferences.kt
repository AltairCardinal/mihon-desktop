package mihon.domain.extension.suggestion

import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.Base64
/** Device-local presentation choices, excluded by existing backup and sync app-state policy. */
class ExtensionSuggestionPreferences(store: PreferenceStore) {
    val expanded = store.getBoolean(Preference.appStateKey("extension_suggestions_expanded"), true)
    val ignored = store.getString(Preference.appStateKey("extension_suggestions_ignored"), "")
    fun ignoredIdentities() = ignored.changes().map { value ->
        value.lineSequence().mapNotNull { encoded ->
            runCatching {
                val parts = encoded.split('.').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                require(parts.size == 3)
                SuggestionIdentity(parts[0], parts[1], parts[2])
            }.getOrNull()
        }.toSet()
    }
}
