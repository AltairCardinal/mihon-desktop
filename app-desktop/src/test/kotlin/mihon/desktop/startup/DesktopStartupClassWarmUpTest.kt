package mihon.desktop.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopStartupClassWarmUpTest {

    @Test
    fun `the warm up list only contains resolvable class names`() {
        val names = DesktopStartupClassWarmUp.classNames()
        assertTrue(names.size > 100, "the warm up list is unexpectedly small: ${names.size}")
        assertTrue(names.none { it.contains("\$\$Lambda") || it.contains("/0x") }, "synthetic lambda names are not stable between runs")
        val loader = DesktopStartupClassWarmUp.classLoader()
        val resolvable = names.count { name ->
            runCatching { Class.forName(name, false, loader) }.isSuccess
        }
        assertTrue(
            resolvable * 100 >= names.size * 90,
            "only $resolvable of ${names.size} warm up classes resolve, the list is stale",
        )
    }

    @Test
    fun `warming up loads the classes and ignores names that cannot be resolved`() {
        val loaded = DesktopStartupClassWarmUp.warmUp(
            listOf(
                "java.lang.String",
                "mihon.desktop.startup.DesktopStartupClassWarmUp",
                "mihon.desktop.startup.DoesNotExistOnPurpose",
            ),
        )

        assertEquals(2, loaded)
    }

    @Test
    fun `warming up an empty list loads nothing`() {
        assertEquals(0, DesktopStartupClassWarmUp.warmUp(emptyList()))
    }

    @Test
    fun `the warm up prepares the metadata of the class the navigation library asks for`() {
        // The navigation library asks a class for its qualified name while the first
        // user interface is composed. With the full reflection implementation on the
        // class path that answer deserialises the class metadata, which loads several
        // hundred classes on the thread that composes the user interface.
        val result = DesktopStartupClassWarmUp.warmUpDetailed(emptyList())

        assertEquals(0, result.loadedClasses)
        assertEquals(
            "cafe.adriel.voyager.core.model.ScreenModel",
            result.reflectionName,
        )
    }

    @Test
    fun `the reflection warm up does not fail when the class is not reachable`() {
        // A loader without a parent cannot see the application classes.
        val isolating = object : ClassLoader(null) {}

        val result = DesktopStartupClassWarmUp.warmUpDetailed(
            names = emptyList(),
            loader = isolating,
        )

        assertEquals(0, result.loadedClasses)
        assertNull(result.reflectionName)
    }

    @Test
    fun `starting the warm up records its start in the startup trace`() {
        DesktopStartupClassWarmUp.startAsync()

        val phases = DesktopStartupTrace.recorded().map { it.phase }
        assertTrue(
            phases.contains("warmup-start"),
            "the warm up did not record its start: $phases",
        )
    }
}
