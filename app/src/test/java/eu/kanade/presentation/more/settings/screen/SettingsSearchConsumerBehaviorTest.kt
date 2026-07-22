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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SettingsSearchConsumerBehaviorTest {
    @AfterEach
    fun tearDown() = unmockkObject(SettingsSearchPolicy).also { SearchableSettings.highlightKey = null }

    @Test
    fun `real Android preference projection is searched by shared policy`() {
        val first = Preference.PreferenceItem.TextPreference("first", subtitle = "LOCALIZED MATCH summary")
        val info = Preference.PreferenceItem.InfoPreference("match info")
        val group = Preference.PreferenceGroup("Display", preferenceItems = persistentListOf(first, info))
        val preferences = listOf(group, Preference.PreferenceItem.TextPreference("match direct"))
        val screen = SettingsAppearanceScreen.toSearchableSettingsScreen("Appearance", preferences)
        val sharedGroup = screen.preferences.first() as SearchablePreference.Group
        assertEquals(SearchablePreference.EntryType.Info, sharedGroup.entries.last().type)
        assertSame(SettingsAppearanceScreen, screen.route)

        mockkObject(SettingsSearchPolicy)
        val direction = SettingsLayoutDirection.Ltr
        every { SettingsSearchPolicy.search<Screen>(any(), "match", direction) } answers { callOriginal() }
        val results = searchSettings(listOf(screen), "match", direction)
        assertEquals(listOf("first", "match direct"), results.map { it.title })
        assertEquals(listOf("Appearance > Display", "Appearance"), results.map { it.breadcrumb })
        verify(exactly = 1) { SettingsSearchPolicy.search<Screen>(listOf(screen), "match", direction) }
    }

    @Test
    fun `highlight consume scrolls to first duplicate then clears exactly once`() = runTest {
        val duplicate = Preference.PreferenceItem.TextPreference("Duplicate")
        val preferences =
            listOf(Preference.PreferenceGroup("Group", preferenceItems = persistentListOf(duplicate)), duplicate)
        val scrolled = mutableListOf<Int>()
        SearchableSettings.highlightKey = "Duplicate"
        consumeSettingsHighlight(preferences, scrolled::add)
        consumeSettingsHighlight(preferences, scrolled::add)
        assertEquals(listOf(1), scrolled)
        assertEquals(null, SearchableSettings.highlightKey)
    }
}
