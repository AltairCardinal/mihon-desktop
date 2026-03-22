package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun hasActiveFilters(filters: FilterList): Boolean = filters.any { filter ->
    when (filter) {
        is Filter.CheckBox -> filter.state
        is Filter.Text -> filter.state.isNotBlank()
        is Filter.Select<*> -> filter.state != 0
        is Filter.Group<*> -> filter.state.filterIsInstance<Filter.CheckBox>().any { it.state }
        else -> false
    }
}
