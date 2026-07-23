package eu.kanade.domain.ui.model

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

object ThemeDefaults {
    const val THEME_MODE_KEY = "pref_theme_mode_key"
    const val APP_THEME_KEY = "pref_app_theme"

    val themeMode = ThemeMode.SYSTEM

    fun appTheme(dynamicColorAvailable: Boolean): AppTheme {
        return if (dynamicColorAvailable) AppTheme.MONET else AppTheme.DEFAULT
    }
}

object ThemePreferenceCodec {
    fun encode(value: ThemeMode): String = value.name

    fun encode(value: AppTheme): String = value.name

    fun decodeThemeMode(value: String): ThemeMode {
        return ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeDefaults.themeMode
    }

    fun decodeAppTheme(value: String, dynamicColorAvailable: Boolean): AppTheme {
        return AppTheme.entries.firstOrNull { it.name == value }
            ?: ThemeDefaults.appTheme(dynamicColorAvailable)
    }
}

fun selectableAppThemes(dynamicColorAvailable: Boolean): List<AppTheme> {
    return AppTheme.entries.filter {
        it.titleRes != null && (it != AppTheme.MONET || dynamicColorAvailable)
    }
}
