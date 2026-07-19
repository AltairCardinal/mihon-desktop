package mihon.desktop.compat

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 6: content.pm stubs.
 */
class AndroidCompatPhase6Test {

    // ── ApplicationInfo ─────────────────────────────────────────────────────

    @Test
    fun `ApplicationInfo has default fields`() {
        val info = ApplicationInfo()
        info.shouldNotBeNull()
        info.packageName.shouldNotBeNull()
    }

    // ── PackageInfo ─────────────────────────────────────────────────────────

    @Test
    fun `PackageInfo has versionName and versionCode`() {
        val info = PackageInfo()
        info.versionName = "1.0.0"
        info.versionCode = 10
        info.versionName shouldBe "1.0.0"
        info.versionCode shouldBe 10
    }

    // ── PackageManager ──────────────────────────────────────────────────────

    @Test
    fun `PackageManager getPackageInfo returns non-null`() {
        val pm = PackageManager()
        val info = pm.getPackageInfo("mihon.desktop", 0)
        info.shouldNotBeNull()
    }

    @Test
    fun `PackageManager getApplicationInfo returns non-null`() {
        val pm = PackageManager()
        val info = pm.getApplicationInfo("mihon.desktop", 0)
        info.shouldNotBeNull()
    }
}
