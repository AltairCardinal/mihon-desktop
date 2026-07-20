package mihon.desktop.extension

import java.util.UUID
import java.util.prefs.Preferences
import tachiyomi.core.common.preference.DesktopPreferenceStore

internal class IsolatedDesktopPreferenceStore private constructor(
    val store: DesktopPreferenceStore,
    private val parent: Preferences,
    private val node: Preferences,
) : AutoCloseable {
    override fun close() {
        node.removeNode()
        parent.flush()
    }

    companion object {
        fun create(): IsolatedDesktopPreferenceStore {
            val parent = Preferences.userRoot().node("/mihon-tests/real-extension-fixtures")
            val node = parent.node(UUID.randomUUID().toString())
            return IsolatedDesktopPreferenceStore(DesktopPreferenceStore(node), parent, node)
        }
    }
}
