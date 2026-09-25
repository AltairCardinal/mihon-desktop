package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class GitHubRetryAfterIntegrationTest {
    @Test
    fun `primary rate limit honors the later HTTP date or reset deadline`() = runTest {
        val server = MockWebServer()
        val resetEpochSeconds = 4_102_444_800L
        val retryEpochSeconds = resetEpochSeconds + 120
        val retryDate = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(retryEpochSeconds))
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", resetEpochSeconds.toString())
                .addHeader("Retry-After", retryDate)
                .body("""{"message":"API rate limit exceeded"}""")
                .build(),
        )
        server.start()
        try {
            val transport = GitHubSyncTransport(
                OkHttpClient(),
                tokenProvider = { "fixture-token" },
                apiBaseUrl = server.url("/").toString().removeSuffix("/"),
            )
            val failure = transport.readSnapshot(
                SyncRepository("fixture-owner", "private-sync", "mihon-sync"),
                expectedSpaceId = "space",
                expectedGeneration = 1,
            ).exceptionOrNull()
            val observedAtMillis = System.currentTimeMillis()

            assertTrue(failure is SyncHttpException)
            val rateLimit = failure as SyncHttpException
            assertEquals(SyncHttpFailureClass.RATE_LIMITED, rateLimit.failureClass)
            assertEquals(resetEpochSeconds, rateLimit.rateLimitResetEpochSeconds)
            assertNotNull(rateLimit.retryAfterMillis)
            val inferredDeadlineMillis = observedAtMillis + requireNotNull(rateLimit.retryAfterMillis)
            assertTrue(
                inferredDeadlineMillis >= retryEpochSeconds * 1_000L - 5_000L &&
                    inferredDeadlineMillis <= retryEpochSeconds * 1_000L + 5_000L,
                "retry deadline must use the later of Retry-After and x-ratelimit-reset",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `secondary rate limit without a server hint waits before retry`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("X-RateLimit-Remaining", "99")
                .addHeader("X-RateLimit-Reset", "4102444800")
                .body("""{"message":"You have exceeded a secondary rate limit."}""")
                .build(),
        )
        server.start()
        try {
            val transport = GitHubSyncTransport(
                OkHttpClient(),
                tokenProvider = { "fixture-token" },
                apiBaseUrl = server.url("/").toString().removeSuffix("/"),
            )
            val failure = transport.readSnapshot(
                SyncRepository("fixture-owner", "private-sync", "mihon-sync"),
                expectedSpaceId = "space",
                expectedGeneration = 1,
            ).exceptionOrNull()

            assertTrue(failure is SyncHttpException)
            val rateLimit = failure as SyncHttpException
            assertEquals(SyncHttpFailureClass.RATE_LIMITED, rateLimit.failureClass)
            assertTrue(requireNotNull(rateLimit.retryAfterMillis) in 55_000L..60_000L)
        } finally {
            server.close()
        }
    }
}
