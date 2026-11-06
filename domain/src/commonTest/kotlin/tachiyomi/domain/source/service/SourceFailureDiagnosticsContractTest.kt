package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceFailureIdentity
import eu.kanade.tachiyomi.network.SourceOperationPhase
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceFailureDiagnosticsContractTest {
    @Test
    fun `records frozen owner phase and original frame without throwable messages`() {
        val original = NoSuchMethodError("private-query https://private.example/path?token=credential")
        original.stackTrace = arrayOf(StackTraceElement("extension.RealInterceptor", "intercept", "Private.kt", 53))
        val cause = SourceCompatibilityException(
            original,
            SourceOperationPhase.SEARCH,
            SourceFailureIdentity(9L, "trusted.package", "Trusted extension", "1.2.3", 123L),
        )
        val error = AppError.ExtensionIncompatible(IOException("private transport details", cause))
        val records = mutableListOf<String>()
        error.recordSourceFailureDiagnostics(records::add, 1234L)
        val report = records.single()
        assertTrue(report.contains("source=9, package=trusted.package"), report)
        assertTrue(report.contains("version=1.2.3, versionCode=123"), report)
        assertTrue(report.contains("operation=SEARCH, kind=NoSuchMethodError"), report)
        assertTrue(report.contains("extension.RealInterceptor.intercept:53"), report)
        assertTrue(report.contains("1234"), report)
        listOf("private-query", "private.example", "credential", "private transport details").forEach {
            assertFalse(report.contains(it), report)
        }
        assertSame(original, cause.cause)
    }

    @Test
    fun `unknown is explicit and oversized records fit one bounded log entry`() {
        val original = NoClassDefFoundError("private class details")
        original.stackTrace = Array(400) { StackTraceElement("x".repeat(200), "call", "Unused.kt", it) }
        val records = mutableListOf<String>()
        val identity = SourceFailureIdentity(
            extensionPackage = "包".repeat(500),
            extensionName = "x" + "\uD83D\uDE00".repeat(200),
            versionName = "版".repeat(500),
        )
        AppError.ExtensionIncompatible(SourceCompatibilityException(original, null, identity))
            .recordSourceFailureDiagnostics(records::add, 1L)
        val report = records.single()
        assertTrue(report.contains("source=unknown"), report)
        assertTrue(report.contains("operation=unknown"), report)
        assertTrue(report.contains("versionCode=unknown"), report)
        Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(report))
        assertTrue(report.toByteArray(Charsets.UTF_8).size < 4096)
        records.clear()
        AppError.ExtensionIncompatible(null).recordSourceFailureDiagnostics(records::add, 1L)
        assertTrue(records.single().contains("package=unknown"))
    }

    @Test
    fun `ordinary sink failure preserves primary error while source controls still propagate`() {
        val original = NoSuchMethodError("original")
        val error = AppError.ExtensionIncompatible(original)
        var attempts = 0
        error.recordSourceFailureDiagnostics({
            attempts++
            throw IOException("disk unavailable")
        }, 1L)
        assertEquals(1, attempts)
        assertSame(original, error.cause)
        val cancelled = CancellationException("cancelled")
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                error.recordSourceFailureDiagnostics({ throw IOException("wrapped", cancelled) }, 1L)
            },
        )
        val fatal = object : VirtualMachineError("controlled") {}
        assertSame(
            fatal,
            assertThrows(fatal.javaClass) {
                error.recordSourceFailureDiagnostics({ throw IOException("wrapped", fatal) }, 1L)
            },
        )
        val hostBug = NoSuchMethodError("host logger")
        assertSame(
            hostBug,
            assertThrows(NoSuchMethodError::class.java) {
                error.recordSourceFailureDiagnostics({ throw IOException("wrapped", hostBug) }, 1L)
            },
        )
        var deep: Throwable = cancelled
        repeat(65) { deep = IOException("wrapper", deep) }
        val tail = deep
        assertSame(
            tail,
            assertThrows(IOException::class.java) {
                error.recordSourceFailureDiagnostics({ throw tail }, 1L)
            },
        )
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                AppError.ExtensionIncompatible(ExceptionInInitializerError(cancelled))
                    .recordSourceFailureDiagnostics({ error("must not write") }, 1L)
            },
        )
    }

    @Test
    fun `ordinary source errors and cancellation produce no compatibility record`() {
        val records = mutableListOf<String>()
        listOf(AppError.Cancelled, AppError.Network(IOException("private")), AppError.MalformedData(null))
            .forEach { it.recordSourceFailureDiagnostics(records::add, 1L) }
        assertTrue(records.isEmpty())
    }
}
