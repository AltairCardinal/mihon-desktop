package mihon.desktop.compat

import android.content.pm.ApplicationInfo
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 6: the content.pm ApplicationInfo stub.
 */
class AndroidCompatPhase6Test {

    // ── ApplicationInfo ─────────────────────────────────────────────────────

    @Test
    fun `ApplicationInfo has default fields`() {
        val info = ApplicationInfo()
        info.shouldNotBeNull()
        info.packageName.shouldNotBeNull()
    }

}
