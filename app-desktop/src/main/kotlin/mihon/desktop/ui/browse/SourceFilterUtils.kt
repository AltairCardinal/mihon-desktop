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

internal fun FilterList.copyStatesToFreshTree(fresh: FilterList): FilterList {
    require(this !== fresh) { "Source must return a fresh FilterList for draft editing" }
    require(size == fresh.size) { "Source FilterList structure changed while creating a draft" }
    zip(fresh).forEach { (committed, draft) -> committed.copyStateTo(draft) }
    return fresh
}

private fun Filter<*>.copyStateTo(draft: Filter<*>) {
    require(this !== draft) { "Source must return fresh Filter instances for draft editing" }
    require(name == draft.name && javaClass == draft.javaClass) {
        "Source Filter structure changed at '$name'"
    }
    when {
        this is Filter.Header && draft is Filter.Header -> Unit
        this is Filter.Separator && draft is Filter.Separator -> Unit
        this is Filter.CheckBox && draft is Filter.CheckBox -> draft.state = state
        this is Filter.TriState && draft is Filter.TriState -> draft.state = state
        this is Filter.Text && draft is Filter.Text -> draft.state = state
        this is Filter.Select<*> && draft is Filter.Select<*> -> {
            require(values.javaClass == draft.values.javaClass && values.contentDeepEquals(draft.values)) {
                "Source Select '$name' changed values"
            }
            draft.state = state
        }
        this is Filter.Sort && draft is Filter.Sort -> {
            require(values.contentEquals(draft.values)) { "Source Sort '$name' changed values" }
            draft.state = state?.copy()
        }
        this is Filter.Group<*> && draft is Filter.Group<*> -> {
            require(state.size == draft.state.size) { "Source Filter group '$name' changed shape" }
            state.zip(draft.state).forEach { (committedChild, draftChild) ->
                if (committedChild is Filter<*> && draftChild is Filter<*>) {
                    committedChild.copyStateTo(draftChild)
                } else {
                    require(committedChild == draftChild) { "Source Filter group '$name' changed values" }
                }
            }
        }
        else -> error("Source Filter kind changed at '$name'")
    }
}
