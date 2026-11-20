package mihon.desktop.domain

import java.util.prefs.AbstractPreferences

/** Raw preference backend; serialization and change delivery use production DesktopPreferenceStore. */
internal class DetailActionTestPreferences : AbstractPreferences(null, "") {
    private val entries = mutableMapOf<String, String>()
    var failWrites = false
    var afterRead: ((String) -> Unit)? = null
    override fun putSpi(key: String, value: String) {
        check(!failWrites) { "Injected preference write failure" }
        entries[key] = value
    }
    override fun getSpi(key: String): String? {
        val value = entries[key]
        afterRead?.invoke(key)
        return value
    }
    override fun removeSpi(key: String) {
        entries.remove(key)
    }
    override fun removeNodeSpi() {
        entries.clear()
    }
    override fun keysSpi(): Array<String> = entries.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = error("No child preferences in this fixture")
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}
