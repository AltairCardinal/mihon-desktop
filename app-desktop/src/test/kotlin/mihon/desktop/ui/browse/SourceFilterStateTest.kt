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

    @Test
    fun `hasActiveFilters detects tri-state sort and recursively nested filters`() {
        val triState = object : Filter.TriState("Licensed") {}
        val sort = object : Filter.Sort("Order", arrayOf("Newest")) {}
        val nested = object : Filter.Group<Filter<*>>(
            "Outer",
            listOf(object : Filter.Group<Filter<*>>("Inner", listOf(TestCheckBox("Completed"))) {}),
        ) {}
        assertFalse(hasActiveFilters(FilterList(triState, sort, nested)))

        triState.state = Filter.TriState.STATE_INCLUDE
        assertTrue(hasActiveFilters(FilterList(triState)))
        triState.state = Filter.TriState.STATE_IGNORE
        sort.state = Filter.Sort.Selection(0, true)
        assertTrue(hasActiveFilters(FilterList(sort)))
        sort.state = null
        val inner = nested.state.single() as Filter.Group<*>
        (inner.state.single() as Filter.CheckBox).state = true
        assertTrue(hasActiveFilters(FilterList(nested)))
    }
}
