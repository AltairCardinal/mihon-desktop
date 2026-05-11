package mihon.desktop.compat

import android.os.Environment
import android.os.Handler
import android.os.Looper
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for Phase 3 Android compat stubs: Handler, Looper, Environment.
 */
class AndroidCompatPhase3Test {

    // ── Looper ──────────────────────────────────────────────────────────────

    @Test
    fun `Looper getMainLooper returns non-null`() {
        Looper.getMainLooper().shouldNotBeNull()
    }

    @Test
    fun `Looper myLooper returns non-null on any thread`() {
        Looper.myLooper().shouldNotBeNull()
    }

    // ── Handler ─────────────────────────────────────────────────────────────

    @Test
    fun `Handler with Looper can be constructed`() {
        val handler = Handler(Looper.getMainLooper())
        handler.shouldNotBeNull()
    }

    @Test
    fun `Handler post executes runnable`() {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        handler.post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `Handler postDelayed executes after delay`() {
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        handler.postDelayed({ latch.countDown() }, 50)
        latch.await(2, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `Handler getLooper returns the looper`() {
        val looper = Looper.getMainLooper()
        val handler = Handler(looper)
        handler.looper shouldBe looper
    }

    // ── Environment ─────────────────────────────────────────────────────────

    @Test
    fun `Environment getExternalStorageDirectory returns non-null`() {
        Environment.getExternalStorageDirectory().shouldNotBeNull()
    }

    @Test
    fun `Environment getDataDirectory returns non-null`() {
        Environment.getDataDirectory().shouldNotBeNull()
    }

    @Test
    fun `Environment getExternalStorageState returns mounted`() {
        Environment.getExternalStorageState() shouldBe Environment.MEDIA_MOUNTED
    }
}
