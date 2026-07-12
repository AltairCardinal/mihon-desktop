package mihon.desktop.di

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.backup.AutoBackupScheduler
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class DesktopDiWiringTest {
    @Test
    fun `测试配置入口使用隔离内存存储并解析实际依赖`(@TempDir tempDir: File) = runBlocking {
        val testStore = InMemoryPreferenceStore()
        initDesktopDIForTest(tempDir, testStore)

        assertSame(testStore, Injekt.get<PreferenceStore>())
        assertNotNull(Injekt.get<DesktopAppPreferences>())
        assertNotNull(Injekt.get<ReaderPreferences>())
        assertNotNull(Injekt.get<LibraryUpdateScheduler>())
        assertNotNull(Injekt.get<DesktopNetworkHelper>())
        assertNotNull(Injekt.get<DesktopTaskScheduler>())
        assertNotNull(Injekt.get<DesktopDownloadManager>())
        assertNotNull(Injekt.get<AutoBackupScheduler>())
        assertNotNull(Injekt.get<DesktopExtensionManager>())

        val preference = Injekt.get<PreferenceStore>().getString("wiring_observe", "initial")
        val changed = async(start = CoroutineStart.UNDISPATCHED) { preference.changes().drop(1).first() }
        preference.set("updated")
        assertEquals("updated", withTimeout(1_000) { changed.await() })
    }
}
