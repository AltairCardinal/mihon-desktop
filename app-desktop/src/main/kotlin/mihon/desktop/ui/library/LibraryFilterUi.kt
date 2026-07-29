package mihon.desktop.ui.library

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.i18n.MR

internal fun filterRows(filter: LibraryFilter) = listOf(
    MR.strings.label_downloaded.localized() to (LibraryFilterField.DOWNLOADED to filter.downloaded),
    MR.strings.unread.localized() to (LibraryFilterField.UNREAD to filter.unread),
    MR.strings.desktop_ui_started.localized() to (LibraryFilterField.STARTED to filter.started),
    MR.strings.action_filter_bookmarked.localized() to (LibraryFilterField.BOOKMARKED to filter.bookmarked),
    MR.strings.completed.localized() to (LibraryFilterField.COMPLETED to filter.completed),
    MR.strings.desktop_ui_custom_interval.localized() to (LibraryFilterField.INTERVAL_CUSTOM to filter.intervalCustom),
)

internal fun TriState.label() = when (this) {
    TriState.DISABLED -> MR.strings.desktop_ui_filter_any.localized()
    TriState.ENABLED_IS -> MR.strings.desktop_ui_filter_include.localized()
    TriState.ENABLED_NOT -> MR.strings.desktop_ui_filter_exclude.localized()
}

internal fun TriState?.orDisabledForUi() = this ?: TriState.DISABLED

internal fun Boolean.onOff() = if (this) MR.strings.on.localized() else MR.strings.off.localized()
