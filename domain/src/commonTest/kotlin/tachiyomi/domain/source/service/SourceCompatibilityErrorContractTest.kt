package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.network.AppErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import rx.exceptions.OnErrorNotImplementedException
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class SourceCompatibilityErrorContractTest {
    @Test
    fun `linkage classification precedes IO reflection and Rx wrappers preserving original cause`() {
        listOf(
            NoSuchMethodError("future-method"),
            NoClassDefFoundError("future-class"),
            AbstractMethodError("future-interface"),
            NoSuchFieldError("future-field"),
        ).forEach { linkage ->
            listOf<Throwable>(
                linkage,
                IOException("transport", linkage),
                InvocationTargetException(linkage),
                OnErrorNotImplementedException(linkage),
            ).forEach { original ->
                val mapped = original.toSourceAppError()
                assertEquals("ExtensionIncompatible", mapped::class.simpleName)
                assertSame(original, mapped.cause)
            }
        }
    }

    @Test
    fun `fatal cause takes priority over cancellation IO AppError and initializer linkage`() {
        listOf<Throwable>(object : VirtualMachineError("controlled-vm") {}, ThreadDeath()).forEach { fatal ->
            val cancellation = CancellationException("cancel-before-fatal").apply { initCause(fatal) }
            listOf<Throwable>(
                fatal,
                IOException("transport", fatal),
                ExceptionInInitializerError(fatal),
                IOException("transport", ExceptionInInitializerError(cancellation)),
                AppErrorException(AppError.Network(fatal)),
            ).forEach { wrapped ->
                assertSame(fatal, assertThrows(fatal.javaClass) { wrapped.toSourceAppError() })
            }
        }
    }

    @Test
    fun `cancellation is preserved before compatibility or stable AppError wrappers`() {
        val cancellation = CancellationException("controlled-cancel")
        listOf<Throwable>(
            IOException("transport", cancellation),
            ExceptionInInitializerError(cancellation),
            AppErrorException(AppError.Network(cancellation)),
        ).forEach { assertSame(AppError.Cancelled, it.toSourceAppError()) }
    }

    @Test
    fun `cause traversal uses identity terminates cycles and refuses unknown deep tails`() {
        val linkage = NoSuchMethodError("future")
        class EqualWrapper(cause: Throwable) : RuntimeException(cause) {
            override fun equals(other: Any?) = other is EqualWrapper
            override fun hashCode() = 7
        }
        assertEquals("ExtensionIncompatible", EqualWrapper(EqualWrapper(linkage)).toSourceAppError()::class.simpleName)
        val cycle = object : IOException("cycle") {
            override val cause: Throwable get() = this
        }
        assertEquals("Network", cycle.toSourceAppError()::class.simpleName)
        var deep: Throwable = ExceptionInInitializerError(object : VirtualMachineError("hidden") {})
        repeat(64) { deep = IOException("depth-$it", deep) }
        val original = deep
        assertSame(original, assertThrows(IOException::class.java) { original.toSourceAppError() })
        val deepLinkage = NoSuchMethodError("outer-linkage").apply { initCause(original) }
        assertSame(deepLinkage, assertThrows(NoSuchMethodError::class.java) { deepLinkage.toSourceAppError() })
    }

    @Test
    fun `ordinary HTTP network parser and stable app errors retain their contracts`() {
        val expected = mapOf(401 to "Authentication", 403 to "Forbidden", 429 to "RateLimited", 500 to "Server")
        expected.forEach { (status, type) ->
            assertEquals(type, HttpException(status).toSourceAppError()::class.simpleName)
        }
        assertEquals("Network", IOException("offline").toSourceAppError()::class.simpleName)
        assertEquals("MalformedData", IllegalArgumentException("parser").toSourceAppError()::class.simpleName)
        val stable = AppError.RateLimited(12)
        assertSame(stable, AppErrorException(stable).toSourceAppError())
    }

    @Test
    fun `compatibility storage preserves category and old unknown payload remains readable`() {
        val stored = StoredAppError("ExtensionIncompatible", message = "future ABI")
        val restored = Json.decodeFromString<StoredAppError>(Json.encodeToString(stored)).toAppError()
        assertEquals("ExtensionIncompatible", restored::class.simpleName)
        assertEquals("ExtensionIncompatible", restored.toStoredAppError().type)
        assertEquals("future ABI", restored.cause?.message)
        assertEquals("Unknown", StoredAppError("FutureUnknownType").toAppError()::class.simpleName)
    }

    @Test
    fun `transported compatibility failure has no login challenge or automatic retry`() = runBlocking {
        var calls = 0
        val source = object : Source {
            override val id = 7L
            override val name = "controlled"
            override suspend fun getPopularManga(page: Int): MangasPage {
                calls++
                throw IOException("transport", NoSuchMethodError("future"))
            }
        }
        val request = SourcePageRequest(source.id, 1, 1L, SourceQuery.Popular)
        val failure = SourceMangaSearchService().loadPageResult(source, request) as SourcePageResult.Failure
        assertEquals("ExtensionIncompatible", failure.error::class.simpleName)
        assertEquals(SourceRecoveryAction.None, failure.recoveryAction)
        assertEquals(1, calls)
    }
}
