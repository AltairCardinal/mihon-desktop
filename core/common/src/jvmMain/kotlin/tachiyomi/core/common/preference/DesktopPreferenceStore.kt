package tachiyomi.core.common.preference

import java.util.prefs.Preferences

class DesktopPreferenceStore(
    private val preferences: Preferences = Preferences.userRoot().node("/mihon"),
) : PreferenceStore {

    fun childNode(relativePath: String): Preferences {
        require(relativePath.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        return preferences.node(relativePath)
    }

    override fun getString(key: String, defaultValue: String): Preference<String> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def -> prefs.get(k, def) },
            writer = { prefs, k, v -> prefs.put(k, v) },
        )
    }

    override fun getLong(key: String, defaultValue: Long): Preference<Long> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def -> prefs.getLong(k, def) },
            writer = { prefs, k, v -> prefs.putLong(k, v) },
        )
    }

    override fun getInt(key: String, defaultValue: Int): Preference<Int> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def -> prefs.getInt(k, def) },
            writer = { prefs, k, v -> prefs.putInt(k, v) },
        )
    }

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def -> prefs.getFloat(k, def) },
            writer = { prefs, k, v -> prefs.putFloat(k, v) },
        )
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def -> prefs.getBoolean(k, def) },
            writer = { prefs, k, v -> prefs.putBoolean(k, v) },
        )
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def ->
                val raw = prefs.get(k, null) ?: return@DesktopPreference def
                if (raw.isEmpty()) emptySet() else raw.split(STRING_SET_SEPARATOR).toSet()
            },
            writer = { prefs, k, v ->
                prefs.put(k, v.joinToString(STRING_SET_SEPARATOR))
            },
        )
    }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def ->
                val raw = prefs.get(k, null) ?: return@DesktopPreference def
                try {
                    deserializer(raw)
                } catch (_: Exception) {
                    def
                }
            },
            writer = { prefs, k, v -> prefs.put(k, serializer(v)) },
        )
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> {
        return DesktopPreference(
            preferences = preferences,
            key = key,
            defaultValue = defaultValue,
            reader = { prefs, k, def ->
                try {
                    val raw = prefs.get(k, null) ?: return@DesktopPreference def
                    deserializer(raw.toInt())
                } catch (_: Exception) {
                    def
                }
            },
            writer = { prefs, k, v -> prefs.put(k, serializer(v).toString()) },
        )
    }

    override fun getAll(): Map<String, *> {
        return preferences.keys().associateWith { preferences.get(it, null) }
    }

    fun clearAndFlush() {
        fun clear(node: Preferences) {
            node.clear()
            node.childrenNames().forEach { clear(node.node(it)) }
            node.flush()
        }
        clear(preferences)
    }

    companion object {
        private const val STRING_SET_SEPARATOR = "\u001F" // Unit Separator
    }
}
