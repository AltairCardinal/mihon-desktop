package android.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View

/**
 * Minimal in-memory Android text hierarchy required by extension verification.
 * These types retain callback and enabled/error state but do not render widgets.
 */
open class TextView(context: Context) : View(context) {
    private val textWatchers = mutableListOf<TextWatcher>()
    private var text: CharSequence = ""

    var error: CharSequence? = null

    open fun addTextChangedListener(watcher: TextWatcher?) {
        watcher?.let(textWatchers::add)
    }

    open fun setText(text: CharSequence?) {
        val previous = this.text
        val next = text ?: ""
        val watchers = textWatchers.toList()
        watchers.forEach { it.beforeTextChanged(previous, 0, previous.length, next.length) }
        this.text = next
        watchers.forEach { it.onTextChanged(next, 0, previous.length, next.length) }
        val editable = StringEditable(next.toString())
        watchers.forEach { it.afterTextChanged(editable) }
    }
}

open class EditText(context: Context) : TextView(context)

open class Button(context: Context) : TextView(context)

private class StringEditable(
    private val value: String,
) : Editable {
    override val length: Int get() = value.length

    override fun get(index: Int): Char = value[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)

    override fun toString(): String = value
}
