package eu.kanade.tachiyomi.network

import android.content.Context
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
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidNetworkHelperExtensionCompatibilityTest {

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
