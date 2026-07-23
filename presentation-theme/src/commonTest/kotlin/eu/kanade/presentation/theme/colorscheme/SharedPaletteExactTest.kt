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
    fun `catppuccin palette matches fixed main tokens`() {
        assertEquals(
            "ffcba6f7,ff11111b,ffcba6f7,ff11111b,ff8839ef,ffcba6f7,ff11111b,ff313244," +
                "ffcba6f7,ffcba6f7,ff11111b,ff1e1e2e,ffcdd6f4,ff181825,ffcdd6f4,ff181825," +
                "ffcdd6f4,ff1e1e2e,ffcdd6f4,ffcba6f7,ffeff1f5,ff4c4f69,fff38ba8,ff11111b," +
                "ffff0558,ffef9fb4,ffcba6f7,ff585b70,ff11111b,ff181825,ff313244,ff181825," +
                "ff1e1e2e,ff1e1e2e,ff1e1e2e,ff313244",
            CatppuccinColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff8839ef,ffdce0e8,ff8839ef,ffdce0e8,ffcba6f7,ff8839ef,ffdce0e8,ffcdd0da," +
                "ff8839ef,ff8839ef,ffdce0e8,ffeff1f5,ff4c4f69,ffe6e9ef,ff4c4f69,ffe6e9ef," +
                "ff4c4f69,ffeff1f5,ff4c4f69,ff8839ef,ff1e1e2e,ffcdd6f4,ffd20f39,ffdce0e8," +
                "ff68001c,ffd61c41,ff8839ef,ffacb0be,ffdce0e8,ffe6e9ef,ffcdd0da,ffe6e9ef," +
                "ffeff1f5,ffeff1f5,ffeff1f5,ffcdd0da",
            CatppuccinColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `midnight dusk palette matches fixed main tokens`() {
        assertEquals(
            "fff02475,ffffffff,ffbd1c5c,ffffffff,fff02475,fff02475,ff16151d,ff66183c," +
                "fff02475,ff55971c,ff16151d,ff386412,ffe5e1e5,ff16151d,ffe5e1e5,ff16151d," +
                "ffe5e1e5,ff281624,ffd6c1c4,fff02475,ff333043,ffffffff,fff2b8b5,ff601410," +
                "ff8c1d18,fff9dedc,ff9f8c8f,ff49454f,ff000000,ff141218,ff3b383e,ff221320," +
                "ff251522,ff281624,ff2d1c2a,ff2f1f2c",
            MidnightDuskColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ffbb0054,ffffffff,ffffd9e1,ff3f0017,ffffb1c4,ffbb0054,ffffffff,ffefbad4," +
                "ffd1377c,ff006638,ffffffff,ff00894b,ff2d1600,fffffbff,ff1c1b1f,fffffbff," +
                "ff1c1b1f,fff9e6f1,ff524346,ffbb0054,ff313033,fff4f0f4,ffb3261e,ffffffff," +
                "fff9dedc,ff410e0b,ff847376,ffcac4d0,ff000000,ffded8e1,fffef7ff,ffdac0cd," +
                "ffe8d1dd,fff9e6f1,fffcf3f8,fffef9fc",
            MidnightDuskColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `monochrome palette matches fixed main tokens`() {
        assertEquals(
            "ffffffff,ff000000,ffffffff,ff000000,ff000000,ffffffff,ff000000,ff777777," +
                "ff000000,ff777777,ffffffff,ffffffff,ff000000,ff000000,ffffffff,ff000000," +
                "ffffffff,ff000000,ffffffff,ffffffff,ffffffff,ff000000,ffffffff,ff000000," +
                "ffffffff,ff000000,ffffffff,ffffffff,ff000000,ff000000,ffffffff,ff000000," +
                "ff000000,ff000000,ff000000,ff000000",
            MonochromeColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff000000,ffffffff,ff000000,ffffffff,ffffffff,ff000000,ffffffff,ff888888," +
                "ffffffff,ff888888,ffffffff,ff000000,ffffffff,ffffffff,ff000000,ffffffff," +
                "ff000000,ffffffff,ff000000,ff000000,ff000000,ffffffff,ff000000,ffffffff," +
                "ff000000,ffffffff,ff000000,ff000000,ff000000,ffffffff,ffffffff,ffffffff," +
                "ffffffff,ffffffff,ffffffff,ffffffff",
            MonochromeColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `nord palette matches fixed main tokens`() {
        assertEquals(
            "ff88c0d0,ff2e3440,ff88c0d0,ff2e3440,ff397e91,ff81a1c1,ff2e3440,ff506275," +
                "ff88c0d0,ff5e81ac,ff000000,ff5e81ac,ff000000,ff2e3440,ffeceff4,ff2e3440," +
                "ffeceff4,ff414c5c,ffeceff4,ff88c0d0,ffd8dee9,ff2e3440,fff2b8b5,ff2e3440," +
                "ffbf616a,ff000000,ff6d717b,ff90939a,ff000000,ff141218,ff3b383e,ff373f4d," +
                "ff3e4756,ff414c5c,ff4e5766,ff505968",
            NordColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff5e81ac,ff000000,ff5e81ac,ff000000,ff8ca8cd,ff81a1c1,ff2e3440,ff91b4d7," +
                "ff2e3440,ff88c0d0,ff2e3440,ff88c0d0,ff2e3440,ffeceff4,ff2e3440,ffe5e9f0," +
                "ff2e3440,ffdae0ea,ff2e3440,ff5e81ac,ff3b4252,ffeceff4,ffb3261e,ffeceff4," +
                "ffbf616a,ff000000,ff2e3440,ffcac4d0,ff000000,ffded8e1,fffef7ff,ffd1d7e0," +
                "ffd6dce6,ffdae0ea,ffe9edf3,fff2f4f8",
            NordColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `strawberry palette matches fixed main tokens`() {
        assertEquals(
            "ffffb2b8,ff67001d,ffd53855,ffffffff,ffb61f40,ffed4a65,ff201a1a,ff91002a," +
                "ffffffff,ffe8c08e,ff201a1a,ff775930,fffff7f1,ff201a1a,fff7dcdd,ff201a1a," +
                "fff7dcdd,ff322727,ffe1bec0,ffffb2b8,fff7dcdd,ff3d2c2d,ffffb4ab,ff690005," +
                "ff93000a,ffffdad6,ffa9898b,ff594042,ff000000,ff1d1011,ff463536,ff2c2222," +
                "ff302525,ff322727,ff3c2f2f,ff463737",
            StrawberryColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ffa10833,ffffffff,ffd53855,ffffffff,ffffb2b8,ffa10833,ffffffff,ffd53855," +
                "fff6eaed,ff5f441d,ffffffff,ff87683d,ffffffff,fffafafa,ff261819,fffafafa," +
                "ff261819,fff6eaed,ff594042,ffa10833,ff3d2c2d,ffffeced,ffba1a1a,ffffffff," +
                "ffffdad6,ff410002,ff8d7071,ffe1bec0,ff000000,ffeed4d5,fffff8f7,fff7dcdd," +
                "fffde2e3,fff6eaed,fffff0f0,ffffffff",
            StrawberryColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `tako palette matches fixed main tokens`() {
        assertEquals(
            "fff3b375,ff38294e,fff3b375,ff38294e,ff84531e,fff3b375,ff38294e,ff5c4d4b," +
                "fff3b375,ff66577e,fff3b375,ff4e4065,ffeddcff,ff21212e,ffe3e0f2,ff21212e," +
                "ffe3e0f2,ff2a2a3c,ffcbc4ce,ff66577e,ffe5e1e6,ff1b1b1e,fff2b8b5,ff601410," +
                "ff8c1d18,fff9dedc,ff958f99,ff49454f,ff000000,ff141218,ff3b383e,ff20202e," +
                "ff262636,ff2a2a3c,ff303044,ff36364d",
            TakoColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff66577e,fff3b375,ff66577e,fff3b375,ffd6baff,ff66577e,fff3b375,ffc8bed0," +
                "ff66577e,fff3b375,ff574360,fffdd6b0,ff221437,fff7f5ff,ff1b1b22,fff7f5ff," +
                "ff1b1b22,ffe8e0eb,ff49454e,ff66577e,ff313033,fff3eff4,ffb3261e,ffffffff," +
                "fff9dedc,ff410e0b,ff7a757e,ffcac4d0,ff000000,ffded8e1,fffef7ff,ffd7d0da," +
                "ffdfd8e2,ffe8e0eb,ffeee6f1,fff7eefa",
            TakoColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `teal turqoise palette matches fixed main tokens`() {
        assertEquals(
            "ff40e0d0,ff000000,ff40e0d0,ff000000,ff008080,ff40e0d0,ff000000,ff18544e," +
                "ff40e0d0,ffbf1f2f,ffffffff,ff200508,ffbf1f2f,ff202125,ffdfdeda,ff202125," +
                "ffdfdeda,ff233133,ffdfdeda,ff40e0d0,ffdfdeda,ff202125,fff2b8b5,ff601410," +
                "ff8c1d18,fff9dedc,ff899391,ff49454f,ff000000,ff141218,ff3b383e,ff202c2e," +
                "ff222f31,ff233133,ff28383a,ff2f4244",
            TealTurqoiseColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff008080,ffffffff,ff008080,ffffffff,ff40e0d0,ff008080,ffffffff,ffcfe5e4," +
                "ff008080,ffff7f7f,ff000000,ff2a1616,ffff7f7f,fffafafa,ff050505,fffafafa," +
                "ff050505,ffebf3f1,ff050505,ffbfdfdf,ff050505,fffafafa,ffb3261e,ffffffff," +
                "fff9dedc,ff410e0b,ff6f7977,ffcac4d0,ff000000,ffded8e1,fffef7ff,ffe1e9e7," +
                "ffe6eeec,ffebf3f1,fff0f8f6,fff7fffd",
            TealTurqoiseColorScheme.lightScheme.exactTokenSnapshot(),
        )
    }

    @Test
    fun `tidal wave palette matches fixed main tokens`() {
        assertEquals(
            "ff5ed4fc,ff003544,ff004d61,ffb8eaff,ffa12b03,ff5ed4fc,ff003544,ff004d61," +
                "ffb8eaff,ff92f7bc,ff001c3b,ffc3fada,ff78ffd6,ff001c3b,ffd5e3ff,ff001c3b," +
                "ffd5e3ff,ff082b4b,ffbfc8cc,ff5ed4fc,ffffe3c4,ff001c3b,fff2b8b5,ff601410," +
                "ff8c1d18,fff9dedc,ff8a9296,ff49454f,ff000000,ff141218,ff3b383e,ff072642," +
                "ff072947,ff082b4b,ff093257,ff0a3861",
            TidalWaveColorScheme.darkScheme.exactTokenSnapshot(),
        )
        assertEquals(
            "ff006780,ffffffff,ffb4d4df,ff001f28,ffff987f,ff006780,ffffffff,ff9ae1ff," +
                "ff001f28,ff92f7bc,ff001c3b,ffc3fada,ff78ffd6,fffdfbff,ff001c3b,fffdfbff," +
                "ff001c3b,ffe8eff5,ff40484c,ff006780,ff020400,ffffe3c4,ffb3261e,ffffffff," +
                "fff9dedc,ff410e0b,ff70787c,ffcac4d0,ff000000,ffded8e1,fffef7ff,ffe2e8ec," +
                "ffe5ecf1,ffe8eff5,ffedf4fa,fff5faff",
            TidalWaveColorScheme.lightScheme.exactTokenSnapshot(),
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
