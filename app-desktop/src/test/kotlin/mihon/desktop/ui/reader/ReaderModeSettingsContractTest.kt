package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.prefs.Preferences

class ReaderModeSettingsContractTest {
    @Test
    fun `early projection uses the same complete effective settings as the full reader`() {
        val root = Preferences.userRoot().node("/mihon/reader-settings-projection/${System.nanoTime()}")
        val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))
        val settings =
            ReaderSettings(
                mode = ReaderSettingsMode.VERTICAL,
                scroll = ReaderScrollMode.PAGED,
                gap = true,
                match = false,
            )
        val flags = ReaderSettingsCodec.setCustom(0L, settings)
        try {
            val initial =
                initialReaderState(
                    prefs,
                    mihon.desktop.reader.desktopReaderSessionState(pageCount = 2),
                    false,
                    flags,
                    null,
                    false,
                )
            assertEquals(settings, initial.readerSettings)
            assertEquals(ReadingMode.VERTICAL, initial.readingMode)
            assertEquals(false, firstCompositionSnapshotReady(initial))
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `global defaults retain hidden choices through preference reconstruction`() {
        val legacy = Preferences.userRoot().node("/mihon/reader-settings-defaults/${System.nanoTime()}")
        val store = DesktopPreferenceStore(legacy.node("store"))
        val expected = ReaderSettings(
            mode = ReaderSettingsMode.VERTICAL,
            layout = ReaderSettingsLayout.DOUBLE,
            direction = ReaderDirection.LTR,
            scroll = ReaderScrollMode.PAGED,
            match = false,
            gap = true,
        )
        try {
            ReaderPreferences(store, legacy.node("legacy")).saveReaderSettings(expected)
            assertEquals(
                expected,
                ReaderPreferences(DesktopPreferenceStore(legacy.node("store")), legacy.node("legacy"))
                    .loadReaderSettings(),
            )
        } finally {
            legacy.removeNode()
        }
    }

    @Test
    fun `custom hidden choices restore after following default and reopening the reader`() {
        val legacy = Preferences.userRoot().node("/mihon/reader-settings-reopen/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy)
        val custom = ReaderSettings(
            mode = ReaderSettingsMode.VERTICAL,
            layout = ReaderSettingsLayout.DOUBLE,
            direction = ReaderDirection.LTR,
            scroll = ReaderScrollMode.PAGED,
            match = false,
            gap = true,
        )
        val model = ReaderScreenModel(prefs = prefs)
        try {
            model.setReaderSettings(custom)
            model.followGlobalReadingMode(prefs)
            val reopened = ReaderScreenModel(prefs = prefs, mangaViewerFlags = model.currentViewerFlags())
            try {
                assertEquals(prefs.loadReaderSettings(), reopened.state.value.readerSettings)
                reopened.restoreCustomReadingMode()
                assertEquals(custom, reopened.state.value.readerSettings)
                assertEquals(ReadingMode.VERTICAL, reopened.state.value.readingMode)
                assertEquals(false, reopened.state.value.dualPageMode)
            } finally {
                reopened.onDispose()
            }
        } finally {
            model.onDispose()
            legacy.removeNode()
        }
    }

    @Test
    fun `Android vertical pager flags retain vertical presentation on desktop`() {
        assertEquals("VERTICAL", readingModeFromViewerFlags(3L)?.name)
    }

    @Test
    fun `following default retains custom layout and direction in persisted flags`() {
        val legacy = Preferences.userRoot().node("/mihon/reader-settings-contract/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            readingMode = ReadingMode.AUTO
        }
        val model = ReaderScreenModel(prefs = prefs)
        try {
            model.setReadingMode(ReadingMode.LTR)
            model.setDualPageMode(true)
            model.followGlobalReadingMode(prefs)

            val saved = model.currentViewerFlags()
            assertEquals(true, ReaderSettingsCodec.isFollowingDefault(saved))
            val restored = ReaderSettingsCodec.custom(saved)
            assertEquals(ReaderSettingsLayout.DOUBLE, restored.layout)
            assertEquals(ReaderDirection.LTR, restored.direction)
        } finally {
            model.onDispose()
            legacy.removeNode()
        }
    }
}
