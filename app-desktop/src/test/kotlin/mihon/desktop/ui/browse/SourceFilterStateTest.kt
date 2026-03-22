package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceFilterStateTest {

    // Concrete implementations for testing
    private class TestCheckBox(name: String, state: Boolean = false) : Filter.CheckBox(name, state)
    private class TestText(name: String) : Filter.Text(name)

    @Test
    fun `hasActiveFilters is false when all filters are default`() {
        val filters = FilterList(
            TestCheckBox("Action", false),
            TestCheckBox("Romance", false),
        )
        assertFalse(hasActiveFilters(filters))
    }

    @Test
    fun `hasActiveFilters is true when any checkbox is checked`() {
        val filters = FilterList(
            TestCheckBox("Action", true),
            TestCheckBox("Romance", false),
        )
        assertTrue(hasActiveFilters(filters))
    }

    @Test
    fun `hasActiveFilters is true when text filter has value`() {
        val textFilter = TestText("Author")
        textFilter.state = "Oda"
        val filters = FilterList(textFilter)
        assertTrue(hasActiveFilters(filters))
    }

    @Test
    fun `hasActiveFilters is false for empty filter list`() {
        assertFalse(hasActiveFilters(FilterList()))
    }
}
