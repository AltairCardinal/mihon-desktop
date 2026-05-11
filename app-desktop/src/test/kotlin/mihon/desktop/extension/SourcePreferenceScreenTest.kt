package mihon.desktop.extension

import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.preference.CheckBoxPreference
import eu.kanade.tachiyomi.source.preference.EditTextPreference
import eu.kanade.tachiyomi.source.preference.ListPreference
import eu.kanade.tachiyomi.source.preference.MultiSelectListPreference
import eu.kanade.tachiyomi.source.preference.PreferenceCategoryItem
import eu.kanade.tachiyomi.source.preference.SwitchPreference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourcePreferenceScreenTest {

    @Test
    fun `PreferenceScreen can be instantiated with no items`() {
        val screen = PreferenceScreen()
        assertTrue(screen.preferences.isEmpty())
    }

    @Test
    fun `PreferenceScreen collects switch preferences`() {
        val screen = PreferenceScreen()
        screen.addPreference(SwitchPreference(key = "k1", title = "Switch 1"))
        assertEquals(1, screen.preferences.size)
        val item = screen.preferences.first()
        assertInstanceOf(SwitchPreference::class.java, item)
        assertEquals("k1", item.key)
        assertEquals("Switch 1", item.title)
    }

    @Test
    fun `SwitchPreference default value is false`() {
        val pref = SwitchPreference(key = "k", title = "T")
        assertFalse(pref.defaultValue)
    }

    @Test
    fun `SwitchPreference accepts custom default value`() {
        val pref = SwitchPreference(key = "k", title = "T", defaultValue = true)
        assertTrue(pref.defaultValue)
    }

    @Test
    fun `PreferenceScreen collects list preferences`() {
        val screen = PreferenceScreen()
        val pref = ListPreference(
            key = "lang",
            title = "Language",
            entries = listOf("English", "Japanese"),
            entryValues = listOf("en", "ja"),
            defaultValue = "en",
        )
        screen.addPreference(pref)
        assertEquals(1, screen.preferences.size)
        val item = screen.preferences.first() as ListPreference
        assertEquals(2, item.entries.size)
        assertEquals("en", item.defaultValue)
    }

    @Test
    fun `PreferenceScreen collects edit text preferences`() {
        val screen = PreferenceScreen()
        screen.addPreference(EditTextPreference(key = "url", title = "Base URL"))
        assertEquals(1, screen.preferences.size)
        assertInstanceOf(EditTextPreference::class.java, screen.preferences.first())
    }

    @Test
    fun `PreferenceScreen collects checkbox preferences`() {
        val screen = PreferenceScreen()
        screen.addPreference(CheckBoxPreference(key = "cb", title = "Checkbox"))
        assertEquals(1, screen.preferences.size)
        assertInstanceOf(CheckBoxPreference::class.java, screen.preferences.first())
    }

    @Test
    fun `PreferenceScreen collects multi-select list preferences`() {
        val screen = PreferenceScreen()
        screen.addPreference(
            MultiSelectListPreference(
                key = "langs",
                title = "Languages",
                entries = listOf("English", "Japanese"),
                entryValues = listOf("en", "ja"),
            ),
        )
        assertEquals(1, screen.preferences.size)
        assertInstanceOf(MultiSelectListPreference::class.java, screen.preferences.first())
    }

    @Test
    fun `PreferenceScreen collects multiple mixed preferences`() {
        val screen = PreferenceScreen()
        screen.addPreference(SwitchPreference(key = "a", title = "A"))
        screen.addPreference(EditTextPreference(key = "b", title = "B"))
        screen.addPreference(
            ListPreference(key = "c", title = "C", entries = listOf("X"), entryValues = listOf("x")),
        )
        assertEquals(3, screen.preferences.size)
    }

    @Test
    fun `ListPreference entries and entryValues default to empty`() {
        val pref = ListPreference(key = "k", title = "T")
        assertTrue(pref.entries.isEmpty())
        assertTrue(pref.entryValues.isEmpty())
    }

    @Test
    fun `SwitchPreference summary is null by default`() {
        val pref = SwitchPreference(key = "k", title = "T")
        assertEquals(null, pref.summary)
    }

    @Test
    fun `preference summary can be set`() {
        val pref = SwitchPreference(key = "k", title = "T")
        pref.summary = "Some hint"
        assertEquals("Some hint", pref.summary)
    }

    // PreferenceCategory tests

    @Test
    fun `PreferenceScreen collects PreferenceCategoryItem`() {
        val screen = PreferenceScreen()
        screen.addPreference(PreferenceCategoryItem(title = "Advanced"))
        assertEquals(1, screen.preferences.size)
        assertInstanceOf(PreferenceCategoryItem::class.java, screen.preferences.first())
    }

    @Test
    fun `PreferenceCategoryItem has correct title`() {
        val cat = PreferenceCategoryItem(title = "Section Title")
        assertEquals("Section Title", cat.title)
    }

    @Test
    fun `PreferenceCategoryItem key defaults to empty string`() {
        val cat = PreferenceCategoryItem(title = "Cat")
        assertEquals("", cat.key)
    }

    @Test
    fun `PreferenceCategoryItem summary is null by default`() {
        val cat = PreferenceCategoryItem(title = "Cat")
        assertNull(cat.summary)
    }

    @Test
    fun `PreferenceScreen maintains order with category and preferences mixed`() {
        val screen = PreferenceScreen()
        screen.addPreference(PreferenceCategoryItem(title = "Basic"))
        screen.addPreference(SwitchPreference(key = "s1", title = "Switch 1"))
        screen.addPreference(PreferenceCategoryItem(title = "Advanced"))
        screen.addPreference(EditTextPreference(key = "e1", title = "URL"))
        assertEquals(4, screen.preferences.size)
        assertInstanceOf(PreferenceCategoryItem::class.java, screen.preferences[0])
        assertInstanceOf(SwitchPreference::class.java, screen.preferences[1])
        assertInstanceOf(PreferenceCategoryItem::class.java, screen.preferences[2])
        assertInstanceOf(EditTextPreference::class.java, screen.preferences[3])
    }
}
