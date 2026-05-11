package androidx.preference

import android.content.Context

/**
 * Desktop stub for androidx.preference.TwoStatePreference.
 */
open class TwoStatePreference(context: Context) : Preference(context) {

    var isChecked: Boolean = false

    var summaryOn: CharSequence? = null
    var summaryOff: CharSequence? = null
}
