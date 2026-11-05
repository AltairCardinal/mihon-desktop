package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import kotlinx.coroutines.CancellationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceFailureBoundaryContractTest {
    @Test
    fun `ordinary HTTP statuses and cancelled calls keep their transport behavior`() {
        val client = OkHttpClient.Builder().addInterceptor(UncaughtExceptionInterceptor()).build()
        MockWebServer().also { it.start() }.use { server ->
            listOf(401, 403, 429, 500).forEach { status ->
                server.enqueue(MockResponse(code = status, body = "ordinary HTTP"))
                client.newCall(Request.Builder().url(server.url("/status/$status")).build()).execute().use {
                    assertEquals(status, it.code)
                }
            }
            val cancelled = client.newCall(Request.Builder().url(server.url("/cancelled")).build())
            cancelled.cancel()
            val failure = assertThrows(IOException::class.java) { cancelled.execute() }
            assertEquals("IOException", failure.javaClass.simpleName)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `real async future interceptor fails once without worker escape and next request works`() {
        ExtensionLinkageNetworkContract.verify(
            listOf(OkHttpClient.Builder().addInterceptor(UncaughtExceptionInterceptor()).build()),
        )
    }

    @Test
    fun `network boundary preserves fatal cancellation ordinary IO and unrelated errors`() {
        val fatal = object : VirtualMachineError("controlled") {}
        val cancellation = CancellationException("cancel")
        val io = IOException("offline")
        val assertion = AssertionError("host bug")
        var deep: Throwable = fatal
        repeat(64) { deep = IOException("depth-$it", deep) }
        val unknownLinkage = NoSuchMethodError("unknown deep tail").apply { initCause(deep) }
        listOf(
            IOException("wrapped", ExceptionInInitializerError(fatal)) to fatal,
            ExceptionInInitializerError(ThreadDeath()).let { it to it.cause!! },
            assertion to assertion,
            io to io,
            unknownLinkage to unknownLinkage,
        ).forEach { (thrown, expected) ->
            val client = OkHttpClient.Builder().addInterceptor(UncaughtExceptionInterceptor())
                .addInterceptor { throw thrown }.build()
            assertSame(
                expected,
                assertThrows(expected.javaClass) {
                    client.newCall(Request.Builder().url("http://localhost/unused").build()).execute()
                },
            )
        }
        listOf<Throwable>(
            cancellation,
            ExceptionInInitializerError(cancellation),
            IOException("cancel", cancellation),
        ).forEach { original ->
            val client = OkHttpClient.Builder().addInterceptor(UncaughtExceptionInterceptor())
                .addInterceptor { throw original }.build()
            val transport = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("http://localhost/unused").build()).execute()
            }
            if (original is IOException) assertSame(original, transport) else assertSame(original, transport.cause)
        }
    }
}
