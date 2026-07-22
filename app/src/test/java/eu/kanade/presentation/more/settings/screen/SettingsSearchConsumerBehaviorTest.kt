package eu.kanade.presentation.more.settings.screen

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.consumeSettingsHighlight
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import mihon.domain.settings.SearchablePreference
import mihon.domain.settings.SettingsLayoutDirection
import mihon.domain.settings.SettingsSearchPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SettingsSearchConsumerBehaviorTest {

    @AfterEach
    fun tearDown() {
        SearchableSettings.highlightKey = null
        unmockkObject(SettingsSearchPolicy)
    }

    @Test
    fun `real Android preference projection is searched by shared policy`() {
        val preferences = listOf(
            Preference.PreferenceGroup(
                title = "Display",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference("first", subtitle = "LOCALIZED MATCH summary"),
                    Preference.PreferenceItem.InfoPreference("match info"),
                ),
            ),
            Preference.PreferenceItem.TextPreference("match direct"),
        )
        val screen = SettingsAppearanceScreen.toSearchableSettingsScreen("Appearance", preferences)
        val group = screen.preferences.first() as SearchablePreference.Group
        assertEquals(SearchablePreference.EntryType.Info, group.entries.last().type)
        assertSame(SettingsAppearanceScreen, screen.route)

        mockkObject(SettingsSearchPolicy)
        every {
            SettingsSearchPolicy.search<Screen>(any(), "match", SettingsLayoutDirection.Ltr)
        } answers { callOriginal() }

        val results = searchSettings(listOf(screen), "match", SettingsLayoutDirection.Ltr)

        assertEquals(listOf("first", "match direct"), results.map { it.title })
        assertEquals(listOf("Appearance > Display", "Appearance"), results.map { it.breadcrumb })
        verify(exactly = 1) {
            SettingsSearchPolicy.search<Screen>(listOf(screen), "match", SettingsLayoutDirection.Ltr)
        }
    }

    @Test
    fun `highlight consume scrolls to first duplicate then clears exactly once`() = runTest {
        val preferences = listOf(
            Preference.PreferenceGroup(
                title = "Group",
                preferenceItems = persistentListOf(Preference.PreferenceItem.TextPreference("Duplicate")),
            ),
            Preference.PreferenceItem.TextPreference("Duplicate"),
        )
        val scrolled = mutableListOf<Int>()
        SearchableSettings.highlightKey = "Duplicate"

        consumeSettingsHighlight(preferences, scrolled::add)
        consumeSettingsHighlight(preferences, scrolled::add)

        assertEquals(listOf(1), scrolled)
        assertNull(SearchableSettings.highlightKey)
    }

    @Test
    fun `settings search destination is a Voyager screen`() {
        assertInstanceOf(Screen::class.java, SettingsSearchScreen())
    }
}
