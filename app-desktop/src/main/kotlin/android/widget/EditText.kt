package android.widget

import android.text.TextWatcher
import android.view.View

/**
 * Minimal in-memory Android text hierarchy required by extension verification.
 * These types retain callback and enabled/error state but do not render widgets
 * or invoke text callbacks.
 */
open class TextView : View() {
    private val textWatchers = mutableListOf<TextWatcher>()

    var error: CharSequence? = null

    open fun addTextChangedListener(watcher: TextWatcher?) {
        watcher?.let(textWatchers::add)
    }
}

open class EditText : TextView()

open class Button : TextView()
