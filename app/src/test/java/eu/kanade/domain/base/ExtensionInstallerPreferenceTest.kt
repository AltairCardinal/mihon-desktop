package eu.kanade.domain.base

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExtensionInstallerPreferenceTest {
    @Test
    fun `explicit Shizuku choice survives unavailable service package`() {
        val context = context { false }
        val store = store()
        val preference = ExtensionInstallerPreference(context, store)
        preference.set(BasePreferences.ExtensionInstaller.SHIZUKU)
        assertEquals(BasePreferences.ExtensionInstaller.SHIZUKU, preference.get())
        assertEquals(BasePreferences.ExtensionInstaller.SHIZUKU, ExtensionInstallerPreference(context, store).get())
    }

    @Test
    fun `removing Shizuku package does not replace a persisted installer on read or reconstruction`() {
        var installed = true
        val context = context { installed }
        val store = store()
        val preference = ExtensionInstallerPreference(context, store)
        preference.set(BasePreferences.ExtensionInstaller.SHIZUKU)
        assertEquals(BasePreferences.ExtensionInstaller.SHIZUKU, preference.get())
        installed = false
        assertEquals(BasePreferences.ExtensionInstaller.SHIZUKU, preference.get())
        assertEquals(BasePreferences.ExtensionInstaller.SHIZUKU, ExtensionInstallerPreference(context, store).get())
    }

    private fun store(): AndroidPreferenceStore {
        val application = RuntimeEnvironment.getApplication()
        return AndroidPreferenceStore(
            application,
            application.getSharedPreferences("installer-" + java.util.UUID.randomUUID(), Context.MODE_PRIVATE),
        )
    }

    private fun context(shizukuInstalled: () -> Boolean): Context {
        val pm = mockk<PackageManager> {
            every { getApplicationInfo(any<String>(), any<Int>()) } answers {
                if (firstArg<String>() == "moe.shizuku.privileged.api" && shizukuInstalled()) {
                    ApplicationInfo()
                } else {
                    throw PackageManager.NameNotFoundException()
                }
            }
        }
        return mockk { every { packageManager } returns pm }
    }
}
