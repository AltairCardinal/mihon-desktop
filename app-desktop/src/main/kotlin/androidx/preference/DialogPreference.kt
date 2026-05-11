package androidx.preference

import android.content.Context

/**
 * Desktop stub for androidx.preference.DialogPreference.
 */
open class DialogPreference(context: Context) : Preference(context) {
    var dialogTitle: CharSequence? = null
    var dialogMessage: CharSequence? = null
}
