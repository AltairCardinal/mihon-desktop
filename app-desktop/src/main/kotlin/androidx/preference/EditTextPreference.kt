package androidx.preference

import android.content.Context
import android.widget.EditText

/**
 * Desktop stub for androidx.preference.EditTextPreference.
 */
open class EditTextPreference(context: Context) : DialogPreference(context) {

    var text: String? = null

    private var onBindEditTextListener: OnBindEditTextListener? = null

    fun setOnBindEditTextListener(listener: OnBindEditTextListener?) {
        onBindEditTextListener = listener
    }

    fun interface OnBindEditTextListener {
        fun onBindEditText(editText: EditText)
    }
}
