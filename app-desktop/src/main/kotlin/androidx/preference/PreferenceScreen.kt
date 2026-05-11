package androidx.preference

import android.content.Context

/**
 * Desktop stub for androidx.preference.PreferenceScreen.
 * Collects preferences added by extensions.
 */
open class PreferenceScreen(context: Context) : PreferenceCategory(context) {

    /** Alias matching Android API name. */
    val preferenceManager: PreferenceManager = PreferenceManager(context)
}

/**
 * Desktop stub for androidx.preference.PreferenceManager.
 */
class PreferenceManager(val context: Context) {
    fun createPreferenceScreen(context: Context): PreferenceScreen = PreferenceScreen(context)
}
