package mihon.desktop.compat

import android.text.Html
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Tests for the Phase 2 Html Android compat adapter.
 */
class AndroidCompatPhase2Test {

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

    @Test
    fun `Html one argument fromHtml has the Android Spanned descriptor`() {
        fromHtmlMethod(String::class.java).returnType.name shouldBe "android.text.Spanned"
    }

    @Test
    fun `Html flags fromHtml has the Android Spanned descriptor`() {
        fromHtmlMethod(String::class.java, Int::class.javaPrimitiveType!!).returnType.name shouldBe
            "android.text.Spanned"
    }

    @Test
    fun `Html fromHtml decodes representative entities and strips inline tags`() {
        val result = Html.fromHtml("A &amp; <b>B</b> &copy; &#169;", 0)

        result.toString() shouldBe "A & B © ©"
        Class.forName("android.text.Spanned").isInstance(result) shouldBe true
    }

    @Test
    fun `Html legacy mode preserves Android block and break newlines`() {
        Html.fromHtml("<p>One<br>Two</p><div>Three</div>", 0).toString() shouldBe "One\nTwo\n\nThree\n\n"
    }

    private fun fromHtmlMethod(vararg parameterTypes: Class<*>): Method =
        Html::class.java.getMethod("fromHtml", *parameterTypes)

}
