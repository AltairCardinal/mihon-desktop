package androidx.preference

import android.content.Context

/**
 * Desktop stub for androidx.preference.MultiSelectListPreference.
 */
open class MultiSelectListPreference(context: Context) : DialogPreference(context) {

    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
    var values: Set<String> = emptySet()

    fun findIndexOfValue(value: String?): Int =
        entryValues?.indexOfFirst { it.toString() == value } ?: -1
}
