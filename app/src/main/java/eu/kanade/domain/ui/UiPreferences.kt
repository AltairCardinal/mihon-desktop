package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeDefaults
import eu.kanade.domain.ui.model.ThemePreferenceCodec
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun themeMode() = preferenceStore.getObjectFromString(
        ThemeDefaults.THEME_MODE_KEY,
        ThemeDefaults.themeMode,
        ThemePreferenceCodec::encode,
        ThemePreferenceCodec::decodeThemeMode,
    )

    fun appTheme() = preferenceStore.getObjectFromString(
        ThemeDefaults.APP_THEME_KEY,
        ThemeDefaults.appTheme(DeviceUtil.isDynamicColorAvailable),
        ThemePreferenceCodec::encode,
        { ThemePreferenceCodec.decodeAppTheme(it, DeviceUtil.isDynamicColorAvailable) },
    )

    fun themeDarkAmoled() = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    fun relativeTime() = preferenceStore.getBoolean("relative_time_v2", true)

    fun dateFormat() = preferenceStore.getString("app_date_format", "")

    fun tabletUiMode() = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    fun imagesInDescription() = preferenceStore.getBoolean("pref_render_images_description", true)

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
