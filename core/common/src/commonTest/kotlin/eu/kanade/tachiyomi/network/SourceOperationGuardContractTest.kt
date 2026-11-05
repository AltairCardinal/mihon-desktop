package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class SourceOperationGuardContractTest {
    @Test
    fun `broad host catch preserves wrapped host linkage but permits marked source transport`() {
        val linkage = NoSuchMethodError("host database adapter")
        val wrapped = IOException("host operation", InvocationTargetException(linkage))
        assertSame(
            linkage,
            assertThrows(NoSuchMethodError::class.java) {
                wrapped.rethrowSourceControlFailure()
            },
        )
        val source = SourceCompatibilityException(linkage, SourceOperationPhase.SEARCH)
        IOException("outer", source).rethrowSourceControlFailure()
        val hostBug = AssertionError("host failure")
        assertSame(
            hostBug,
            assertThrows(AssertionError::class.java) {
                IOException("outer", hostBug).rethrowSourceControlFailure()
            },
        )
    }

    @Test
    fun `marked initializer linkage retains source classification through broad host catch`() {
        val initializer = ExceptionInInitializerError(AssertionError("extension init"))
        val source = assertThrows(SourceCompatibilityException::class.java) {
            guardSourceOperation(SourceOperationPhase.CONSTRUCTION) { throw initializer }
        }
        assertSame(initializer, source.cause)
        IOException("outer", source).rethrowSourceControlFailure()
    }

    @Test
    fun `nested wrappers retain precise phase and complete original cause chain`() {
        val linkage = NoSuchMethodError("future")
        val precise = SourceCompatibilityException(linkage, SourceOperationPhase.IMAGE_PARSE)
        val wrapper = IOException("outer transport", InvocationTargetException(precise))
        val error = assertThrows(SourceCompatibilityException::class.java) {
            guardSourceOperation(SourceOperationPhase.IMAGE_REQUEST) { throw wrapper }
        }
        assertEquals(SourceOperationPhase.IMAGE_PARSE, error.phase)
        assertSame(wrapper, error.cause)
        assertSame(
            precise,
            assertThrows(SourceCompatibilityException::class.java) {
                guardSourceOperation(SourceOperationPhase.IMAGE_REQUEST) { throw precise }
            },
        )
    }

    @Test
    fun `guard preserves fatal wrapped cancellation ordinary exception and unrelated Error`() {
        val fatal = object : VirtualMachineError("controlled") {}
        val cancellation = CancellationException("cancel")
        val ordinary = IOException("ordinary")
        val error = AssertionError("host bug")
        listOf<Throwable>(fatal, cancellation, ordinary, error).forEach { expected ->
            val thrown = if (expected === fatal ||
                expected === cancellation
            ) {
                ExceptionInInitializerError(expected)
            } else {
                expected
            }
            assertSame(
                expected,
                assertThrows(expected.javaClass) {
                    guardSourceOperation(SourceOperationPhase.SEARCH) { throw thrown }
                },
            )
        }
        var deep: Throwable = fatal
        repeat(64) { deep = IOException("depth", deep) }
        val original = deep
        assertSame(
            original,
            assertThrows(IOException::class.java) {
                guardSourceOperation(SourceOperationPhase.SEARCH) { throw original }
            },
        )
    }

    @Test
    fun `legacy single Throwable JVM constructor remains callable and unphased`() {
        val cause = NoSuchMethodError("legacy")
        val value = SourceCompatibilityException::class.java.getConstructor(Throwable::class.java).newInstance(cause)
        assertSame(cause, value.cause)
        assertEquals(null, value.phase)
    }
}
