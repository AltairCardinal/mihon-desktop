package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SourceRegistrationContractTest {
    @Test
    fun `registration equality keeps distinct equal sources separate without metadata or hash probes`() {
        val first = ForeignSource()
        val second = ForeignSource()
        val registered = SourceRegistration(first, 7L)
        assertEquals(registered, SourceRegistration(first, 7L))
        assertEquals(registered.hashCode(), SourceRegistration(first, 7L).hashCode())
        assertNotEquals(registered, SourceRegistration(second, 7L))
        assertNotEquals(registered, SourceRegistration(first, 8L))
        assertEquals(0, first.probes + second.probes)
    }

    private class ForeignSource : Source {
        var probes = 0
        override val id: Long get() {
            probes++
            throw NoSuchMethodError("foreign id")
        }
        override val name: String get() {
            probes++
            throw NoSuchMethodError("foreign name")
        }
        override fun equals(other: Any?) = other is ForeignSource
        override fun hashCode(): Int {
            probes++
            throw AssertionError("foreign hash must not be called")
        }
    }
}
