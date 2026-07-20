package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.util.prefs.PreferenceChangeListener
import java.util.prefs.Preferences

class DesktopPreference<T>(
    private val preferences: Preferences,
    private val key: String,
    private val defaultValue: T,
    private val reader: (Preferences, String, T) -> T,
    private val writer: (Preferences, String, T) -> Unit,
) : Preference<T> {

    override fun key(): String = key

    override fun get(): T = reader(preferences, key, defaultValue)

    override fun set(value: T) {
        writer(preferences, key, value)
        preferences.flush()
    }

    override fun isSet(): Boolean {
        return preferences.get(key, null) != null
    }

    override fun delete() {
        preferences.remove(key)
        preferences.flush()
    }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = callbackFlow {
        val listener = PreferenceChangeListener { evt ->
            if (evt.key == key) {
                trySend(get())
            }
        }
        preferences.addPreferenceChangeListener(listener)
        awaitClose {
            try {
                preferences.removePreferenceChangeListener(listener)
            } catch (error: IllegalStateException) {
                if (error.message != "Node has been removed.") throw error
            }
        }
    }.onStart { emit(get()) }.conflate()

    override fun stateIn(scope: CoroutineScope): StateFlow<T> {
        return changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}
