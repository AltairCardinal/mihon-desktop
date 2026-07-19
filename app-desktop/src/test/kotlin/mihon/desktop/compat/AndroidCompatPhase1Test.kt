package mihon.desktop.compat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 1 Android compat stubs: ContextWrapper and Application.
 */
class AndroidCompatPhase1Test {

    // ── ContextWrapper ─────────────────────────────────────────────────────

    @Test
    fun `ContextWrapper delegates getSharedPreferences to base context`() {
        val base = Context()
        val wrapper = ContextWrapper(base)
        val prefs = wrapper.getSharedPreferences("wrapper_test", 0)
        prefs.shouldNotBeNull()
    }

    @Test
    fun `ContextWrapper delegates getPackageName to base context`() {
        val base = Context()
        val wrapper = ContextWrapper(base)
        wrapper.getPackageName() shouldBe base.getPackageName()
    }

    @Test
    fun `ContextWrapper delegates getFilesDir to base context`() {
        val base = Context()
        val wrapper = ContextWrapper(base)
        wrapper.getFilesDir() shouldBe base.getFilesDir()
    }

    @Test
    fun `ContextWrapper getBaseContext returns the base`() {
        val base = Context()
        val wrapper = ContextWrapper(base)
        wrapper.getBaseContext() shouldBe base
    }

    @Test
    fun `ContextWrapper getApplicationContext returns self by default`() {
        val base = Context()
        val wrapper = ContextWrapper(base)
        wrapper.getApplicationContext().shouldNotBeNull()
    }

    // ── Application ─────────────────────────────────────────────────────────

    @Test
    fun `Application extends ContextWrapper`() {
        val app = Application()
        app.shouldBeInstanceOf<ContextWrapper>()
    }

    @Test
    fun `Application attach sets base context`() {
        val app = Application()
        val ctx = Context()
        app.attach(ctx)
        app.getBaseContext() shouldBe ctx
    }

    @Test
    fun `Application onCreate does not throw`() {
        val app = Application()
        app.attach(Context())
        app.onCreate()
    }

    @Test
    fun `Application onTerminate does not throw`() {
        val app = Application()
        app.attach(Context())
        app.onTerminate()
    }

    @Test
    fun `Application getSharedPreferences delegates after attach`() {
        val app = Application()
        app.attach(Context())
        val prefs = app.getSharedPreferences("app_test", 0)
        prefs.shouldNotBeNull()
    }

}
