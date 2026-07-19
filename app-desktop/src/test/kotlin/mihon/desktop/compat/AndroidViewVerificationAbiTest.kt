package mihon.desktop.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

class AndroidViewVerificationAbiTest {

    @Test
    fun `View verifier ABI exposes exact binary descriptors and fails fast without a UI engine`() {
        val context = Class.forName("android.content.Context")
        val view = Class.forName("android.view.View")
        val measureSpec = Class.forName("android.view.View\$MeasureSpec")
        val viewGroup = Class.forName("android.view.ViewGroup")
        val layoutParams = Class.forName("android.view.ViewGroup\$LayoutParams")
        val textView = Class.forName("android.widget.TextView")
        val editText = Class.forName("android.widget.EditText")
        val button = Class.forName("android.widget.Button")

        assertTrue(view.isAssignableFrom(viewGroup))
        assertTrue(Modifier.isAbstract(viewGroup.modifiers))
        assertThrows(NoSuchMethodException::class.java) { view.getConstructor() }
        val viewConstructor = view.getConstructor(context)
        viewGroup.getConstructor(context)
        listOf(textView, editText, button).forEach { widget ->
            widget.getConstructor(context)
            assertThrows(NoSuchMethodException::class.java) { widget.getConstructor() }
        }
        val setLayoutParams = view.getMethod("setLayoutParams", layoutParams)
        val measure = view.getMethod("measure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val layout = view.getMethod(
            "layout",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val makeMeasureSpec = measureSpec.getMethod(
            "makeMeasureSpec",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        assertTrue(Modifier.isStatic(makeMeasureSpec.modifiers))
        assertEquals(Int::class.javaPrimitiveType, makeMeasureSpec.returnType)
        val params = layoutParams.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(320, 640)
        val width = layoutParams.getField("width")
        val height = layoutParams.getField("height")
        listOf(width, height).forEach { field ->
            assertTrue(Modifier.isPublic(field.modifiers))
            assertFalse(Modifier.isFinal(field.modifiers))
        }
        assertEquals(320, width.getInt(params))
        assertEquals(640, height.getInt(params))
        width.setInt(params, 480)
        height.setInt(params, 800)
        assertEquals(480, width.getInt(params))
        assertEquals(800, height.getInt(params))
        assertEquals(0x40000438, makeMeasureSpec.invoke(null, 1080, 0x40000000))

        val contextInstance = context.getConstructor().newInstance()
        val instance = viewConstructor.newInstance(contextInstance)
        val storedContext = view.getDeclaredField("context").apply { isAccessible = true }
        assertSame(contextInstance, storedContext.get(instance))
        listOf(
            { setLayoutParams.invoke(instance, params) },
            { measure.invoke(instance, 1, 2) },
            { layout.invoke(instance, 1, 2, 3, 4) },
        ).forEach { call ->
            val failure = assertThrows(InvocationTargetException::class.java) { call() }
            assertTrue(failure.targetException is UnsupportedOperationException)
        }
    }
}
