package mihon.domain.settings

object SettingsSearchPolicy {
    private const val MAX_RESULTS = 10

    fun <RouteToken> search(
        screens: List<SearchableSettingsScreen<RouteToken>>,
        query: String,
        layoutDirection: SettingsLayoutDirection = SettingsLayoutDirection.Ltr,
    ): List<SettingsSearchResult<RouteToken>> {
        if (query.isEmpty()) return emptyList()
        return screens
            .asSequence()
            .flatMap { screen ->
                screen.preferences
                    .asSequence()
                    .flatMap { preference -> preference.entriesWithGroup() }
                    .filter { candidate -> candidate.entry.isSearchable() }
                    .filter { candidate -> candidate.entry.matches(query) }
                    .map { candidate ->
                        SettingsSearchResult(
                            route = screen.route,
                            title = candidate.entry.title,
                            breadcrumb = breadcrumb(screen.title, candidate.groupTitle, layoutDirection),
                            anchorTitle = candidate.entry.title,
                        )
                    }
            }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun SearchablePreference.entriesWithGroup(): Sequence<Candidate> = when (this) {
        is SearchablePreference.Entry -> sequenceOf(Candidate(this, groupTitle = null))
        is SearchablePreference.Group -> {
            if (enabled && title.isNotBlank()) {
                entries.asSequence().map { Candidate(it, groupTitle = title) }
            } else {
                emptySequence()
            }
        }
    }

    private fun SearchablePreference.Entry.isSearchable(): Boolean {
        return enabled && title.isNotBlank() && type == SearchablePreference.EntryType.Standard
    }

    private fun SearchablePreference.Entry.matches(query: String): Boolean {
        return title.contains(query, ignoreCase = true) || summary?.contains(query, ignoreCase = true) == true
    }

    private fun breadcrumb(
        screenTitle: String,
        groupTitle: String?,
        layoutDirection: SettingsLayoutDirection,
    ): String {
        if (groupTitle == null) return screenTitle
        return when (layoutDirection) {
            SettingsLayoutDirection.Ltr -> "$screenTitle > $groupTitle"
            SettingsLayoutDirection.Rtl -> "$groupTitle < $screenTitle"
        }
    }

    private data class Candidate(
        val entry: SearchablePreference.Entry,
        val groupTitle: String?,
    )
}
