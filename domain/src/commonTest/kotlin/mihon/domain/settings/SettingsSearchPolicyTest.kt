package mihon.domain.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsSearchPolicyTest {

    @Test
    fun `fixed main searches localized title and summary ignoring case in production registration order`() {
        val routes = listOf(
            "appearance",
            "library",
            "reader",
            "download",
            "tracking",
            "browse",
            "data",
            "security",
            "advanced",
        )
        val screens = routes.mapIndexed { index, route ->
            screen(
                route = route,
                title = "Screen $index",
                preferences = listOf(
                    entry(
                        title = if (index % 2 == 0) "MATCH $route" else route,
                        summary = if (index % 2 == 0) null else "localized match summary",
                    ),
                ),
            )
        }

        val results = SettingsSearchPolicy.search(screens, query = "match")

        assertEquals(routes, results.map { it.route })
        assertEquals(screens.map { it.preferences.single().title }, results.map { it.title })
    }

    @Test
    fun `fixed main excludes disabled blank info and disabled or blank groups`() {
        val results = SettingsSearchPolicy.search(
            screens = listOf(
                screen(
                    route = "appearance",
                    title = "Appearance",
                    preferences = listOf(
                        entry("visible match"),
                        entry("disabled match", enabled = false),
                        entry("  ", summary = "match"),
                        entry("info match", type = SearchablePreference.EntryType.Info),
                        group("Enabled", entry("group match")),
                        group("Disabled", entry("hidden match"), enabled = false),
                        group("  ", entry("blank group match")),
                        group("Info", entry("group info match", type = SearchablePreference.EntryType.Info)),
                        group("Children", entry("disabled child match", enabled = false)),
                    ),
                ),
            ),
            query = "match",
        )

        assertEquals(listOf("visible match", "group match"), results.map { it.title })
    }

    @Test
    fun `fixed main caps results at ten after preserving screen and preference order`() {
        val screens = listOf(
            screen("first", "First", (1..7).map { entry("match $it") }),
            screen("second", "Second", (8..14).map { entry("match $it") }),
        )

        val results = SettingsSearchPolicy.search(screens, query = "match")

        assertEquals((1..10).map { "match $it" }, results.map { it.title })
    }

    @Test
    fun `fixed main formats group breadcrumbs for LTR and RTL`() {
        val screens = listOf(
            screen(
                route = "reader",
                title = "Reader",
                preferences = listOf(entry("match direct"), group("Navigation", entry("match grouped"))),
            ),
        )

        val ltr = SettingsSearchPolicy.search(screens, "match", SettingsLayoutDirection.Ltr)
        val rtl = SettingsSearchPolicy.search(screens, "match", SettingsLayoutDirection.Rtl)

        assertEquals(listOf("Reader", "Reader > Navigation"), ltr.map { it.breadcrumb })
        assertEquals(listOf("Reader", "Navigation < Reader"), rtl.map { it.breadcrumb })
    }

    @Test
    fun `fixed main duplicate titles retain original title anchor without occurrence identity`() {
        val results = SettingsSearchPolicy.search(
            screens = listOf(screen("library", "Library", listOf(entry("Duplicates"), entry("Duplicates")))),
            query = "duplicates",
        )

        assertEquals(2, results.size)
        assertEquals(listOf("Duplicates", "Duplicates"), results.map { it.anchorTitle })
        assertEquals(results.first(), results.last())
    }

    @Test
    fun `fixed main has no keyword screen group matching or relevance ranking boundary`() {
        val screens = listOf(
            screen(
                route = "advanced",
                title = "match screen",
                preferences = listOf(
                    group("match group", entry("unrelated")),
                    entry("first", summary = "match in summary"),
                    entry("match", summary = "exact title appears later"),
                ),
            ),
        )

        val results = SettingsSearchPolicy.search(screens, query = "match")

        assertEquals(listOf("first", "match"), results.map { it.title })
        assertTrue(SettingsSearchPolicy.search(screens, query = "screen").isEmpty())
        assertTrue(SettingsSearchPolicy.search(screens, query = "group").isEmpty())
    }

    @Test
    fun `fixed main empty search key returns no results while whitespace still uses contains`() {
        val screens = listOf(screen("appearance", "Appearance", listOf(entry("contains space"))))

        assertTrue(SettingsSearchPolicy.search(screens, query = "").isEmpty())
        assertEquals(listOf("contains space"), SettingsSearchPolicy.search(screens, query = " ").map { it.title })
    }

    private fun screen(
        route: String,
        title: String,
        preferences: List<SearchablePreference>,
    ) = SearchableSettingsScreen(route, title, preferences)

    private fun entry(
        title: String,
        summary: String? = null,
        enabled: Boolean = true,
        type: SearchablePreference.EntryType = SearchablePreference.EntryType.Standard,
    ) = SearchablePreference.Entry(title, summary, enabled, type)

    private fun group(
        title: String,
        vararg entries: SearchablePreference.Entry,
        enabled: Boolean = true,
    ) = SearchablePreference.Group(title, entries.toList(), enabled)
}
