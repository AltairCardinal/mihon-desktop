package android.content

/**
 * Desktop stub for android.content.SharedPreferences.
 * Backed by java.util.prefs.Preferences so values persist across JVM restarts.
 */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun getAll(): Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * JVM implementation of [SharedPreferences] backed by [java.util.prefs.Preferences].
 * Each named preferences file maps to a Preferences node under "mihon/extensions/<name>".
 */
internal class DesktopSharedPreferences(name: String) : SharedPreferences {

    private val node = java.util.prefs.Preferences.userRoot().node("mihon/extensions/$name")

    override fun getString(key: String, defValue: String?): String? =
        if (node.keys().contains(key)) node.get(key, defValue) else defValue

    override fun getInt(key: String, defValue: Int): Int = node.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = node.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = node.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = node.getBoolean(key, defValue)

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val raw = node.get(key, null) ?: return defValues
        return if (raw.isEmpty()) emptySet() else raw.split("\u0000").toSet()
    }

    override fun contains(key: String): Boolean = node.keys().contains(key)

    override fun getAll(): Map<String, Any?> = node.keys().associateWith { node.get(it, null) }

    override fun edit(): SharedPreferences.Editor = EditorImpl(node)

    private class EditorImpl(
        private val node: java.util.prefs.Preferences,
    ) : SharedPreferences.Editor {
        private val ops = mutableListOf<() -> Unit>()

        override fun putString(key: String, value: String?) = apply {
            ops += { if (value != null) node.put(key, value) else node.remove(key) }
        }
        override fun putInt(key: String, value: Int) = apply { ops += { node.putInt(key, value) } }
        override fun putLong(key: String, value: Long) = apply { ops += { node.putLong(key, value) } }
        override fun putFloat(key: String, value: Float) = apply { ops += { node.putFloat(key, value) } }
        override fun putBoolean(key: String, value: Boolean) = apply { ops += { node.putBoolean(key, value) } }

        override fun putStringSet(key: String, values: Set<String>?) = apply {
            ops += { if (values != null) node.put(key, values.joinToString("\u0000")) else node.remove(key) }
        }
        override fun remove(key: String) = apply { ops += { node.remove(key) } }
        override fun clear() = apply { ops += { node.keys().forEach { node.remove(it) } } }

        override fun apply() { ops.forEach { it() }; node.flush() }
        override fun commit(): Boolean { apply(); return true }
    }
}
