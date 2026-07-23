package eu.kanade.domain.ui.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.Collections

class AndroidThemeModeFacadeTest {

    @Test
    fun `shared and Android theme facades are unique and resolvable`() {
        val classLoader = requireNotNull(javaClass.classLoader)
        val sharedFacadeName = "eu.kanade.domain.ui.model.ThemeModeKt"
        val sharedResources = Collections.list(
            classLoader.getResources(sharedFacadeName.replace('.', '/') + ".class"),
        )

        assertEquals(1, sharedResources.size)
        Class.forName(sharedFacadeName, false, classLoader)
            .getDeclaredMethod("selectableAppThemes", Boolean::class.javaPrimitiveType)

        val androidFacade = Class.forName(
            "eu.kanade.domain.ui.model.AndroidThemeModeKt",
            false,
            classLoader,
        )
        val method = androidFacade.getDeclaredMethod(
            "setAppCompatDelegateThemeMode",
            ThemeMode::class.java,
        )
        assertTrue(Modifier.isPublic(method.modifiers))
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(Void.TYPE, method.returnType)
    }
}
