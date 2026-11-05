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
    fun `nested guard keeps the frozen owner and precise phase through transport wrappers`() {
        val owner = SourceFailureIdentity(9L, "old.package", "Old extension", "1.2.3", 123L)
        val linkage = NoSuchMethodError("future API")
        val original = SourceCompatibilityException(linkage, SourceOperationPhase.FILTERS, owner)
        val transport = IOException("transport", InvocationTargetException(original))
        val guarded = assertThrows(SourceCompatibilityException::class.java) {
            guardSourceOperation(SourceOperationPhase.SEARCH) { throw transport }
        }
        assertSame(owner, guarded.identity)
        assertEquals(SourceOperationPhase.FILTERS, guarded.phase)
        assertSame(transport, guarded.cause)
        val relabeled = guarded.withSourceFailureIdentity(
            SourceFailureIdentity(9L, "new.package", "New extension", "9.9.9", 999L),
        )
        assertSame(guarded, relabeled)
        val legacyOuter = SourceCompatibilityException(original, SourceOperationPhase.IMAGE_REQUEST)
        val promoted = assertThrows(SourceCompatibilityException::class.java) {
            guardSourceOperation(SourceOperationPhase.SEARCH) { throw legacyOuter }
        }
        assertSame(owner, promoted.identity)
        assertEquals(SourceOperationPhase.IMAGE_REQUEST, promoted.phase)
        assertSame(legacyOuter, promoted.cause)
    }

    @Test
    fun `identity diagnostic fields are bounded single line and unknown is explicit`() {
        val owner = SourceFailureIdentity(
            extensionPackage = "package\r\nforged",
            extensionName = "x".repeat(500),
            versionName = "1\u2028forged\u2029line",
        )
        val label = owner.diagnosticLabel()
        org.junit.jupiter.api.Assertions.assertFalse(
            label.any {
                it.isISOControl() || it == '\u2028' || it == '\u2029'
            },
        )
        org.junit.jupiter.api.Assertions.assertTrue(label.length < 450)
        org.junit.jupiter.api.Assertions.assertTrue(label.contains("source=unknown"))
        org.junit.jupiter.api.Assertions.assertTrue(label.contains("versionCode=unknown"))
    }

    @Test
    fun `legacy two argument JVM constructor remains callable`() {
        val cause = NoSuchMethodError("legacy")
        val value = SourceCompatibilityException::class.java
            .getConstructor(Throwable::class.java, SourceOperationPhase::class.java)
            .newInstance(cause, SourceOperationPhase.SEARCH)
        assertSame(cause, value.cause)
        assertEquals(SourceOperationPhase.SEARCH, value.phase)
        assertEquals(null, value.identity)
    }

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
