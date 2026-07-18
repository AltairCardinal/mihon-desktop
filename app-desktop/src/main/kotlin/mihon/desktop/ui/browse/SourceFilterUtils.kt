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

internal fun FilterList.deepCopyFilters(): FilterList = FilterList(map { it.deepCopy() })

private fun Filter<*>.deepCopy(): Filter<*> = when (this) {
    is Filter.Header -> Filter.Header(name)
    is Filter.Separator -> Filter.Separator(name)
    is Filter.CheckBox -> object : Filter.CheckBox(name, state) {}
    is Filter.TriState -> object : Filter.TriState(name, state) {}
    is Filter.Text -> object : Filter.Text(name, state) {}
    is Filter.Select<*> -> object : Filter.Select<Any?>(name, values.map { it }.toTypedArray(), state) {}
    is Filter.Sort -> object : Filter.Sort(name, values.copyOf(), state?.copy()) {}
    is Filter.Group<*> -> object : Filter.Group<Any?>(
        name,
        state.map { value -> (value as? Filter<*>)?.deepCopy() ?: value },
    ) {}
}
