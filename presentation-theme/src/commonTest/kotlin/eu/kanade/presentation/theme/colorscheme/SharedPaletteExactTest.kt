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
    fun `lavender palette matches fixed main tokens`() {
        assertEquals(
            "ffa177ff,ff3d0090,ffa177ff,ffffffff,ff6d41c8,ffa177ff,ffffffff,ff423271," +
                "ffa177ff,ffcdbdff,ff360096,ff5512d8,ffefe6ff,ff111129,ffe7e0ec,ff111129," +
                "ffe7e0ec,ff3d2f6b,ffcbc3d6,ffa177ff,ffe7e0ec,ff322f38,ffffb4ab,ff690005," +
                "ff93000a,ffffdad6,ff958e9f,ff4a4453,ff000000,ff111129,ff3b3841,ff15132d," +
                "ff171531,ff1d193b,ff241f41,ff282446",
            LavenderColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff6d41c8,ffffffff,ff7b46af,ff130038,ffa177ff,ff7b46af,ffede2ff,ffc9b0e6," +
                "ff7b46af,ffede2ff,ff7b46af,ff6d3bf0,ffffffff,ffede2ff,ff1d1a22,ffede2ff," +
                "ff1d1a22,ffe4d5f8,ff4a4453,ff6d41c8,ff322f38,fff5eefa,ffba1a1a,ffffffff," +
                "ffffdad6,ff410002,ff7b7485,ffcbc3d6,ff000000,ffded7e3,ffede2ff,ffdaccec," +
                "ffded0f1,ffe4d5f8,ffeadcfd,ffeee2ff",
            LavenderColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `yotsuba palette matches fixed main tokens`() {
        assertEquals(
            "ffffb59d,ff5f1600,ff862200,ffffdbcf,ffae3200,ffffb59d,ff5f1600,ff862200," +
                "ffffdbcf,ffd7c68d,ff3a2f05,ff524619,fff5e2a7,ff211a18,ffede0dd,ff211a18," +
                "ffede0dd,ff332723,ffd8c2bc,ffffb59d,ffede0dd,ff211a18,fff2b8b5,ff601410," +
                "ff8c1d18,fff9dedc,ffa08c87,ff49454f,ff000000,ff141218,ff3b383e,ff2e221f," +
                "ff312521,ff332723,ff413531,ff4c403d",
            YotsubaColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ffae3200,ffffffff,ffffdbcf,ff3b0a00,ffffb59d,ffae3200,ffffffff,ffebcdc2," +
                "ff3b0a00,ff6b5e2f,ffffffff,fff5e2a7,ff231b00,fffcfcfc,ff211a18,fffcfcfc," +
                "ff211a18,fff6ebe7,ff53433f,ffae3200,ff362f2d,fffbeeeb,ffb3261e,ffffffff," +
                "fff9dedc,ff410e0b,ff85736e,ffcac4d0,ff000000,ffded8e1,fffef7ff,ffece3e0," +
                "fff1e7e4,fff6ebe7,fffaf4f2,fffbf6f4",
            YotsubaColorScheme.lightScheme.exactTokenSnapshot(),
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
