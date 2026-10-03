package mihon.presentation.history

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

abstract class HistorySearchInputContract {
    @Test
    fun `same query keeps selection and Chinese IME composition`() {
        val input = HistorySearchInputState("")
        var query: String? = null
        val composing = TextFieldValue("a中文c", TextRange(3), TextRange(1, 3))
        input.accept(composing) { query = it }
        input.align(requireNotNull(query))
        assertEquals(composing, input.value)
        input.clear { query = it }
        assertEquals("", query)
        assertEquals(TextFieldValue(), input.value)
    }

    @Test
    fun `chapter format retains original fixed decimal separator and precision`() {
        assertEquals("1.234", formatChapterNumber(1.2344))
        assertEquals("1", formatChapterNumber(1.0))
    }
}
