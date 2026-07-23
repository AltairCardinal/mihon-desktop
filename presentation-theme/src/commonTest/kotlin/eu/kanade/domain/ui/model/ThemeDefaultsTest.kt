package eu.kanade.domain.ui.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThemeDefaultsTest {

    @Test
    fun `theme identities match fixed main`() {
        assertEquals("LIGHT,DARK,SYSTEM", ThemeMode.entries.joinToString(",") { it.name })
        assertEquals(
            "DEFAULT,MONET,CATPPUCCIN,GREEN_APPLE,LAVENDER,MIDNIGHT_DUSK,NORD," +
                "STRAWBERRY_DAIQUIRI,TAKO,TEALTURQUOISE,TIDAL_WAVE,YINYANG,YOTSUBA," +
                "MONOCHROME,DARK_BLUE,HOT_PINK,BLUE",
            AppTheme.entries.joinToString(",") { it.name },
        )
    }

    @Test
    fun `canonical preference keys match fixed main`() {
        assertEquals("pref_theme_mode_key", ThemeDefaults.THEME_MODE_KEY)
        assertEquals("pref_app_theme", ThemeDefaults.APP_THEME_KEY)
    }

    @Test
    fun `theme mode default follows system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeDefaults.themeMode)
    }

    @Test
    fun `app theme default follows dynamic color capability`() {
        assertEquals(AppTheme.MONET, ThemeDefaults.appTheme(dynamicColorAvailable = true))
        assertEquals(AppTheme.DEFAULT, ThemeDefaults.appTheme(dynamicColorAvailable = false))
    }

    @Test
    fun `codec preserves canonical names`() {
        assertEquals("DARK", ThemePreferenceCodec.encode(ThemeMode.DARK))
        assertEquals("YOTSUBA", ThemePreferenceCodec.encode(AppTheme.YOTSUBA))
        assertEquals(ThemeMode.DARK, ThemePreferenceCodec.decodeThemeMode("DARK"))
        assertEquals(AppTheme.YOTSUBA, ThemePreferenceCodec.decodeAppTheme("YOTSUBA", false))
    }

    @Test
    fun `codec falls back on unknown values`() {
        assertEquals(ThemeMode.SYSTEM, ThemePreferenceCodec.decodeThemeMode("future-mode"))
        assertEquals(AppTheme.MONET, ThemePreferenceCodec.decodeAppTheme("future-theme", true))
        assertEquals(AppTheme.DEFAULT, ThemePreferenceCodec.decodeAppTheme("future-theme", false))
    }

    @Test
    fun `deprecated themes remain readable but never appear in picker`() {
        assertEquals(AppTheme.DARK_BLUE, ThemePreferenceCodec.decodeAppTheme("DARK_BLUE", false))

        val themesWithDynamicColor = selectableAppThemes(dynamicColorAvailable = true)
        assertContains(themesWithDynamicColor, AppTheme.MONET)
        assertFalse(themesWithDynamicColor.any { it.titleRes == null })

        val themesWithoutDynamicColor = selectableAppThemes(dynamicColorAvailable = false)
        assertFalse(AppTheme.MONET in themesWithoutDynamicColor)
        assertFalse(themesWithoutDynamicColor.any { it.titleRes == null })
    }
}
