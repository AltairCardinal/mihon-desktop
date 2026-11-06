package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class SourceMetadataSnapshotContractTest {
    @Test
    fun `excluded language does not execute the unneeded name getter`() {
        val source = MetadataSource().apply { nameFailure = NoSuchMethodError("must not execute") }
        val prepared = prepareSourceMetadata(source, 9L) { it == "fr" }
        assertEquals("en", prepared.language)
        assertNull(prepared.name)
        assertNull(prepared.error)
        assertEquals(0, source.nameReads)
        assertEquals(1, source.languageReads)
        assertEquals(0, source.idReads)
    }

    @Test
    fun `language controls and host policy errors propagate before a later source getter`() {
        val cancellation = CancellationException("controlled")
        val source = MetadataSource().apply { languageFailure = IOException("wrapped", cancellation) }
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                prepareSourceMetadata(source, 9L) { true }
            },
        )
        assertEquals(0, source.nameReads)
        val hostFailure = NoSuchMethodError("host language policy")
        val healthy = MetadataSource()
        assertSame(
            hostFailure,
            assertThrows(NoSuchMethodError::class.java) {
                prepareSourceMetadata(healthy, 9L) { throw hostFailure }
            },
        )
        assertEquals(0, healthy.nameReads)
        assertEquals(1, healthy.languageReads)
    }

    @Test
    fun `registered identity and one presentation read survive a poisoned ID getter`() {
        val source = MetadataSource()
        val prepared = prepareSourceMetadata(source, 9L)
        assertSame(source, prepared.source)
        assertEquals(9L, prepared.sourceId)
        assertEquals("Source name", prepared.name)
        assertEquals("en", prepared.language)
        assertNull(prepared.error)
        assertEquals(0, source.idReads)
        assertEquals(1, source.nameReads)
        assertEquals(1, source.languageReads)
        source.nameFailure = NoSuchMethodError("later name")
        source.languageFailure = NoSuchMethodError("later language")
        assertEquals("Source name", prepared.name)
        assertEquals("en", prepared.language)
        assertEquals(1, source.nameReads)
        assertEquals(1, source.languageReads)
    }

    @Test
    fun `unknown ID remains unknown and partial metadata failure preserves the exact compatibility cause`() {
        val original = NoSuchMethodError("private name details")
        val source = MetadataSource().apply { nameFailure = InvocationTargetException(original) }
        val prepared = prepareSourceMetadata(source, null)
        assertNull(prepared.sourceId)
        assertNull(prepared.name)
        assertEquals("en", prepared.language)
        val error = assertInstanceOf(AppError.ExtensionIncompatible::class.java, prepared.error)
        val marker = assertInstanceOf(SourceCompatibilityException::class.java, error.cause)
        assertEquals(SourceOperationPhase.METADATA, marker.phase)
        assertSame(original, marker.cause?.cause)
        assertEquals(0, source.idReads)
    }

    @Test
    fun `metadata controls and host errors propagate before another getter is invoked`() {
        val cancel = CancellationException("controlled")
        val fatal = object : VirtualMachineError("controlled") {}
        val host = AssertionError("controlled host bug")
        listOf(
            IOException("wrapped", cancel) to cancel,
            ExceptionInInitializerError(fatal) to fatal,
            host to host,
        ).forEach { (wrapped, expected) ->
            val source = MetadataSource().apply { nameFailure = wrapped }
            assertSame(expected, assertThrows(expected.javaClass) { prepareSourceMetadata(source, 9L) })
            assertEquals(0, source.idReads)
            assertEquals(0, source.languageReads)
        }
        var deep: Throwable = cancel
        repeat(65) { deep = IOException("wrapper", deep) }
        val tail = deep
        val source = MetadataSource().apply { nameFailure = tail }
        assertSame(tail, assertThrows(IOException::class.java) { prepareSourceMetadata(source, 9L) })
        assertEquals(0, source.languageReads)
    }

    @Test
    fun `independent metadata getters prefer compatibility over ordinary failure without invoking source equality`() {
        val source = MetadataSource().apply {
            nameFailure = IOException("ordinary metadata IO")
            languageFailure = NoClassDefFoundError("future language API")
        }
        val prepared = prepareSourceMetadata(source, 9L)
        assertInstanceOf(AppError.ExtensionIncompatible::class.java, prepared.error)
        assertNull(prepared.name)
        assertNull(prepared.language)
        assertSame(source, prepared.source)
        prepared.hashCode()
        assertEquals(0, source.idReads)
        assertEquals(1, source.nameReads)
        assertEquals(1, source.languageReads)
    }

    private class MetadataSource : Source {
        var idReads = 0
        var nameReads = 0
        var languageReads = 0
        var nameFailure: Throwable? = null
        var languageFailure: Throwable? = null
        override val id: Long get() {
            idReads++
            throw NoSuchMethodError("ID must not be read again")
        }
        override val name: String get() {
            nameReads++
            nameFailure?.let { throw it }
            return "Source name"
        }
        override val lang: String get() {
            languageReads++
            languageFailure?.let { throw it }
            return "en"
        }
        override fun equals(other: Any?): Boolean = error("Source equality must not be called")
        override fun hashCode(): Int = error("Source hash must not be called")
    }
}
