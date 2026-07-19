package mihon.desktop.compat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.util.Base64 as JavaBase64

/**
 * Tests for the Android API compat stubs provided by [AndroidCompat].
 *
 * These stubs allow APK-sourced extensions (which import android.* classes) to
 * run on the JVM without Android runtime.
 */
class AndroidCompatTest {

    // ── Context ────────────────────────────────────────────────────────────────

    @Test
    fun `Context getSharedPreferences returns non-null instance`() {
        val context = AndroidCompat.context
        val prefs = context.getSharedPreferences("test_prefs", 0)
        prefs.shouldNotBeNull()
    }

    @Test
    fun `Application and ContextWrapper preserve Android Context inheritance`() {
        Context::class.java.isAssignableFrom(ContextWrapper::class.java) shouldBe true
        Context::class.java.isAssignableFrom(Application::class.java) shouldBe true
    }

    @Test
    fun `SharedPreferences getString returns default when key absent`() {
        val prefs = AndroidCompat.context.getSharedPreferences("sp_default_test", 0)
        val value = prefs.getString("missing_key", "default_val")
        value shouldBe "default_val"
    }

    @Test
    fun `SharedPreferences putString and getString round-trip`() {
        val prefs = AndroidCompat.context.getSharedPreferences("sp_roundtrip", 0)
        val editor = prefs.edit()
        editor.putString("greeting", "hello")
        editor.apply()

        val retrieved = prefs.getString("greeting", null)
        retrieved shouldBe "hello"
    }

    @Test
    fun `SharedPreferences getInt and putInt round-trip`() {
        val prefs = AndroidCompat.context.getSharedPreferences("sp_int", 0)
        prefs.edit().putInt("count", 42).apply()
        prefs.getInt("count", 0) shouldBe 42
    }

    @Test
    fun `SharedPreferences getBoolean and putBoolean round-trip`() {
        val prefs = AndroidCompat.context.getSharedPreferences("sp_bool", 0)
        prefs.edit().putBoolean("flag", true).apply()
        prefs.getBoolean("flag", false) shouldBe true
    }

    @Test
    fun `SharedPreferences different names are isolated`() {
        val prefs1 = AndroidCompat.context.getSharedPreferences("ns1", 0)
        val prefs2 = AndroidCompat.context.getSharedPreferences("ns2", 0)
        prefs1.edit().putString("key", "value1").apply()
        prefs2.edit().putString("key", "value2").apply()

        prefs1.getString("key", null) shouldBe "value1"
        prefs2.getString("key", null) shouldBe "value2"
    }

    // ── Base64 ────────────────────────────────────────────────────────────────

    @Test
    fun `AndroidBase64 encodeToString matches Java Base64`() {
        val input = "Hello, Mihon!".toByteArray()
        val expected = JavaBase64.getEncoder().encodeToString(input)
        val actual = android.util.Base64.encodeToString(input, android.util.Base64.DEFAULT)
        actual.trim() shouldBe expected.trim()
    }

    @Test
    fun `AndroidBase64 decode matches Java Base64`() {
        val original = "Desktop Extension Test"
        val encoded = JavaBase64.getEncoder().encodeToString(original.toByteArray())
        val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        String(decoded) shouldBe original
    }

    // ── Log ───────────────────────────────────────────────────────────────────

    @Test
    fun `AndroidLog does not throw for any level`() {
        // Log calls should silently succeed (delegate to println or similar)
        android.util.Log.v("TestTag", "verbose")
        android.util.Log.d("TestTag", "debug")
        android.util.Log.i("TestTag", "info")
        android.util.Log.w("TestTag", "warn")
        android.util.Log.e("TestTag", "error")
        android.util.Log.e("TestTag", "with exception", RuntimeException("test"))
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    @Test
    fun `Build VERSION SDK_INT is reasonable`() {
        val sdkInt = android.os.Build.VERSION.SDK_INT
        // Should be a plausible Android API level (21-34)
        assert(sdkInt in 21..40) { "SDK_INT=$sdkInt not in expected range" }
    }
}
