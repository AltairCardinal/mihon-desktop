package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceFilterStateTest {

    // Concrete implementations for testing
    private class TestCheckBox(name: String, state: Boolean = false) : Filter.CheckBox(name, state)
    private class TestText(name: String) : Filter.Text(name)
    private class TestGroup(state: List<Filter<*>>) : Filter.Group<Filter<*>>("Group", state)
    private class TestSelect<V>(values: Array<V>, state: Int = 0) : Filter.Select<V>("Select", values, state)
    private class TestSort(values: Array<String>, state: Filter.Sort.Selection? = null) :
        Filter.Sort("Sort", values, state)

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

    @Test
    fun `copy states requires every recursively nested filter to be a fresh instance`() {
        val committedChild = TestCheckBox("Completed", true)
        val committedGroup = TestGroup(listOf(committedChild))
        val freshChild = TestCheckBox("Completed")
        val freshGroup = TestGroup(listOf(freshChild))

        FilterList(committedGroup).copyStatesToFreshTree(FilterList(freshGroup))
        assertNotSame(committedGroup, freshGroup)
        assertNotSame(committedChild, freshChild)
        assertTrue(freshChild.state)

        val reusedGroup = TestGroup(listOf(committedChild))
        assertThrows(IllegalArgumentException::class.java) {
            FilterList(committedGroup).copyStatesToFreshTree(FilterList(reusedGroup))
        }
    }

    @Test
    fun `copy states rejects Select value shape content and runtime array type drift`() {
        val committed = TestSelect(arrayOf("First", "Second"), state = 1)
        val incompatible = listOf<Filter<*>>(
            TestSelect(arrayOf("First")),
            TestSelect(arrayOf("First", "Other")),
            TestSelect(arrayOf<Any?>("First", "Second")),
        )

        incompatible.forEach { fresh ->
            assertThrows(IllegalArgumentException::class.java) {
                FilterList(committed).copyStatesToFreshTree(FilterList(fresh))
            }
            assertEquals(0, fresh.state)
        }
    }

    @Test
    fun `copy states rejects Sort values drift before copying selection`() {
        val committed = TestSort(
            arrayOf("Newest", "Oldest"),
            Filter.Sort.Selection(1, ascending = false),
        )
        val fresh = TestSort(arrayOf("Oldest", "Newest"))

        assertThrows(IllegalArgumentException::class.java) {
            FilterList(committed).copyStatesToFreshTree(FilterList(fresh))
        }
        assertNull(fresh.state)
    }
}
