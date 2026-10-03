package mihon.presentation.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/** Query results cannot replace the editor's selection or IME composition. */
internal class HistorySearchInputState(query: String) {
    var value by mutableStateOf(TextFieldValue(query))
        private set

    fun accept(value: TextFieldValue, onQuery: (String?) -> Unit) {
        this.value = value
        onQuery(value.text)
    }

    fun align(query: String) {
        if (value.text != query) value = TextFieldValue(query)
    }

    fun clear(onQuery: (String?) -> Unit) = accept(TextFieldValue(), onQuery)
}
