package mihon.desktop.compat

import android.webkit.CookieManager
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for Phase 4: android.webkit.CookieManager (bridged to in-memory store).
 */
class AndroidCompatPhase4Test {

    @BeforeEach
    fun setUp() {
        CookieManager.getInstance().removeAllCookie()
    }

    @Test
    fun `getInstance returns singleton`() {
        val cm1 = CookieManager.getInstance()
        val cm2 = CookieManager.getInstance()
        cm1 shouldBe cm2
    }

    @Test
    fun `setCookie and getCookie round-trip`() {
        val cm = CookieManager.getInstance()
        cm.setCookie("https://example.com", "session=abc123")
        val cookies = cm.getCookie("https://example.com")
        cookies.shouldNotBeNull()
        cookies shouldContain "session=abc123"
    }

    @Test
    fun `getCookie returns null for unknown domain`() {
        val cm = CookieManager.getInstance()
        cm.getCookie("https://unknown.example.org").shouldBeNull()
    }

    @Test
    fun `multiple cookies for same domain`() {
        val cm = CookieManager.getInstance()
        cm.setCookie("https://example.com", "a=1")
        cm.setCookie("https://example.com", "b=2")
        val cookies = cm.getCookie("https://example.com")
        cookies.shouldNotBeNull()
        cookies shouldContain "a=1"
        cookies shouldContain "b=2"
    }

    @Test
    fun `removeAllCookie clears everything`() {
        val cm = CookieManager.getInstance()
        cm.setCookie("https://example.com", "key=val")
        cm.removeAllCookie()
        cm.getCookie("https://example.com").shouldBeNull()
    }

    @Test
    fun `hasCookies returns false when empty`() {
        val cm = CookieManager.getInstance()
        cm.hasCookies() shouldBe false
    }

    @Test
    fun `hasCookies returns true when cookies exist`() {
        val cm = CookieManager.getInstance()
        cm.setCookie("https://example.com", "x=y")
        cm.hasCookies() shouldBe true
    }

    @Test
    fun `acceptCookie defaults to true`() {
        CookieManager.getInstance().acceptCookie() shouldBe true
    }

    @Test
    fun `setAcceptCookie toggles acceptance`() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(false)
        cm.acceptCookie() shouldBe false
        cm.setAcceptCookie(true)
        cm.acceptCookie() shouldBe true
    }

    @Test
    fun `flush does not throw`() {
        CookieManager.getInstance().flush()
    }
}
