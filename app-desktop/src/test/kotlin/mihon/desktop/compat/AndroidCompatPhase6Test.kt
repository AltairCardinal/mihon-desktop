package mihon.desktop.compat

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 6: content.pm stubs and Intent.
 */
class AndroidCompatPhase6Test {

    // ── Intent ──────────────────────────────────────────────────────────────

    @Test
    fun `Intent default constructor works`() {
        val intent = Intent()
        intent.shouldNotBeNull()
    }

    @Test
    fun `Intent action constructor works`() {
        val intent = Intent("android.intent.action.VIEW")
        intent.action shouldBe "android.intent.action.VIEW"
    }

    @Test
    fun `Intent putExtra and getStringExtra round-trip`() {
        val intent = Intent()
        intent.putExtra("key", "value")
        intent.getStringExtra("key") shouldBe "value"
    }

    @Test
    fun `Intent putExtra and getIntExtra round-trip`() {
        val intent = Intent()
        intent.putExtra("num", 42)
        intent.getIntExtra("num", 0) shouldBe 42
    }

    @Test
    fun `Intent putExtra and getBooleanExtra round-trip`() {
        val intent = Intent()
        intent.putExtra("flag", true)
        intent.getBooleanExtra("flag", false) shouldBe true
    }

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
