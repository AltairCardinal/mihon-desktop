package tachiyomi.data.feedback

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import tachiyomi.domain.feedback.NativeFeedbackPayload
import tachiyomi.domain.feedback.NativeFeedbackSession

class NativeFeedbackHttpContractTest {
    private val id = "123e4567-e89b-42d3-a456-426614174000"
    private val secret = "s".repeat(43)
    private val expiry = System.currentTimeMillis() / 1000 + 600
    private val url = "${NativeFeedbackHttpService.ORIGIN}/verify.html#$id"
    private val session get() = NativeFeedbackSession(id, secret, expiry, url)
    private val payload get() = NativeFeedbackPayload(
        id,
        "Fictional title",
        "A fictional problem described for this HTTP test.",
        "Application: Mihon",
        "Fictional private crash\n",
    )

    @Test
    fun `real HTTP protocol sends only hash to create and secret only in poll header or final body`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse(
                    code = 201,
                    body = """{"sessionId":"$id","secret":"$secret","expiresAt":$expiry,"verificationUrl":"$url"}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"verified":true,"expiresAt":$expiry}"""))
            server.enqueue(
                MockResponse(code = 202, body = """{"status":"received_pending","receipt":"${"r".repeat(43)}"}"""),
            )
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Production-Route", "retained").build())
            }.build()
            val api = NativeFeedbackHttpService(client, server.url("/"))
            val created = api.createSession("a".repeat(64))
            assertEquals(session, created)
            assertTrue(api.pollSession(created).verified)
            assertEquals("r".repeat(43), api.submit(payload, created))
            val create = server.takeRequest()
            assertEquals("/api/native-session", create.url.encodedPath)
            assertEquals("retained", create.headers["X-Production-Route"])
            assertEquals(setOf("contentHash"), Json.parseToJsonElement(create.body!!.utf8()).jsonObject.keys)
            val poll = server.takeRequest()
            assertEquals("/api/native-session/$id", poll.url.encodedPath)
            assertEquals("Bearer $secret", poll.headers["Authorization"])
            assertEquals(0L, poll.bodySize)
            val sent = server.takeRequest()
            assertEquals("/api/feedback", sent.url.encodedPath)
            val body = sent.body!!.utf8()
            assertTrue("Fictional private crash" in body)
            val keys = Json.parseToJsonElement(body).jsonObject.keys
            assertTrue(keys.containsAll(setOf("nativeSessionId", "nativeSessionSecret", "crashLogConsent", "consent")))
            assertFalse("turnstileToken" in keys)
            assertFalse(url.contains(secret))
        }
    }

    @Test
    fun `unsafe destinations malformed fields and oversized responses fail closed`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val api = NativeFeedbackHttpService(OkHttpClient(), server.url("/"))
            for (body in listOf(
                "{}",
                "[]",
                "{",
                "x".repeat(17000),
                """{"sessionId":"$id","secret":"$secret","expiresAt":$expiry,""" +
                    """"verificationUrl":"https://attacker.example/verify.html#$id"}""",
                """{"sessionId":"$id","secret":"$secret","expiresAt":$expiry,"verificationUrl":"$url?body=private"}""",
                """{"sessionId":"wrong","secret":"$secret","expiresAt":$expiry,"verificationUrl":"$url"}""",
            )) {
                server.enqueue(MockResponse(code = 201, body = body))
                val error = try {
                    api.createSession("a".repeat(64))
                    null
                } catch (
                    failure: NativeFeedbackException,
                ) {
                    failure
                }
                assertNotNull(error)
            }
        }
    }

    @Test
    fun `403 429 500 and session conflicts produce safe typed recoverable failures`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val api = NativeFeedbackHttpService(OkHttpClient(), server.url("/"))
            for ((status, expected) in listOf(
                403 to NativeFeedbackFailure.EXPIRED,
                429 to NativeFeedbackFailure.RATE_LIMIT,
                500 to NativeFeedbackFailure.NETWORK,
                409 to NativeFeedbackFailure.CONFLICT,
            )) {
                server.enqueue(MockResponse(code = status, body = "private-server-error-and-token"))
                val failure = try {
                    api.submit(payload, session)
                    error("expected failure")
                } catch (
                    error: NativeFeedbackException,
                ) {
                    error
                }
                assertEquals(expected, failure.failure)
                assertFalse(failure.toString().contains("private-server"))
            }
        }
    }

    @Test
    fun `feedback suppresses logging and redirects while preserving client configuration`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            MockWebServer().also { it.start() }.use { other ->
                val logs = mutableListOf<String>()
                val logger = HttpLoggingInterceptor(logs::add).apply { level = HttpLoggingInterceptor.Level.BODY }
                val client = OkHttpClient.Builder().addNetworkInterceptor(logger).build()
                val api = NativeFeedbackHttpService(client, server.url("/"))
                server.enqueue(
                    MockResponse(
                        code = 307,
                        headers = okhttp3.Headers.headersOf("Location", other.url("/steal").toString()),
                    ),
                )
                other.enqueue(MockResponse(code = 500))
                try {
                    api.submit(payload, session)
                    fail("redirect must fail")
                } catch (_: NativeFeedbackException) { }
                assertEquals(0, other.requestCount)
                assertTrue(logs.isEmpty())
                assertEquals(HttpLoggingInterceptor.Level.BODY, logger.level)
            }
        }
    }
}
