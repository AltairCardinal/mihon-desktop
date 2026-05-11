package mihon.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
    fun `returns Error when block throws`() = runBlocking {
        val result = safeSourceCall<Int> { throw RuntimeException("network error") }
        result.shouldBeInstanceOf<SourceCallResult.Error>()
        val err = result as SourceCallResult.Error
        assert(err.message.contains("network error")) { "Error should include exception message: ${err.message}" }
    }

    @Test
    fun `returns Timeout when block exceeds timeoutMs`() = runBlocking {
        val result = safeSourceCall<Int>(timeoutMs = 50L) { delay(5_000); 99 }
        result.shouldBeInstanceOf<SourceCallResult.Timeout>()
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
