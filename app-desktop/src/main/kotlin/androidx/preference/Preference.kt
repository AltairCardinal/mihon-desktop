package androidx.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * Desktop stub for androidx.preference.Preference.
 * Mirrors the Android Preference API used by Tachiyomi extensions to expose settings.
 */
open class Preference(val context: Context) {

    var key: String? = null
    var title: CharSequence? = null
    var summary: CharSequence? = null
    var isEnabled: Boolean = true
    var isVisible: Boolean = true

    @JvmField
    var defaultValue: Any? = null

    @JvmField
    var onPreferenceChangeListener: OnPreferenceChangeListener? = null

    @JvmField
    var onPreferenceClickListener: OnPreferenceClickListener? = null

    fun setOnPreferenceChangeListener(listener: OnPreferenceChangeListener?) {
        onPreferenceChangeListener = listener
    }

    fun setOnPreferenceClickListener(listener: OnPreferenceClickListener?) {
        onPreferenceClickListener = listener
    }

    fun setDefaultValue(value: Any?) {
        defaultValue = value
    }

    open fun callChangeListener(newValue: Any?): Boolean =
        onPreferenceChangeListener?.onPreferenceChange(this, newValue) ?: true

    fun getSharedPreferences(): SharedPreferences? =
        key?.let { context.getSharedPreferences("source_prefs", 0) }

    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }

    fun interface OnPreferenceClickListener {
        fun onPreferenceClick(preference: Preference): Boolean
    }
}
