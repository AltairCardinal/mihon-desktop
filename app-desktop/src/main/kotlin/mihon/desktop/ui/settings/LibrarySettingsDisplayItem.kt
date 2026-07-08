package mihon.desktop.ui.settings

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
): CheckboxSettingsItem {
    return CheckboxSettingsItem(
        title = "隐藏缺话提示",
        checked = checked,
        onClick = {
            prefs.hideMissingChapterIndicators.set(!checked)
        },
        onCheckedChange = { value ->
            prefs.hideMissingChapterIndicators.set(value)
        },
    )
}
