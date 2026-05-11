package androidx.preference

import android.content.Context

/**
 * Desktop stub for androidx.preference.ListPreference.
 */
open class ListPreference(context: Context) : DialogPreference(context) {

    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
    var value: String? = null

    fun getEntry(): CharSequence? {
        val idx = entryValues?.indexOfFirst { it.toString() == value } ?: -1
        return if (idx >= 0) entries?.getOrNull(idx) else null
    }

    fun setValueIndex(index: Int) {
        value = entryValues?.getOrNull(index)?.toString()
    }

    fun findIndexOfValue(value: String?): Int =
        entryValues?.indexOfFirst { it.toString() == value } ?: -1
}
