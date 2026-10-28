package mihon.desktop.reader

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourcePageFetcherNetworkPolicyTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `failed source network getters never send a fallback request`() = runBlocking {
        for (failHeaders in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                val failure = IllegalStateException("source network policy unavailable")
                val client = OkHttpClient()
                val source = object : CatalogueSource by mockk<CatalogueSource>() {
                    fun getClient(): OkHttpClient = if (failHeaders) client else throw failure
                    fun getHeaders(): Headers = if (failHeaders) throw failure else Headers.EMPTY
                }
                val destination = File(tempDir, "$failHeaders.png")
                val result = SourcePageFetcher(source, client).fetchToDestination(
                    Page(0, imageUrl = server.url("/protected.png").toString()),
                    destination,
                )
                val error = assertInstanceOf(SourcePageFetchResult.Failure::class.java, result).error
                assertInstanceOf(IllegalStateException::class.java, error.cause)
                assertEquals(failure.message, error.cause?.message)
                assertEquals(0, server.requestCount)
                assertFalse(destination.exists())
            }
        }
    }
}
