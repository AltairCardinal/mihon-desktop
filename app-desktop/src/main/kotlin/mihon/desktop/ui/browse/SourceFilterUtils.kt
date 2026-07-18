package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun hasActiveFilters(filters: FilterList): Boolean = filters.any { filter ->
    when (filter) {
        is Filter.CheckBox -> filter.state
        is Filter.TriState -> !filter.isIgnored()
        is Filter.Text -> filter.state.isNotBlank()
        is Filter.Select<*> -> filter.state != 0
        is Filter.Sort -> filter.state != null
        is Filter.Group<*> -> hasActiveFilters(FilterList(filter.state.filterIsInstance<Filter<*>>()))
        else -> false
    }
}
