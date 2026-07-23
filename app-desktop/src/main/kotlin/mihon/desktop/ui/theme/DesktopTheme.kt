package mihon.desktop.ui.theme

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.theme.colorscheme.AppThemeColorScheme

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    val prefs = LocalDesktopUiDependencies.current.appPreferences
    val themeMode by prefs.themeMode.changes().collectAsState(initial = prefs.themeMode.get())
    val appTheme by prefs.appTheme.changes().collectAsState(initial = prefs.appTheme.get())
    val isAmoled by prefs.themeDarkAmoled.changes().collectAsState(initial = prefs.themeDarkAmoled.get())
    val systemIsDark = isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = remember(appTheme, themeMode, systemIsDark, isAmoled) {
            desktopColorScheme(appTheme, themeMode, systemIsDark, isAmoled)
        },
        content = content,
    )
}

internal fun desktopColorScheme(
    appTheme: AppTheme,
    themeMode: ThemeMode,
    systemIsDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemIsDark
    }
    return AppThemeColorScheme.colorScheme(
        appTheme = appTheme,
        isDark = isDark,
        isAmoled = isAmoled,
    )
}
