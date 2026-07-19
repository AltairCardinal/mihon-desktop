package androidx.preference

import android.content.Context

/**
 * Desktop stub for androidx.preference.PreferenceCategory.
 * Acts as a container that groups sub-preferences.
 */
open class PreferenceCategory(context: Context) : Preference(context) {

    @Suppress("PropertyName")
    internal val _prefs = mutableListOf<Preference>()

    val preferences: List<Preference> get() = _prefs.toList()

    fun addPreference(preference: Preference): Boolean = _prefs.add(preference)

    val preferenceCount: Int get() = _prefs.size

    fun getPreference(index: Int): Preference = _prefs[index]
}
