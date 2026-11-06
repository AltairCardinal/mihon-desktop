package eu.kanade.tachiyomi.network

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticNetworkInterceptor
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticRequestContext
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidNetworkHelperExtensionCompatibilityTest {

    @Test
    fun `future linkage is contained on default aliases and derived source clients`() {
        val helper = networkHelper()
        try {
            @Suppress("DEPRECATION")
            ExtensionLinkageNetworkContract.verify(
                listOf(helper.client, helper.nonCloudflareClient, helper.cloudflareClient, helper.clientForSource(7L)),
            )
        } finally {
            helper.client.cache?.close()
        }
    }

    @Test
    fun `default client keeps production guards without legacy compression interceptors`() {
        val helper = networkHelper()

        assertTrue(helper.client.interceptors.any { it.javaClass.simpleName == "UncaughtExceptionInterceptor" })
        assertTrue(helper.client.interceptors.any { it.javaClass.simpleName == "UserAgentInterceptor" })
        assertFalse(
            helper.client.networkInterceptors.any {
                it.javaClass.simpleName == "IgnoreGzipInterceptor" ||
                    it.javaClass.simpleName == "BrotliInterceptor"
            },
        )
    }

    @Test
    fun `default client negotiates transparent gzip for legacy source responses`() {
        val helper = networkHelper()
        MockWebServer().also { it.start() }.use { server ->
            val compressed = ByteArrayOutputStream().also { output ->
                GZIPOutputStream(output).use { gzip ->
                    gzip.write("legacy source response".toByteArray(Charsets.UTF_8))
                }
            }
            server.enqueue(
                MockResponse.Builder()
                    .headers(Headers.headersOf("Content-Encoding", "gzip"))
                    .body(okio.Buffer().write(compressed.toByteArray()))
                    .build(),
            )

            helper.client.newCall(
                Request.Builder()
                    .url(server.url("/legacy-gzip"))
                    .build(),
            ).execute().use { response ->
                assertEquals("legacy source response", response.body.string())
            }
            assertEquals("gzip", server.takeRequest().headers["Accept-Encoding"])
        }
    }

    @Test
    fun `production client disables raw headers even when legacy verbose preference is enabled`() {
        val application = RuntimeEnvironment.getApplication()
        val prefs = application.getSharedPreferences("fictional-feedback-verbose", Context.MODE_PRIVATE)
        val helper = NetworkHelper(application, NetworkPreferences(AndroidPreferenceStore(application, prefs), true))
        assertFalse(helper.client.interceptors.any { it is okhttp3.logging.HttpLoggingInterceptor })
        assertFalse(helper.client.networkInterceptors.any { it is okhttp3.logging.HttpLoggingInterceptor })
    }

    @Test
    fun `production network records typed reader status but never private feedback requests`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = java.nio.file.Files.createTempDirectory("fictional-network-diagnostics").toFile()
        val store = DiagnosticStore(
            directory,
            object : DiagnosticPersistence {
                var session: DiagnosticSession? = null
                override fun load() = session
                override fun save(session: DiagnosticSession?) {
                    this.session = session
                }
            },
            scope,
        )
        DiagnosticEvents.install(store)
        try {
            val helper = networkHelper()
            assertTrue(helper.client.interceptors.any { it is DiagnosticNetworkInterceptor })
            MockWebServer().also { it.start() }.use { server ->
                store.start(DiagnosticCategory.READER)
                server.enqueue(MockResponse(code = 503, body = "fictional private body"))
                helper.nonCloudflareClient.newCall(
                    Request.Builder().url(server.url("/private-fictional-path"))
                        .header("Authorization", "Bearer fictional-private-token")
                        .tag(
                            DiagnosticRequestContext::class.java,
                            DiagnosticRequestContext(
                                DiagnosticCategory.READER,
                                DiagnosticOperation.READER_IMAGE,
                            ),
                        ).build(),
                ).execute().close()
                store.flush()
                val text = store.read(DiagnosticCategory.READER, LocalDate.now().toString())!!.content
                assertTrue(text.contains("READER_IMAGE"))
                assertTrue(text.contains("503"))
                assertFalse(text.contains("private"))
                store.stop()
                store.start(DiagnosticCategory.SOURCE_NETWORK)
                server.enqueue(MockResponse(body = "fictional feedback response"))
                helper.nonCloudflareClient.newBuilder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/private-feedback")).build())
                }.build().newCall(
                    Request.Builder()
                        .url("http://mihon-feedback.mihon-altaircardinal.workers.dev/api/feedback").build(),
                ).execute().close()
                store.flush()
                assertTrue(store.dates(DiagnosticCategory.SOURCE_NETWORK).isEmpty())
            }
        } finally {
            DiagnosticEvents.install(null)
            scope.coroutineContext[Job]?.cancel()
            directory.deleteRecursively()
        }
        Unit
    }

    private fun networkHelper(): NetworkHelper {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "aex01-network-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        return NetworkHelper(
            application,
            NetworkPreferences(AndroidPreferenceStore(application, preferences)),
        )
    }
}
