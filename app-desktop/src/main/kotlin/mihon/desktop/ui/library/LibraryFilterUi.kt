package mihon.desktop.ui.library

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter

internal fun filterRows(filter: LibraryFilter) = listOf(
    "Downloaded" to (LibraryFilterField.DOWNLOADED to filter.downloaded),
    "Unread" to (LibraryFilterField.UNREAD to filter.unread),
    "Started" to (LibraryFilterField.STARTED to filter.started),
    "Bookmarked" to (LibraryFilterField.BOOKMARKED to filter.bookmarked),
    "Completed" to (LibraryFilterField.COMPLETED to filter.completed),
    "Custom interval" to (LibraryFilterField.INTERVAL_CUSTOM to filter.intervalCustom),
)

internal fun TriState.label() = when (this) {
    TriState.DISABLED -> "Any"
    TriState.ENABLED_IS -> "Include"
    TriState.ENABLED_NOT -> "Exclude"
}

internal fun TriState?.orDisabledForUi() = this ?: TriState.DISABLED

internal fun Boolean.onOff() = if (this) "On" else "Off"
