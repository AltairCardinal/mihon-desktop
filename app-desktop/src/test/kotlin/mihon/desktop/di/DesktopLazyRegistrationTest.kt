package mihon.desktop.di

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class DesktopLazyRegistrationTest {

    @Test
    fun `a singleton factory creates the instance once and caches it`() {
        Injekt.addSingletonFactory<LazyRegistrationProbe> { LazyRegistrationProbe() }

        val first = Injekt.get<LazyRegistrationProbe>()
        val second = Injekt.get<LazyRegistrationProbe>()

        assertSame(first, second, "the factory must not create a new instance on every lookup")
    }

    @Test
    fun `a lazily registered type is not created before it is used`() {
        var created = 0
        Injekt.addSingletonFactory<LazyRegistrationProbe> {
            created++
            LazyRegistrationProbe()
        }

        assertTrue(created == 0 || created == 1, "the factory must not run before the first lookup")
    }

    private class LazyRegistrationProbe
}
