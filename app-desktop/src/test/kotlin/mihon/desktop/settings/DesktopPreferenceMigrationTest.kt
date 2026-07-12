package mihon.desktop.settings

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopPreferenceMigrationTest {
    private val root = Preferences.userRoot().node("/mihon/task1a/${System.nanoTime()}")
    private val newNode = root.node("new")
    private val legacyApp = root.node("legacy-app")
    private val legacyReader = root.node("legacy-reader")
    private val store = DesktopPreferenceStore(newNode)

    @AfterEach
    fun tearDown() = root.removeNode()

    @Test
    fun `仅旧 reader 值首次读取时迁移且保留旧值`() {
        legacyReader.put("readingMode", ReadingMode.WEBTOON.name)

        val prefs = ReaderPreferences(store, legacyReader)

        assertEquals(ReadingMode.WEBTOON, prefs.readingMode)
        assertEquals(ReadingMode.WEBTOON.name, newNode.get("reader_reading_mode", null))
        assertEquals(ReadingMode.WEBTOON.name, legacyReader.get("readingMode", null))
    }

    @Test
    fun `新值优先于冲突旧值`() {
        newNode.put("reader_reading_mode", ReadingMode.RTL.name)
        legacyReader.put("readingMode", ReadingMode.WEBTOON.name)

        assertEquals(ReadingMode.RTL, ReaderPreferences(store, legacyReader).readingMode)
    }

    @Test
    fun `无值使用默认且非法枚举回退`() {
        legacyReader.put("readingMode", "BROKEN")

        assertEquals(ReadingMode.LTR, ReaderPreferences(store, legacyReader).readingMode)
        assertEquals(ThemeMode.SYSTEM, DesktopAppPreferences(store, legacyApp).themeMode.get())
    }

    @Test
    fun `旧 app 值迁移且写入会通知观察者`() = runTest {
        legacyApp.put("theme_mode", ThemeMode.DARK.name)
        val preference = DesktopAppPreferences(store, legacyApp).themeMode
        assertEquals(ThemeMode.DARK, preference.get())

        val observed = async { preference.changes().drop(1).first() }
        runCurrent()
        preference.set(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, observed.await())
    }
}
