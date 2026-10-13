package eu.kanade.tachiyomi.data.updater

import android.app.Application
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.sync.AndroidRecoveryPackageInstaller
import eu.kanade.tachiyomi.data.sync.AndroidRecoveryPackageVerification
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.getUriCompat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.release.model.ForkUpdateRequest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ForkAppUpdateDownloadTest {
    private lateinit var previous: InjektScope
    private lateinit var application: Application
    private lateinit var server: MockWebServer
    private val installed = mutableListOf<Uri>()
    private val payload = "signed-package-fixture".toByteArray()
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Before
    fun setup() {
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        application = RuntimeEnvironment.getApplication()
        server = MockWebServer().also { it.start() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/package.apk")).build())
        }.build()
        val network = mockk<NetworkHelper> { every { this@mockk.client } returns client }
        Injekt.addSingleton(network)
        mockkConstructor(AppUpdateNotifier::class, AndroidRecoveryPackageInstaller::class)
        every { anyConstructed<AppUpdateNotifier>().onDownloadStarted(any()) } returns
            NotificationCompat.Builder(application, "updates")
        every { anyConstructed<AppUpdateNotifier>().cancel() } just Runs
        every { anyConstructed<AppUpdateNotifier>().onProgressChange(any()) } just Runs
        every { anyConstructed<AppUpdateNotifier>().onDownloadError(any(), any(), any(), any(), any()) } just Runs
        every { anyConstructed<AppUpdateNotifier>().promptInstall(any()) } answers { installed += firstArg<Uri>() }
        every { anyConstructed<AndroidRecoveryPackageInstaller>().verify(any(), any()) } answers {
            AndroidRecoveryPackageVerification.Ready(firstArg(), "candidate")
        }
        mockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
        every { any<File>().getUriCompat(any()) } returns Uri.parse("content://verified/update.apk")
    }

    @After
    fun cleanup() {
        unmockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
        unmockkConstructor(AppUpdateNotifier::class, AndroidRecoveryPackageInstaller::class)
        server.close()
        Injekt = previous
    }

    @Test
    fun `worker verifies bytes and package identity before install notification`() = runBlocking {
        server.enqueue(MockResponse(body = payload.decodeToString()))
        val result = worker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 1) {
            anyConstructed<AndroidRecoveryPackageInstaller>().verify(
                match { it.isFile && it.readBytes().contentEquals(payload) },
                BuildConfig.VERSION_CODE.toLong() + 1,
            )
        }
        assertEquals(1, installed.size)
    }

    @Test
    fun `hash size and trusted package identity failures never offer installation`() = runBlocking {
        server.enqueue(MockResponse(body = payload.decodeToString()))
        assertEquals(ListenableWorker.Result.failure(), worker(hash = "0".repeat(64)).doWork())
        server.enqueue(MockResponse(body = payload.decodeToString()))
        assertEquals(ListenableWorker.Result.failure(), worker(size = payload.size.toLong() + 1).doWork())
        every { anyConstructed<AndroidRecoveryPackageInstaller>().verify(any(), any()) } returns
            AndroidRecoveryPackageVerification.Rejected
        server.enqueue(MockResponse(body = payload.decodeToString()))
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        listOf(403, 429).forEach { status ->
            server.enqueue(MockResponse(code = status, body = "failure"))
            assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        }
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `cancellation propagates and removes only its own partial private file`() = runBlocking {
        val unrelated = application.cacheDir.resolve("keep-existing-package.apk").apply { writeText("keep") }
        val before = application.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        every { anyConstructed<AppUpdateNotifier>().onProgressChange(any()) } throws
            CancellationException("cancel update")
        server.enqueue(MockResponse(body = payload.decodeToString()))
        val failure = runCatching { worker().doWork() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(before, application.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet())
        assertEquals("keep", unrelated.readText())
        assertTrue(installed.isEmpty())
        unrelated.delete()
        Unit
    }

    @Test
    fun `separate downloads use distinct private files before trusted package inspection`() = runBlocking {
        val inspected = mutableListOf<File>()
        every { anyConstructed<AndroidRecoveryPackageInstaller>().verify(any(), any()) } answers {
            val file = firstArg<File>()
            inspected += file
            AndroidRecoveryPackageVerification.Ready(file, "candidate")
        }
        repeat(2) {
            server.enqueue(MockResponse(body = payload.decodeToString()))
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
        }
        assertEquals(2, inspected.distinct().size)
        assertTrue(inspected.all { it.canonicalPath.startsWith(application.cacheDir.canonicalPath + File.separator) })
    }

    @Test
    fun `missing download metadata unsafe source and HTTP errors cannot become successful updates`() = runBlocking {
        server.enqueue(MockResponse(code = 500, body = "failure"))
        assertEquals(ListenableWorker.Result.failure(), worker(hash = null).doWork())
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(url = "https://github.com/mihonapp/mihon/releases/download/v1/app.apk").doWork(),
        )
        assertEquals(0, server.requestCount)
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertTrue(installed.isEmpty())
    }

    private fun worker(
        hash: String? = sha256,
        size: Long = payload.size.toLong(),
        url: String = "https://github.com/${ForkUpdateRequest.REPOSITORY}/releases/download/v1/app.apk",
    ) = TestListenableWorkerBuilder<AppUpdateDownloadJob>(application)
        .setInputData(
            workDataOf(
                AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL to url,
                AppUpdateDownloadJob.EXTRA_EXPECTED_SHA256 to hash,
                AppUpdateDownloadJob.EXTRA_EXPECTED_SIZE to size,
                AppUpdateDownloadJob.EXTRA_EXPECTED_VERSION_CODE to BuildConfig.VERSION_CODE.toLong() + 1,
            ),
        )
        .build()
}
