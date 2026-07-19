package mihon.desktop.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

class AndroidViewVerificationAbiTest {

    @Test
    fun `View verifier ABI exposes exact binary descriptors and fails fast without a UI engine`() {
        val view = Class.forName("android.view.View")
        val measureSpec = Class.forName("android.view.View\$MeasureSpec")
        val viewGroup = Class.forName("android.view.ViewGroup")
        val layoutParams = Class.forName("android.view.ViewGroup\$LayoutParams")

        assertTrue(view.isAssignableFrom(viewGroup))
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
        assertEquals(320, layoutParams.getField("width").getInt(params))
        assertEquals(640, layoutParams.getField("height").getInt(params))
        assertEquals(0x40000438, makeMeasureSpec.invoke(null, 1080, 0x40000000))

        val instance = view.getConstructor().newInstance()
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
