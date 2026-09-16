package mihon.desktop.image

import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class DesktopImageLoaderTest {

    @Test
    fun `plain and source cover requests use the managed global and plugin clients`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            repeat(4) {
                server.enqueue(
                    MockResponse.Builder()
                        .setHeader("Content-Type", "image/png")
                        .body(Buffer().write(PNG))
                        .build(),
                )
            }
            val globalClient = routedClient("global")
            val pluginClient = routedClient("plugin")
            val imageLoader = createDesktopImageLoader(
                context = PlatformContext.INSTANCE,
                defaultCallFactory = globalClient,
                sourceCallFactory = { pluginClient },
                sourceHeaders = { Headers.headersOf("Referer", "https://source.example/") },
            )

            try {
                // Repeated URLs must still exercise both client routes rather than Coil's caches.
                repeat(2) {
                    val global = imageLoader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .data(server.url("/global.png").toString())
                            .build(),
                    )
                    val plugin = imageLoader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .data(DesktopSourceImage(server.url("/plugin.png").toString(), sourceId = 42L))
                            .build(),
                    )

                    assertEquals(DataSource.NETWORK, assertInstanceOf(SuccessResult::class.java, global).dataSource)
                    assertEquals(DataSource.NETWORK, assertInstanceOf(SuccessResult::class.java, plugin).dataSource)
                    val globalRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                        "Expected global network request"
                    }
                    val pluginRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                        "Expected plugin network request"
                    }
                    assertEquals("global", globalRequest.headers["X-Mihon-Route"])
                    assertEquals("plugin", pluginRequest.headers["X-Mihon-Route"])
                    assertEquals("https://source.example/", pluginRequest.headers["Referer"])
                }
            } finally {
                imageLoader.shutdown()
            }
        }
    }

    @Test
    fun `source image model keeps local files on the local Coil path`() {
        assertEquals("file:///tmp/cover.jpg", desktopSourceImageModel("file:///tmp/cover.jpg", 42L))
        assertEquals(null, desktopSourceImageModel(null, 42L))
        assertEquals(
            DesktopSourceImage("https://example.com/cover.jpg", 42L),
            desktopSourceImageModel("https://example.com/cover.jpg", 42L),
        )
    }

    @Test
    fun `source cover HTTP failure stays on the plugin route and becomes a Coil error`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(403).build())
            val imageLoader = createDesktopImageLoader(
                context = PlatformContext.INSTANCE,
                defaultCallFactory = routedClient("global"),
                sourceCallFactory = { routedClient("plugin") },
                sourceHeaders = { null },
            )

            try {
                val result = imageLoader.execute(
                    ImageRequest.Builder(PlatformContext.INSTANCE)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .data(DesktopSourceImage(server.url("/forbidden.png").toString(), sourceId = 42L))
                        .build(),
                )

                assertInstanceOf(ErrorResult::class.java, result)
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                    "Expected plugin failure request"
                }
                assertEquals("plugin", request.headers["X-Mihon-Route"])
            } finally {
                imageLoader.shutdown()
            }
        }
    }

    private fun routedClient(route: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-Mihon-Route", route)
                    .build(),
            )
        }
        .build()

    private companion object {
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
