package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme
import eu.kanade.domain.ui.model.AppTheme

object AppThemeColorScheme {

    fun colorScheme(
        appTheme: AppTheme,
        isDark: Boolean,
        isAmoled: Boolean,
        monetColorScheme: BaseColorScheme? = null,
    ): ColorScheme {
        val selected = if (appTheme == AppTheme.MONET) {
            monetColorScheme ?: TachiyomiColorScheme
        } else {
            staticColorSchemes.getOrDefault(appTheme, TachiyomiColorScheme)
        }
        return selected.getColorScheme(
            isDark = isDark,
            isAmoled = isAmoled,
            overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
        )
    }
}

private val staticColorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
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
