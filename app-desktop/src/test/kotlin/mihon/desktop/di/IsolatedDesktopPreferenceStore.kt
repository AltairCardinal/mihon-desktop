package mihon.desktop.di

import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.UUID
import java.util.prefs.Preferences

internal fun isolatedDesktopPreferenceStore(): DesktopPreferenceStore =
    DesktopPreferenceStore(
        Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}"),
    )
