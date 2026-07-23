package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SharedPaletteExactTest {

    @Test
    fun `tachiyomi palette matches fixed main tokens`() {
        assertEquals(
            "ffb0c6ff,ff002d6e,ff00429b,ffd9e2ff,ff0058ca,ffb0c6ff,ff002d6e,ff00429b," +
                "ffd9e2ff,ff7adc77,ff003909,ff005312,ff95f990,ff1b1b1f,ffe3e2e6,ff1b1b1f," +
                "ffe3e2e6,ff211f26,ffc5c6d0,ffb0c6ff,ffe3e2e6,ff1b1b1f,ffffb4ab,ff690005," +
                "ff93000a,ffffdad6,ff8f9099,ff44464f,ff000000,ff141218,ff3b383e,ff1a181d," +
                "ff1e1c22,ff211f26,ff292730,ff302e38",
            TachiyomiColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff0058ca,ffffffff,ffd9e2ff,ff001945,ffb0c6ff,ff0058ca,ffffffff,ffd9e2ff," +
                "ff001945,ff006e1b,ffffffff,ff95f990,ff002203,fffefbff,ff1b1b1f,fffefbff," +
                "ff1b1b1f,fff3edf7,ff44464f,ff0058ca,ff303034,fff2f0f4,ffba1a1a,ffffffff," +
                "ffffdad6,ff410002,ff757780,ffc5c6d0,ff000000,ffded8e1,fffef7ff,fff5f1f8," +
                "fff7f2fa,fff3edf7,fffcf7ff,fffcf7ff",
            TachiyomiColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `green apple palette matches fixed main tokens`() {
        assertEquals(
            "ff7adb8f,ff003917,ff017737,ffffffff,ff006d32,ff7adb8f,ff003917,ff017737," +
                "ffffffff,ffffb3ac,ff680008,ffc7282a,ffffffff,ff0f1510,ffdfe4db,ff0f1510," +
                "ffdfe4db,ff3f493f,ffbecabc,ff7adb8f,ffdfe4db,ff2c322c,ffffb4ab,ff690005," +
                "ff93000a,ffffdad6,ff889487,ff3f493f,ff000000,ff0f1510,ff353b35,ff0a0f0b," +
                "ff181d18,ff1c211c,ff262b26,ff313630",
            GreenAppleColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff005927,ffffffff,ff188140,ffffffff,ff7adb8f,ff005927,ffffffff,ff97f7a9," +
                "ff000000,ff9d0012,ffffffff,ffd33131,ffffffff,fff6fbf2,ff181d18,fff6fbf2," +
                "ff181d18,ffdae6d7,ff3f493f,ff005927,ff2c322c,ffedf2e9,ffba1a1a,ffffffff," +
                "ffffdad6,ff410002,ff6f7a6e,ffbecabc,ff000000,ffd6dcd3,fff6fbf2,ffffffff," +
                "fff0f5ec,ffeaefe6,ffe4eae1,ffdfe4db",
            GreenAppleColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `base color scheme preserves light dark and amoled behavior`() {
        assertSame(
            TachiyomiColorScheme.lightScheme,
            TachiyomiColorScheme.getColorScheme(
                isDark = false,
                isAmoled = true,
                overrideDarkSurfaceContainers = true,
            ),
        )
        assertSame(
            TachiyomiColorScheme.darkScheme,
            TachiyomiColorScheme.getColorScheme(
                isDark = true,
                isAmoled = false,
                overrideDarkSurfaceContainers = true,
            ),
        )

        val amoled = TachiyomiColorScheme.getColorScheme(
            isDark = true,
            isAmoled = true,
            overrideDarkSurfaceContainers = true,
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

        val amoledWithoutContainerOverride = TachiyomiColorScheme.getColorScheme(
            isDark = true,
            isAmoled = true,
            overrideDarkSurfaceContainers = false,
        )
        assertEquals(
            TachiyomiColorScheme.darkScheme.surfaceContainer,
            amoledWithoutContainerOverride.surfaceContainer,
        )
    }
}

private fun ColorScheme.exactTokenSnapshot(): String = listOf(
    primary,
    onPrimary,
    primaryContainer,
    onPrimaryContainer,
    inversePrimary,
    secondary,
    onSecondary,
    secondaryContainer,
    onSecondaryContainer,
    tertiary,
    onTertiary,
    tertiaryContainer,
    onTertiaryContainer,
    background,
    onBackground,
    surface,
    onSurface,
    surfaceVariant,
    onSurfaceVariant,
    surfaceTint,
    inverseSurface,
    inverseOnSurface,
    error,
    onError,
    errorContainer,
    onErrorContainer,
    outline,
    outlineVariant,
    scrim,
    surfaceDim,
    surfaceBright,
    surfaceContainerLowest,
    surfaceContainerLow,
    surfaceContainer,
    surfaceContainerHigh,
    surfaceContainerHighest,
).joinToString(",") { color ->
    color.toArgb().toUInt().toString(radix = 16).padStart(length = 8, padChar = '0')
}
