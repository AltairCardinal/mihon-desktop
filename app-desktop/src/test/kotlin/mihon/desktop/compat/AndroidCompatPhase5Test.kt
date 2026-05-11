package mihon.desktop.compat

import android.content.Context
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 5: androidx.preference.* stubs.
 * These allow Tachiyomi extensions to call `SwitchPreferenceCompat(context)` etc.
 */
class AndroidCompatPhase5Test {

    private val ctx = Context()

    // ── Preference base ─────────────────────────────────────────────────────

    @Test
    fun `Preference can be constructed with context`() {
        val pref = Preference(ctx)
        pref.shouldNotBeNull()
        pref.context shouldBe ctx
    }

    @Test
    fun `Preference key, title, summary round-trip`() {
        val pref = Preference(ctx)
        pref.key = "my_key"
        pref.title = "My Title"
        pref.summary = "My Summary"
        pref.key shouldBe "my_key"
        pref.title.toString() shouldBe "My Title"
        pref.summary.toString() shouldBe "My Summary"
    }

    @Test
    fun `Preference isVisible and isEnabled default to true`() {
        val pref = Preference(ctx)
        pref.isVisible shouldBe true
        pref.isEnabled shouldBe true
    }

    @Test
    fun `Preference onPreferenceChangeListener callback`() {
        val pref = Preference(ctx)
        var called = false
        pref.setOnPreferenceChangeListener { _, _ -> called = true; true }
        pref.callChangeListener("new_value") shouldBe true
        called shouldBe true
    }

    // ── SwitchPreferenceCompat ──────────────────────────────────────────────

    @Test
    fun `SwitchPreferenceCompat defaults to unchecked`() {
        val pref = SwitchPreferenceCompat(ctx)
        pref.isChecked shouldBe false
    }

    @Test
    fun `SwitchPreferenceCompat checked round-trip`() {
        val pref = SwitchPreferenceCompat(ctx)
        pref.isChecked = true
        pref.isChecked shouldBe true
    }

    @Test
    fun `SwitchPreferenceCompat extends TwoStatePreference`() {
        val pref = SwitchPreferenceCompat(ctx)
        pref.shouldBeInstanceOf<androidx.preference.TwoStatePreference>()
    }

    // ── CheckBoxPreference ──────────────────────────────────────────────────

    @Test
    fun `CheckBoxPreference works like SwitchPreference`() {
        val pref = CheckBoxPreference(ctx)
        pref.isChecked shouldBe false
        pref.isChecked = true
        pref.isChecked shouldBe true
    }

    // ── ListPreference ──────────────────────────────────────────────────────

    @Test
    fun `ListPreference entries and entryValues`() {
        val pref = ListPreference(ctx)
        pref.entries = arrayOf("English", "Japanese")
        pref.entryValues = arrayOf("en", "ja")
        pref.entries shouldBe arrayOf("English", "Japanese")
        pref.entryValues shouldBe arrayOf("en", "ja")
    }

    @Test
    fun `ListPreference value and summary`() {
        val pref = ListPreference(ctx)
        pref.entries = arrayOf("English", "Japanese")
        pref.entryValues = arrayOf("en", "ja")
        pref.value = "ja"
        pref.value shouldBe "ja"
    }

    // ── EditTextPreference ──────────────────────────────────────────────────

    @Test
    fun `EditTextPreference text round-trip`() {
        val pref = EditTextPreference(ctx)
        pref.text = "hello"
        pref.text shouldBe "hello"
    }

    // ── MultiSelectListPreference ───────────────────────────────────────────

    @Test
    fun `MultiSelectListPreference values round-trip`() {
        val pref = MultiSelectListPreference(ctx)
        pref.entries = arrayOf("A", "B", "C")
        pref.entryValues = arrayOf("a", "b", "c")
        pref.values = setOf("a", "c")
        pref.values shouldBe setOf("a", "c")
    }

    // ── PreferenceCategory ──────────────────────────────────────────────────

    @Test
    fun `PreferenceCategory can hold sub-preferences`() {
        val category = PreferenceCategory(ctx)
        category.title = "General"
        val pref1 = SwitchPreferenceCompat(ctx).apply { key = "p1" }
        val pref2 = EditTextPreference(ctx).apply { key = "p2" }
        category.addPreference(pref1)
        category.addPreference(pref2)
        category.preferences shouldHaveSize 2
    }

    // ── PreferenceScreen ────────────────────────────────────────────────────

    @Test
    fun `PreferenceScreen collects preferences`() {
        val screen = PreferenceScreen(ctx)
        screen.addPreference(SwitchPreferenceCompat(ctx).apply { key = "s1" })
        screen.addPreference(ListPreference(ctx).apply { key = "l1" })
        screen.preferenceCount shouldBe 2
    }
}
