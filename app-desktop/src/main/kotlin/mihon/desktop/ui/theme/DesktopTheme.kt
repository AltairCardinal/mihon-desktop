package mihon.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.ThemeMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    val prefs = remember { Injekt.get<DesktopAppPreferences>() }
    val themeMode by prefs.themeMode.changes().collectAsState(initial = prefs.themeMode.get())
    val systemIsDark = isSystemInDarkTheme()

    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemIsDark
    }

    MaterialTheme(
        colorScheme = if (useDark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
