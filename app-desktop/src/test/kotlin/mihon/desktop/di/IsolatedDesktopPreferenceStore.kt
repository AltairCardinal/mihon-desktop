package mihon.desktop.di

import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences

internal fun isolatedDesktopPreferenceStore(): DesktopPreferenceStore =
    DesktopPreferenceStore(
        Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"),
    )

/** Complete in-memory backing for the real DesktopPreferenceStore, without registry node lifetime. */
internal fun inMemoryDesktopPreferenceStore(): DesktopPreferenceStore =
    DesktopPreferenceStore(InMemoryDesktopPreferences(null, ""))

private class InMemoryDesktopPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, InMemoryDesktopPreferences>()
    override fun putSpi(key: String, value: String) {
        values[key] = value
    }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) {
        values.remove(key)
    }
    override fun removeNodeSpi() {
        values.clear()
    }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { InMemoryDesktopPreferences(this, name) }
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}
