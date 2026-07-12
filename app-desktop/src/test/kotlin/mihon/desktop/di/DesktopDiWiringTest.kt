package mihon.desktop.di

import mihon.desktop.backup.AutoBackupScheduler
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.prefs.Preferences

class DesktopDiWiringTest {
    @Test
    fun `测试配置入口隔离用户偏好并解析七领域实际依赖`(@TempDir tempDir: File) {
        val userStore = Preferences.userRoot().node("/mihon")
        userStore.put("task1a_isolation_probe", "user")

        initDesktopDIForTest(tempDir)

        assertNotNull(Injekt.get<PreferenceStore>())
        assertNotNull(Injekt.get<DesktopAppPreferences>())
        assertNotNull(Injekt.get<ReaderPreferences>())
        assertNotNull(Injekt.get<LibraryUpdateScheduler>())
        assertNotNull(Injekt.get<DesktopNetworkHelper>())
        assertNotNull(Injekt.get<DesktopDownloadManager>())
        assertNotNull(Injekt.get<AutoBackupScheduler>())
        assertNotNull(Injekt.get<DesktopExtensionManager>())
        Injekt.get<PreferenceStore>().getString("task1a_isolation_probe").set("test")
        org.junit.jupiter.api.Assertions.assertEquals("user", userStore.get("task1a_isolation_probe", null))
        userStore.remove("task1a_isolation_probe")
    }

    @Test
    fun `单一初始化入口调用全部领域 registrar`() {
        val source = File("src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt").readText()
        listOf("Settings", "Reader", "Library", "Network", "Download", "Backup", "Extension").forEach { domain ->
            org.junit.jupiter.api.Assertions.assertTrue(source.contains("registerDesktop$domain("), domain)
        }
    }
}
