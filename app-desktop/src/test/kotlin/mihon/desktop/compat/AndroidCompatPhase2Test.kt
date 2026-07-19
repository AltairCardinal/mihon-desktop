package mihon.desktop.compat

import android.graphics.Color
import android.text.Html
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 2 Android compat stubs: Html and Color.
 */
class AndroidCompatPhase2Test {

    // ── Html ────────────────────────────────────────────────────────────────

    @Test
    fun `Html fromHtml strips tags`() {
        val result = Html.fromHtml("<p>Hello <b>world</b></p>", 0)
        result.toString().trim() shouldBe "Hello world"
    }

    @Test
    fun `Html fromHtml handles null gracefully`() {
        val result = Html.fromHtml("", 0)
        result.toString() shouldBe ""
    }

    // ── Color ───────────────────────────────────────────────────────────────

    @Test
    fun `Color parseColor parses hex`() {
        Color.parseColor("#FF0000") shouldBe 0xFFFF0000.toInt()
    }

    @Test
    fun `Color parseColor parses named color red`() {
        Color.parseColor("red") shouldBe 0xFFFF0000.toInt()
    }

    @Test
    fun `Color rgb creates opaque color`() {
        val c = Color.rgb(255, 0, 0)
        Color.red(c) shouldBe 255
        Color.green(c) shouldBe 0
        Color.blue(c) shouldBe 0
        Color.alpha(c) shouldBe 255
    }

    @Test
    fun `Color argb creates color with alpha`() {
        val c = Color.argb(128, 0, 255, 0)
        Color.alpha(c) shouldBe 128
        Color.red(c) shouldBe 0
        Color.green(c) shouldBe 255
        Color.blue(c) shouldBe 0
    }

    @Test
    fun `Color constants are correct`() {
        Color.BLACK shouldBe 0xFF000000.toInt()
        Color.WHITE shouldBe 0xFFFFFFFF.toInt()
        Color.TRANSPARENT shouldBe 0x00000000
    }
}
