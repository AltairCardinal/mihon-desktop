package eu.kanade.presentation.theme

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidSharedPaletteWiringTest {

    @Test
    fun `android theme map uses shared palette objects`() {
        val field = Class.forName("eu.kanade.presentation.theme.TachiyomiThemeKt")
            .getDeclaredField("colorSchemes")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val palettes = field.get(null) as Map<AppTheme, BaseColorScheme>

        assertSame(TachiyomiColorScheme, palettes[AppTheme.DEFAULT])
        assertSame(GreenAppleColorScheme, palettes[AppTheme.GREEN_APPLE])
    }

    @Test
    fun `android palettes are loaded from shared module`() {
        assertSharedModuleOrigin(BaseColorScheme::class.java)
        assertSharedModuleOrigin(TachiyomiColorScheme::class.java)
        assertSharedModuleOrigin(GreenAppleColorScheme::class.java)
    }

    private fun assertSharedModuleOrigin(type: Class<*>) {
        val codeSource = requireNotNull(type.protectionDomain?.codeSource) {
            "${type.name} has no runtime code source"
        }
        val location = codeSource.location.toString().replace('\\', '/')
        assertTrue(
            location.contains("/presentation-theme/"),
            "${type.name} was loaded from Android app output: $location",
        )
    }
}
