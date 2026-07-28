package mihon.desktop.compat

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.util.Linkify
import android.webkit.CookieManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

class AndroidExtensionAbiCompatibilityTest {

    @Test
    fun `ContextWrapper exposes the Android external cache ABI`() {
        val base = Context()
        val wrapper = ContextWrapper(base)

        val method = ContextWrapper::class.java.getMethod("getExternalCacheDir")
        assertEquals(File::class.java, method.returnType)
        assertEquals(base.getExternalCacheDir(), method.invoke(wrapper))
    }

    @Test
    fun `Build exposes the public static ID field`() {
        val field = Build::class.java.getField("ID")

        assertTrue(Modifier.isPublic(field.modifiers))
        assertTrue(Modifier.isStatic(field.modifiers))
        assertEquals(String::class.java, field.type)
        assertTrue((field.get(null) as String).isNotBlank())
    }

    @Test
    fun `CookieManager singleton retains cookies for extension clients`() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookie()

        manager.setCookie("https://example.com/path", "session=one")
        manager.setCookie("https://example.com/other", "adult=yes")

        assertSame(manager, CookieManager.getInstance())
        assertEquals("session=one; adult=yes", manager.getCookie("https://example.com/reader"))
    }

    @Test
    fun `SpannableString has the Android verifier shape used by Linkify`() {
        val text: Spannable = SpannableString("https://example.com")

        assertEquals("https://example.com", text.toString())
        assertNotNull(Spannable::class.java)
        assertTrue(Linkify.addLinks(text, Linkify.WEB_URLS))
    }

    @Test
    fun `current extension API can resolve OkHttp Zstd support`() {
        assertNotNull(Class.forName("okhttp3.zstd.Zstd"))
    }
}
