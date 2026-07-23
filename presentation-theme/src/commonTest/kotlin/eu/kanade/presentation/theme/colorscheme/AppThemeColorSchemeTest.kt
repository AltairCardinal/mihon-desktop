package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemePreferenceCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppThemeColorSchemeTest {

    @Test
    fun `every static app theme selects its fixed main palette`() {
        val palettes = mapOf(
            AppTheme.DEFAULT to TachiyomiColorScheme,
            AppTheme.CATPPUCCIN to CatppuccinColorScheme,
            AppTheme.GREEN_APPLE to GreenAppleColorScheme,
            AppTheme.LAVENDER to LavenderColorScheme,
            AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
            AppTheme.MONOCHROME to MonochromeColorScheme,
            AppTheme.NORD to NordColorScheme,
            AppTheme.STRAWBERRY_DAIQUIRI to StrawberryColorScheme,
            AppTheme.TAKO to TakoColorScheme,
            AppTheme.TEALTURQUOISE to TealTurqoiseColorScheme,
            AppTheme.TIDAL_WAVE to TidalWaveColorScheme,
            AppTheme.YINYANG to YinYangColorScheme,
            AppTheme.YOTSUBA to YotsubaColorScheme,
        )

        palettes.forEach { (theme, palette) ->
            assertSame(
                palette.lightScheme,
                AppThemeColorScheme.colorScheme(theme, isDark = false, isAmoled = false),
                "$theme light palette",
            )
            assertSame(
                palette.darkScheme,
                AppThemeColorScheme.colorScheme(theme, isDark = true, isAmoled = false),
                "$theme dark palette",
            )
        }
    }

    @Test
    fun `deprecated and unknown app themes safely fall back to fixed main default`() {
        listOf(AppTheme.DARK_BLUE, AppTheme.HOT_PINK, AppTheme.BLUE).forEach { theme ->
            assertSame(
                TachiyomiColorScheme.lightScheme,
                AppThemeColorScheme.colorScheme(theme, isDark = false, isAmoled = false),
            )
            assertSame(
                TachiyomiColorScheme.darkScheme,
                AppThemeColorScheme.colorScheme(theme, isDark = true, isAmoled = false),
            )
        }

        val unknownWithoutDynamicColor = ThemePreferenceCodec.decodeAppTheme("FUTURE_THEME", false)
        assertSame(
            TachiyomiColorScheme.lightScheme,
            AppThemeColorScheme.colorScheme(unknownWithoutDynamicColor, isDark = false, isAmoled = false),
        )
        val unknownWithDynamicColor = ThemePreferenceCodec.decodeAppTheme("FUTURE_THEME", true)
        assertSame(
            TachiyomiColorScheme.darkScheme,
            AppThemeColorScheme.colorScheme(unknownWithDynamicColor, isDark = true, isAmoled = false),
        )
    }

    @Test
    fun `amoled only changes dark static colors and preserves fixed containers`() {
        assertSame(
            YinYangColorScheme.lightScheme,
            AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = false, isAmoled = true),
        )

        val amoled = AppThemeColorScheme.colorScheme(
            AppTheme.YINYANG,
            isDark = true,
            isAmoled = true,
        )
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.White, amoled.onBackground)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.White, amoled.onSurface)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceVariant)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceContainerLowest)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceContainerLow)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceContainer)
        assertEquals(Color(0xFF131313), amoled.surfaceContainerHigh)
        assertEquals(Color(0xFF1B1B1B), amoled.surfaceContainerHighest)
    }

    @Test
    fun `monet is injected and amoled preserves its dynamic surface containers`() {
        val dynamic = object : BaseColorScheme() {
            override val lightScheme = lightColorScheme(primary = Color.Red)
            override val darkScheme = darkColorScheme(
                primary = Color.Green,
                surfaceContainer = Color.Blue,
            )
        }

        assertSame(
            dynamic.lightScheme,
            AppThemeColorScheme.colorScheme(
                AppTheme.MONET,
                isDark = false,
                isAmoled = true,
                monetColorScheme = dynamic,
            ),
        )
        val amoled = AppThemeColorScheme.colorScheme(
            AppTheme.MONET,
            isDark = true,
            isAmoled = true,
            monetColorScheme = dynamic,
        )
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.White, amoled.onBackground)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.White, amoled.onSurface)
        assertEquals(dynamic.darkScheme.surfaceContainer, amoled.surfaceContainer)
    }

    @Test
    fun `monet without adapter applies static fallback amoled containers`() {
        val amoled = AppThemeColorScheme.colorScheme(
            AppTheme.MONET,
            isDark = true,
            isAmoled = true,
            monetColorScheme = null,
        )

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.White, amoled.onBackground)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.White, amoled.onSurface)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceVariant)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceContainerLowest)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceContainerLow)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceContainer)
        assertEquals(Color(0xFF131313), amoled.surfaceContainerHigh)
        assertEquals(Color(0xFF1B1B1B), amoled.surfaceContainerHighest)
    }
}
