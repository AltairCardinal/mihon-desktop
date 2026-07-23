package eu.kanade.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.AppThemeColorScheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidSharedPaletteWiringTest {

    @Test
    fun `android theme consumer delegates static selection to shared selector`() {
        val selected = getThemeColorScheme(
            appTheme = AppTheme.YINYANG,
            isDark = false,
            isAmoled = true,
            monetColorScheme = { error("Static themes must not create the Android Monet adapter") },
        )

        assertSame(YinYangColorScheme.lightScheme, selected)
    }

    @Test
    fun `android theme consumer injects Monet adapter result into shared selector`() {
        val dynamic = object : BaseColorScheme() {
            override val lightScheme = lightColorScheme(primary = Color.Red)
            override val darkScheme = darkColorScheme(surfaceContainer = Color.Blue)
        }
        var creations = 0

        val selected = getThemeColorScheme(
            appTheme = AppTheme.MONET,
            isDark = true,
            isAmoled = true,
            monetColorScheme = {
                creations++
                dynamic
            },
        )

        assertEquals(1, creations)
        assertEquals(Color.Black, selected.background)
        assertEquals(dynamic.darkScheme.surfaceContainer, selected.surfaceContainer)
    }

    @Test
    fun `android palettes are loaded from shared module`() {
        assertSharedModuleOrigin(BaseColorScheme::class.java)
        assertSharedModuleOrigin(AppThemeColorScheme::class.java)
        assertSharedModuleOrigin(YinYangColorScheme::class.java)
        assertAndroidModuleOrigin(MonetColorScheme::class.java)
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

    private fun assertAndroidModuleOrigin(type: Class<*>) {
        val codeSource = requireNotNull(type.protectionDomain?.codeSource) {
            "${type.name} has no runtime code source"
        }
        val location = codeSource.location.toString().replace('\\', '/')
        assertTrue(
            location.contains("/app/"),
            "${type.name} was not loaded from Android app output: $location",
        )
    }
}
