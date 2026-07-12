package mihon.desktop.di

import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class DesktopDiWiringTest {
    @Test
    fun `配置入口解析 UI 实际设置与阅读器依赖`(@TempDir tempDir: File) {
        initConfigLayer(tempDir)

        assertNotNull(Injekt.get<PreferenceStore>())
        assertNotNull(Injekt.get<DesktopAppPreferences>())
        assertNotNull(Injekt.get<ReaderPreferences>())
    }
}
