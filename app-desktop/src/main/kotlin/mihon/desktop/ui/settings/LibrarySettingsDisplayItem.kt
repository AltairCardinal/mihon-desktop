package mihon.desktop.ui.settings

import tachiyomi.i18n.MR
import java.util.Locale

import mihon.desktop.settings.DesktopAppPreferences

internal data class CheckboxSettingsItem(
    val title: String,
    val checked: Boolean,
    val onClick: () -> Unit,
    val onCheckedChange: (Boolean) -> Unit,
)

internal fun missingChapterIndicatorSettingsItem(
    prefs: DesktopAppPreferences,
    checked: Boolean = prefs.hideMissingChapterIndicators.get(),
    locale: Locale = Locale.getDefault(),
): CheckboxSettingsItem {
    return CheckboxSettingsItem(
        title = MR.strings.desktop_ui_hide_missing_chapter_indicators.localized(locale),
        checked = checked,
        onClick = {
            prefs.hideMissingChapterIndicators.set(!checked)
        },
        onCheckedChange = { value ->
            prefs.hideMissingChapterIndicators.set(value)
        },
    )
}
