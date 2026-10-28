package mihon.desktop.image

import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.source.FakeDesktopSourceManager
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class DesktopImageLoaderTest {

    @Test
    fun `production source manager cover route preserves nonpublic source client and headers`() = runBlocking {
        withProductionSourceImage(PrivateNetworkSource(routedClient("source"))) { server, result ->
            assertInstanceOf(SuccessResult::class.java, result)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("source", request.headers["X-Mihon-Route"])
            assertEquals("https://source.example/", request.headers["Referer"])
        }
    }

    @Test
    fun `production source client getter failure never falls back to global network`() = runBlocking {
        val failure = IllegalStateException("Source client unavailable")
        withProductionSourceImage(NetworkSource(routedClient("source"), clientFailure = failure)) { server, result ->
            val error = assertInstanceOf(ErrorResult::class.java, result).throwable
            assertInstanceOf(IllegalStateException::class.java, error)
            assertEquals(failure.message, error.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `production source headers getter failure never sends a headerless image request`() = runBlocking {
        val failure = IllegalStateException("Source headers unavailable")
        withProductionSourceImage(NetworkSource(routedClient("source"), headersFailure = failure)) { server, result ->
            val error = assertInstanceOf(ErrorResult::class.java, result).throwable
            assertInstanceOf(IllegalStateException::class.java, error)
            assertEquals(failure.message, error.message)
            assertEquals(0, server.requestCount)
        }
    }

    private suspend fun withProductionSourceImage(
        source: Source,
        verify: (MockWebServer, coil3.request.ImageResult) -> Unit,
    ) {
        val directory = Files.createTempDirectory("mihon-source-image-route").toFile()
        try {
            DesktopNetworkHelper(
                cacheDir = directory.resolve("network"),
                cookieStorageFile = directory.resolve("cookies.json"),
            ).use { networkHelper ->
                MockWebServer().also { it.start() }.use { server ->
                    server.enqueue(
                        MockResponse.Builder()
                            .setHeader("Content-Type", "image/png")
                            .body(Buffer().write(PNG))
                            .build(),
                    )
                    val loader = createDesktopImageLoader(
                        PlatformContext.INSTANCE,
                        networkHelper,
                        FakeDesktopSourceManager(listOf(source)),
                    )
                    try {
                        val result = loader.execute(
                            ImageRequest.Builder(PlatformContext.INSTANCE)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .data(DesktopSourceImage(server.url("/source.png").toString(), source.id))
                                .build(),
                        )
                        verify(server, result)
                    } finally {
                        loader.shutdown()
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    open class NetworkSource(
        private val sourceClient: OkHttpClient,
        private val clientFailure: Throwable? = null,
        private val headersFailure: Throwable? = null,
    ) : Source {
        override val id = 42L
        override val name = "image-source"

        open fun getClient(): OkHttpClient {
            clientFailure?.let { throw it }
            return sourceClient
        }

        open fun getHeaders(): Headers {
            headersFailure?.let { throw it }
            return Headers.headersOf("Referer", "https://source.example/")
        }
    }

    private class PrivateNetworkSource(client: OkHttpClient) : NetworkSource(client) {
        override fun getClient(): OkHttpClient = super.getClient()
        override fun getHeaders(): Headers = super.getHeaders()
    }

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

    @Test
    fun `a cover is served from the image cache without a second network request`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-image-cache").toFile()
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Type", "image/png")
                    .body(Buffer().write(PNG))
                    .build(),
            )
            val imageLoader = createDesktopImageLoader(
                context = PlatformContext.INSTANCE,
                defaultCallFactory = OkHttpClient(),
                sourceCallFactory = { OkHttpClient() },
                sourceHeaders = { null },
                imageCache = DesktopImageCache(directory),
            )

            try {
                val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .data(DesktopSourceImage(server.url("/cover.png").toString(), sourceId = 42L))
                    .diskCacheKey("manga-cover:42:0:cover")
                    .build()

                val first = imageLoader.execute(request)
                assertEquals(DataSource.NETWORK, assertInstanceOf(SuccessResult::class.java, first).dataSource)

                // The cover is read from the image cache, which stores every cover under
                // its own key. That keeps the reads of the grid parallel instead of
                // queueing them behind the single lock of the network cache.
                // The write into the cache happens in the background, so the read is
                // retried until the cache holds the cover.
                val second = withTimeout(10_000) {
                    var result = imageLoader.execute(request)
                    while (assertInstanceOf(SuccessResult::class.java, result).dataSource != DataSource.DISK) {
                        delay(50)
                        result = imageLoader.execute(request)
                    }
                    result
                }
                assertEquals(DataSource.DISK, assertInstanceOf(SuccessResult::class.java, second).dataSource)
                assertEquals(
                    1,
                    server.requestCount,
                    "the cover must not be requested from the network twice",
                )
            } finally {
                imageLoader.shutdown()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `a cover served from the http cache is still kept in the image cache`() = runBlocking {
        val directory = Files.createTempDirectory("mihon-image-cache").toFile()
        val httpCacheDirectory = Files.createTempDirectory("mihon-http-cache").toFile()
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Type", "image/png")
                    .setHeader("Cache-Control", "max-age=3600")
                    .body(Buffer().write(PNG))
                    .build(),
            )
            val client = OkHttpClient.Builder()
                .cache(Cache(httpCacheDirectory, 16L * 1024 * 1024))
                .build()
            val imageLoader = createDesktopImageLoader(
                context = PlatformContext.INSTANCE,
                defaultCallFactory = client,
                sourceCallFactory = { client },
                sourceHeaders = { null },
                imageCache = DesktopImageCache(directory),
            )

            try {
                val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .data(DesktopSourceImage(server.url("/cover.png").toString(), sourceId = 42L))
                    .diskCacheKey("manga-cover:42:0:cover")
                    .build()

                imageLoader.execute(request)
                // The second read is served by the image cache. The covers never reach
                // the HTTP cache, whose index is guarded by a single lock: that is what
                // made the grid fill in one cover at a time.
                val second = imageLoader.execute(request)
                assertEquals(DataSource.DISK, assertInstanceOf(SuccessResult::class.java, second).dataSource)
                assertEquals(1, server.requestCount, "the cover must not be requested from the network twice")

                val third = withTimeout(10_000) {
                    var result = imageLoader.execute(request)
                    while (assertInstanceOf(SuccessResult::class.java, result).dataSource != DataSource.DISK) {
                        delay(50)
                        result = imageLoader.execute(request)
                    }
                    result
                }
                assertEquals(DataSource.DISK, assertInstanceOf(SuccessResult::class.java, third).dataSource)
            } finally {
                imageLoader.shutdown()
                directory.deleteRecursively()
                httpCacheDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun `the image cache lives next to the network cache in the application directory`() {
        val cache = requireNotNull(desktopImageCache())

        assertEquals("image_cache", cache.directory().name)
        val networkCache = mihon.desktop.platform.DesktopPlatformPaths.current(
            createDirectories = false,
        ).networkCacheDir
        assertEquals(networkCache.parentFile, cache.directory().parentFile)
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
