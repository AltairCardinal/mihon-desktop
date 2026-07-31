package mihon.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import eu.kanade.tachiyomi.network.HttpException
import java.io.IOException
import org.junit.jupiter.api.Test

/**
 * Tests for [safeSourceCall] — the unified timeout + error wrapper for extension API calls.
 */
class SourceCallWrapperTest {

    @Test
    fun `returns Success when block completes normally`() = runBlocking {
        val result = safeSourceCall { 42 }
        result.shouldBeInstanceOf<SourceCallResult.Success<Int>>()
        (result as SourceCallResult.Success).value shouldBe 42
    }

    @Test
    fun `classifies IO HTTP and parser failures with the shared source error contract`() = runBlocking {
        val network = safeSourceCall<Int> { throw IOException("connection reset") } as SourceCallResult.Error
        val authentication = safeSourceCall<Int> { throw HttpException(403) } as SourceCallResult.Error
        val malformed = safeSourceCall<Int> { throw IllegalArgumentException("broken document") } as SourceCallResult.Error

        network.error.shouldBeInstanceOf<AppError.Network>()
        authentication.error.shouldBeInstanceOf<AppError.Authentication>()
        malformed.error.shouldBeInstanceOf<AppError.MalformedData>()
    }

    @Test
    fun `preserves an extension supplied stable app error`() = runBlocking {
        val expected = AppError.RateLimited(12)
        val result = safeSourceCall<Int> { throw AppErrorException(expected) }
        result.shouldBeInstanceOf<SourceCallResult.Error>()
        (result as SourceCallResult.Error).error shouldBe expected
    }

    @Test
    fun `returns Timeout when block exceeds timeoutMs`() = runBlocking {
        val result = safeSourceCall<Int>(timeoutMs = 50L) { delay(5_000); 99 }
        result.shouldBeInstanceOf<SourceCallResult.Timeout>()
        (result as SourceCallResult.Timeout).error.shouldBeInstanceOf<AppError.Network>()
    }

    @Test
    fun `does not swallow IllegalStateException`() = runBlocking {
        val result = safeSourceCall<Int> { throw IllegalStateException("state error") }
        result.shouldBeInstanceOf<SourceCallResult.Error>()
    }

    @Test
    fun `default timeout is 30 seconds (sanity check via fast call)`() = runBlocking {
        // A fast call should always succeed regardless of default timeout
        val result = safeSourceCall { "done" }
        result.shouldBeInstanceOf<SourceCallResult.Success<String>>()
    }
}
