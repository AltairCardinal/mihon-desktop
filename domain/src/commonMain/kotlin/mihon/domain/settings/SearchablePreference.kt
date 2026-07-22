package mihon.domain.settings

sealed interface SearchablePreference {
    val title: String
    val enabled: Boolean

    data class Entry(
        override val title: String,
        val summary: String? = null,
        override val enabled: Boolean = true,
        val type: EntryType = EntryType.Standard,
    ) : SearchablePreference

    data class Group(
        override val title: String,
        val entries: List<Entry>,
        override val enabled: Boolean = true,
    ) : SearchablePreference

    enum class EntryType {
        Standard,
        Info,
    }
}

data class SearchableSettingsScreen<RouteToken>(
    val route: RouteToken,
    val title: String,
    val preferences: List<SearchablePreference>,
)

data class SettingsSearchResult<RouteToken>(
    val route: RouteToken,
    val title: String,
    val breadcrumb: String,
    val anchorTitle: String,
)

enum class SettingsLayoutDirection {
    Ltr,
    Rtl,
}
