package mihon.desktop.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes

class DesktopUpdateDownloaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `success reports progress stays contained and bypasses shared cache`() = runTest {
        withServer { server ->
            val cache = Cache(tempDir.resolve("http-cache").toFile(), 1024 * 1024)
            val downloader = downloader(tempDir.resolve("downloads"), OkHttpClient.Builder().cache(cache).build())
            val first = "first-payload".toByteArray()
            val second = "second-payload".toByteArray()
            server.enqueue(cacheable(first))
            server.enqueue(cacheable(second))
            val progress = mutableListOf<DownloadProgress>()
            val firstResult = downloader.download(release(server, first)) { progress += it }
            val secondResult = downloader.download(release(server, second))
            assertTrue(firstResult is VerifiedDownload)
            firstResult as VerifiedDownload
            assertEquals(first.toList(), firstResult.file.readBytes().toList())
            assertTrue(firstResult.file.startsWith(tempDir.resolve("downloads").toRealPath()))
            assertEquals(first.size.toLong(), progress.last().downloadedBytes)
            assertEquals(first.size.toLong(), progress.last().totalBytes)
            assertTrue(secondResult is VerifiedDownload)
            assertEquals(2, server.requestCount)
            cache.close()
        }
    }

    @Test
    fun `missing or invalid checksum is manual only without a request or verified file`() = runTest {
        withServer { server ->
            val root = tempDir.resolve("manual")
            val invalid = listOf(null, ReleaseChecksum("md5", "0".repeat(64)), ReleaseChecksum("sha256", "not-a-hash"))
            invalid.forEach { checksum ->
                val result = downloader(root).download(release(server, PAYLOAD, checksum))
                assertTrue(result is ManualOnly)
                assertEquals(RELEASE_PAGE, (result as ManualOnly).releasePage)
            }
            assertEquals(0, server.requestCount)
            assertEmpty(root)
        }
    }

    @Test
    fun `known and streaming size limits fail and clean partial files`() = runTest {
        withServer { server ->
            val root = tempDir.resolve("limits")
            val downloader = downloader(root, maxBytes = 8)
            server.enqueue(MockResponse(body = "123456789"))
            server.enqueue(MockResponse.Builder().chunkedBody("123456789", 2).build())

            repeat(2) {
                val result = downloader.download(release(server, "123456789".toByteArray()))
                assertEquals(DownloadFailure.TOO_LARGE, (result as DownloadFailed).reason)
                assertEmpty(root)
            }
        }
    }

    @Test
    fun `size limit comparison cannot overflow near Long max`() {
        assertFalse(exceedsLimit(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE))
        assertTrue(exceedsLimit(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE))
    }

    @Test
    fun `redirect limit and scheme changes are rejected and cleaned`() = runTest {
        withServer { server ->
            val root = tempDir.resolve("redirects")
            val downloader = downloader(root, maxRedirects = 2)
            repeat(3) { server.enqueue(redirect("/redirect-$it")) }
            val limited = downloader.download(release(server, PAYLOAD))
            assertEquals(DownloadFailure.REDIRECT_LIMIT, (limited as DownloadFailed).reason)
            assertEquals(3, server.requestCount)
            assertEmpty(root)
            server.enqueue(redirect("https://example.com/update.msi"))
            val changedScheme = downloader.download(release(server, PAYLOAD))
            assertEquals(DownloadFailure.CROSS_SCHEME_REDIRECT, (changedScheme as DownloadFailed).reason)
            assertEmpty(root)
        }
    }

    @Test
    fun `cancellation cancels work and removes the partial file`() = runTest {
        withServer { server ->
            val root = tempDir.resolve("cancel")
            val bytes = ByteArray(64) { it.toByte() }
            server.enqueue(
                MockResponse.Builder().body(bytes.decodeToString()).throttleBody(1, 50, TimeUnit.MILLISECONDS).build(),
            )
            val started = CompletableDeferred<Unit>()
            val job = launch {
                downloader(root).download(release(server, bytes)) {
                    if (it.downloadedBytes > 0) started.complete(Unit)
                }
            }
            started.await()
            job.cancelAndJoin()
            assertEmpty(root)
        }
    }

    @Test
    fun `interruption and hash mismatch clean up then retry succeeds`() = runTest {
        withServer { server ->
            val root = tempDir.resolve("retry")
            val downloader = downloader(root)
            server.enqueue(
                MockResponse.Builder().body(PAYLOAD.decodeToString()).onResponseBody(SocketEffect.CloseSocket()).build(),
            )
            server.enqueue(MockResponse(body = "wrong"))
            server.enqueue(MockResponse(body = PAYLOAD.decodeToString()))

            assertEquals(DownloadFailure.CONNECTION, (downloader.download(release(server, PAYLOAD)) as DownloadFailed).reason)
            assertEmpty(root)
            assertEquals(DownloadFailure.CHECKSUM_MISMATCH, (downloader.download(release(server, PAYLOAD)) as DownloadFailed).reason)
            assertEmpty(root)
            assertTrue(downloader.download(release(server, PAYLOAD)) is VerifiedDownload)
        }
    }

    @Test
    fun `local output failures are storage failures and clean partial files`() = runTest {
        withServer { server ->
            val root = tempDir.resolve("storage")
            val failures: List<(Path) -> OutputStream> = listOf(
                { throw IOException("open") },
                { object : OutputStream() { override fun write(value: Int) = throw IOException("write") } },
                { object : OutputStream() { override fun write(value: Int) = Unit; override fun close() = throw IOException("close") } },
            )
            failures.forEach { openOutput ->
                server.enqueue(MockResponse(body = PAYLOAD.decodeToString()))
                val result = downloader(root, openOutput = openOutput).download(release(server, PAYLOAD))
                assertEquals(DownloadFailure.STORAGE, (result as DownloadFailed).reason)
                assertEmpty(root)
            }
        }
    }

    @Test
    fun `symlink root is rejected while hostile asset name cannot escape safe root`() = runTest {
        withServer { server ->
            val outside = Files.createDirectory(tempDir.resolve("outside"))
            val link = tempDir.resolve("linked-root")
            Files.createSymbolicLink(link, outside)
            val unsafe = downloader(link).download(release(server, PAYLOAD))
            assertEquals(DownloadFailure.UNSAFE_TEMP_ROOT, (unsafe as DownloadFailed).reason)
            assertEquals(0, server.requestCount)

            val safeRoot = tempDir.resolve("safe")
            server.enqueue(MockResponse(body = PAYLOAD.decodeToString()))
            val result = downloader(safeRoot).download(release(server, PAYLOAD, name = "../../escape.msi")) as VerifiedDownload
            assertTrue(result.file.startsWith(safeRoot.toRealPath()))
            assertFalse(Files.exists(tempDir.resolve("escape.msi")))
        }
    }

    private fun downloader(
        root: Path,
        client: OkHttpClient = OkHttpClient(),
        maxBytes: Long = 1024,
        maxRedirects: Int = 3,
        openOutput: (Path) -> OutputStream = { Files.newOutputStream(it) },
    ) = DesktopUpdateDownloader(client, root, maxBytes, maxRedirects, openOutput)

    private fun release(
        server: MockWebServer,
        bytes: ByteArray,
        checksum: ReleaseChecksum? = ReleaseChecksum("sha256", bytes.sha256()),
        name: String = "mihon-desktop-windows-x86_64-v1.msi",
    ) = Release(
        version = "v1",
        info = "info",
        releaseLink = RELEASE_PAGE,
        downloadLink = server.url("/update").toString(),
        asset = ReleaseAsset(name, ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD), checksum),
    )

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) = MockWebServer().also { it.start() }.use { block(it) }

    private fun redirect(location: String) = MockResponse(code = 302, headers = Headers.headersOf("Location", location))

    private fun cacheable(bytes: ByteArray) = MockResponse(headers = Headers.headersOf("Cache-Control", "max-age=3600"), body = bytes.decodeToString())

    private fun assertEmpty(root: Path) = if (Files.exists(root)) Files.list(root).use { assertEquals(0, it.count()) } else Unit

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    companion object {
        private val PAYLOAD = "verified-payload".toByteArray()
        private const val RELEASE_PAGE = "https://example.com/release"
    }
}
