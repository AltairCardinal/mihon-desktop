package mihon.desktop.compat

import android.graphics.Color
import android.text.Html
import android.text.TextUtils
import android.util.Pair
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 2 Android compat stubs: TextUtils, Html, Pair, Color.
 */
class AndroidCompatPhase2Test {

    // ── TextUtils ───────────────────────────────────────────────────────────

    @Test
    fun `TextUtils isEmpty returns true for null`() {
        TextUtils.isEmpty(null) shouldBe true
    }

    @Test
    fun `TextUtils isEmpty returns true for empty string`() {
        TextUtils.isEmpty("") shouldBe true
    }

    @Test
    fun `TextUtils isEmpty returns false for non-empty string`() {
        TextUtils.isEmpty("hello") shouldBe false
    }

    @Test
    fun `TextUtils join joins with delimiter`() {
        TextUtils.join(", ", listOf("a", "b", "c")) shouldBe "a, b, c"
    }

    @Test
    fun `TextUtils join returns empty for empty list`() {
        TextUtils.join(", ", emptyList<String>()) shouldBe ""
    }

    @Test
    fun `TextUtils isDigitsOnly returns true for digits`() {
        TextUtils.isDigitsOnly("12345") shouldBe true
    }

    @Test
    fun `TextUtils isDigitsOnly returns false for mixed`() {
        TextUtils.isDigitsOnly("123abc") shouldBe false
    }

    @Test
    fun `TextUtils isDigitsOnly returns false for empty`() {
        TextUtils.isDigitsOnly("") shouldBe false
    }

    @Test
    fun `TextUtils equals handles nulls`() {
        TextUtils.equals(null, null) shouldBe true
        TextUtils.equals("a", null) shouldBe false
        TextUtils.equals(null, "a") shouldBe false
        TextUtils.equals("a", "a") shouldBe true
    }

    @Test
    fun `TextUtils htmlEncode escapes HTML`() {
        TextUtils.htmlEncode("<b>test</b>") shouldBe "&lt;b&gt;test&lt;/b&gt;"
    }

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

    // ── Pair ────────────────────────────────────────────────────────────────

    @Test
    fun `Pair stores first and second`() {
        val pair = Pair("hello", 42)
        pair.first shouldBe "hello"
        pair.second shouldBe 42
    }

    @Test
    fun `Pair create factory works`() {
        val pair = Pair.create("a", "b")
        pair.first shouldBe "a"
        pair.second shouldBe "b"
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
