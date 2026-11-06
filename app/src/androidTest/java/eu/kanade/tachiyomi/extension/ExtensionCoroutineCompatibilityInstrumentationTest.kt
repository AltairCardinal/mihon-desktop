package eu.kanade.tachiyomi.extension

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceFilterResult
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.prepareSourceFilters
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs only on the task-owned emulator and non-debuggable R8 benchmark host. */
class ExtensionCoroutineCompatibilityInstrumentationTest {
    @Test
    fun externalBinariesWorkAfterR8AndFutureFailuresStaySourceLocal() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(InstrumentationRegistry.getArguments().getString("ecDedicated") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(context.packageName == "app.mihon.desktop.fork.benchmark")
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val preferences = Injekt.get<SourcePreferences>()
        val trusted = preferences.trustedExtensions()
        val originalTrust = trusted.get()
        val trustWasSet = trusted.isSet()
        val ownedFiles = mutableListOf<File>()
        val helper = Injekt.get<NetworkHelper>()
        val changedFields = mutableListOf<Pair<java.lang.reflect.Field, Any>>()
        try {
            Loopback().use { server ->
                // Preserve each production chain. Only the final transport destination changes.
                for (name in listOf("client", "nonCloudflareClient", "cloudflareClient")) {
                    val field = NetworkHelper::class.java.getDeclaredField(name).apply { isAccessible = true }
                    val original = field.get(helper) as OkHttpClient
                    val routed = original.newBuilder().addInterceptor { chain ->
                        val request = chain.request()
                        val local = request.url.newBuilder().scheme("http").host("127.0.0.1")
                            .port(server.port).build()
                        chain.proceed(request.newBuilder().url(local).build())
                    }.build()
                    changedFields += field to original
                    field.set(helper, routed)
                    assertSame(routed, field.get(helper))
                }
                assertSame(changedFields.first().first.get(helper), helper.client)
                suspend fun load(
                    asset: String,
                    hash: String,
                    pkg: String,
                    version: Long,
                    signer: String,
                ): List<Source> {
                    val target = File(context.filesDir, "exts/$pkg.ext")
                    check(!target.exists()) { "Refuse to overwrite an existing extension" }
                    val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
                    assertEquals(hash, digest(bytes))
                    target.parentFile!!.mkdirs()
                    ownedFiles += target
                    FileOutputStream(target).use { output ->
                        check(target.setReadOnly())
                        output.write(bytes)
                    }
                    Injekt.get<TrustExtension>().trust(pkg, version, signer)
                    val result = ExtensionLoader.loadExtensionFromPkgName(context, pkg)
                    check(result is LoadResult.Success) { "Production extension admission failed: $pkg $result" }
                    return result.extension.sources.also { sources ->
                        check(sources.isNotEmpty())
                        sources.forEach { assertTrue(it.javaClass.classLoader is ChildFirstPathClassLoader) }
                    }
                }
                val real = load("ec-zerobyw-1.6.2.apk", REAL_HASH, REAL_PACKAGE, 106002, REAL_SIGNER).single()
                assertEquals("Loading must not warm the cold domain", 0, server.count.get())
                val service = SourceMangaSearchService()
                for (page in 1..2) {
                    val result = service.loadPageResult(real, request(real.id, page))
                    assertTrue(result is SourcePageResult.Content)
                    assertEquals(
                        listOf("EC controlled manga"),
                        (result as SourcePageResult.Content).items.map {
                            it.title
                        },
                    )
                }
                assertEquals(3, server.count.get())
                assertEquals(listOf("/source-domains/zerobyw.txt", "/pc/pc/", "/pc/pc/"), server.paths.toList())

                // These thin DEX inputs were compiled before the host, against different runtimes.
                for ((name, hash, className) in listOf(
                    Triple("old", OLD_DEX_HASH, "ec.old.BlockingProbe"),
                    Triple("new", NEW_DEX_HASH, "ec.newabi.BlockingProbe"),
                )) {
                    val file = File(context.cacheDir, "ec-coroutines-$name.jar")
                    check(!file.exists())
                    val bytes = instrumentation.context.assets.open("ec-coroutines/$name.jar").use { it.readBytes() }
                    assertEquals(hash, digest(bytes))
                    ownedFiles += file
                    FileOutputStream(file).use { output ->
                        check(file.setReadOnly())
                        output.write(bytes)
                    }
                    val loader = ChildFirstPathClassLoader(file.absolutePath, null, context.classLoader)
                    val probe = loader.loadClass(className)
                    assertSame(loader, probe.classLoader)
                    assertEquals("default-value", probe.getMethod("defaultValue").invoke(null))
                    assertEquals("external", probe.getMethod("explicitContext").invoke(null))
                    val failure = runCatching { probe.getMethod("failure").invoke(null) }.exceptionOrNull()
                    assertTrue(failure is InvocationTargetException)
                    assertEquals("external-failure", (failure as InvocationTargetException).targetException.message)
                    val cancelled = Job().apply { cancel(CancellationException("ec-native-cancel")) }
                    val cancellation = runCatching {
                        probe.getMethod("cancelled", Job::class.java).invoke(null, cancelled)
                    }.exceptionOrNull()
                    assertTrue(cancellation is InvocationTargetException)
                    assertTrue((cancellation as InvocationTargetException).targetException is CancellationException)
                    assertTrue(cancelled.isCancelled)
                }
                val old = load(
                    "aex00-external-v16-controlled-sample.apk",
                    OLD_APK_HASH,
                    "aex00.external.v16.controlled",
                    160000,
                    OLD_SIGNER,
                ).first()
                val future = load(
                    "ec-coroutines/future.apk",
                    FUTURE_HASH,
                    "ec.future.controlled",
                    160001,
                    FUTURE_SIGNER,
                ).single()
                val filters = prepareSourceFilters(future)
                assertTrue(filters is SourceFilterResult.Failure)
                assertTrue((filters as SourceFilterResult.Failure).error is AppError.ExtensionIncompatible)
                assertTrue(service.loadPageResult(old, request(old.id, 1)) is SourcePageResult.Content)
                val healthyEntered = CountDownLatch(1)
                val releaseHealthy = CountDownLatch(1)
                server.blockSearch.set(healthyEntered to releaseHealthy)
                withTimeout(20_000) {
                    coroutineScope {
                        val good = async(Dispatchers.IO) { service.loadPageResult(real, request(real.id, 3)) }
                        try {
                            assertTrue("Healthy source must be in flight", healthyEntered.await(15, TimeUnit.SECONDS))
                            val failure = service.loadPageResult(future, request(0xEC0001, 1))
                            assertTrue(failure is SourcePageResult.Failure)
                            failure as SourcePageResult.Failure
                            assertTrue(failure.error is AppError.ExtensionIncompatible)
                            assertEquals(SourceRecoveryAction.None, failure.recoveryAction)
                        } finally {
                            releaseHealthy.countDown()
                        }
                        assertTrue(good.await() is SourcePageResult.Content)
                    }
                }
                val interceptor = future.javaClass.classLoader!!.loadClass("ec.future.FutureInterceptor")
                    .getDeclaredConstructor().newInstance() as Interceptor
                checkAsyncIsolation(helper.client, interceptor, server.port)
                server.failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
            }
        } finally {
            changedFields.asReversed().forEach { (field, original) -> field.set(helper, original) }
            ownedFiles.asReversed().forEach { if (it.exists()) check(it.delete()) }
            if (trustWasSet) trusted.set(originalTrust) else trusted.delete()
        }
    }

    private fun checkAsyncIsolation(client: OkHttpClient, interceptor: Interceptor, port: Int) {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = CopyOnWriteArrayList<Thread>()
        val executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "ec-owned-network").apply {
                uncaughtExceptionHandler =
                    Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
                threads += this
            }
        }
        val dispatcher = Dispatcher(executor)
        val callbacks = AtomicInteger()
        val bad = AtomicReference<IOException?>()
        val done = CountDownLatch(1)
        try {
            client.newBuilder().dispatcher(dispatcher).addInterceptor(interceptor).build()
                .newCall(Request.Builder().url("http://127.0.0.1:$port/healthy").build()).enqueue(object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        callbacks.incrementAndGet()
                        bad.set(e)
                        done.countDown()
                    }
                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.close()
                        callbacks.incrementAndGet()
                        done.countDown()
                    }
                })
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
            assertTrue(bad.get()?.inspectSourceFailure()?.linkage != null)
            val healthyDone = CountDownLatch(1)
            val healthy = AtomicReference<String?>()
            client.newBuilder().dispatcher(dispatcher).build()
                .newCall(Request.Builder().url("http://127.0.0.1:$port/healthy").build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        healthyDone.countDown()
                    }
                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use { healthy.set(it.body.string()) }
                        healthyDone.countDown()
                    }
                })
            assertTrue(healthyDone.await(20, TimeUnit.SECONDS))
            assertEquals("healthy", healthy.get())
        } finally {
            dispatcher.cancelAll()
            executor.shutdown()
            check(executor.awaitTermination(20, TimeUnit.SECONDS))
            threads.forEach {
                it.join(2_000)
                check(!it.isAlive)
            }
        }
        assertEquals(1, callbacks.get())
        assertTrue("The owned worker must finish without rethrowing linkage errors", uncaught.isEmpty())
    }

    private fun request(id: Long, page: Int) = SourcePageRequest(
        id,
        page,
        1,
        SourceQuery.Search("fixture", FilterList()),
    )

    private class Loopback : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val count = AtomicInteger()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val blockSearch = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()
        private val worker = Thread({
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = reader.readLine().split(' ')[1].substringBefore('?')
                        paths += path
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        if (path == "/pc/pc/") {
                            blockSearch.getAndSet(null)?.let { (entered, release) ->
                                entered.countDown()
                                check(release.await(20, TimeUnit.SECONDS))
                            }
                        }
                        val body = when (path) {
                            "/source-domains/zerobyw.txt" -> "https://zerobyw.example"
                            "/pc/pc/" ->
                                "<html><body><a href='/details/?kuid=ec1'>" +
                                    "<h3>EC controlled manga</h3><img src='/cover.jpg'></a></body></html>"
                            "/healthy" -> "healthy"
                            else -> error("Unexpected fixture path")
                        }.toByteArray(Charsets.UTF_8)
                        count.incrementAndGet()
                        val output = socket.getOutputStream()
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(
                                Charsets.US_ASCII,
                            ),
                        )
                        output.write(body)
                        output.flush()
                    }
                }
            } catch (error: Exception) {
                if (!server.isClosed) failure.set(error)
            }
        }, "ec-owned-loopback").apply { start() }
        override fun close() {
            server.close()
            worker.join(2_000)
            check(!worker.isAlive)
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    private companion object {
        const val REAL_PACKAGE = "eu.kanade.tachiyomi.extension.zh.zerobyw"
        const val REAL_HASH = "b04e4a298e6fef4fa58ef452ece77dc988025515f4b9b835d54a085c072dc874"
        const val REAL_SIGNER = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val OLD_APK_HASH = "34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182"
        const val OLD_SIGNER = "9be8a18439915033e8362f25426323e8b7b94f223eadca4962ce5f91a23d6021"
        const val OLD_DEX_HASH = "6347cd4bace86f9979aa61aa56c451eebc23640dba89d961822beac6c8037dec"
        const val NEW_DEX_HASH = "2a4f54c1584d6a55fa65041cc0ab330af4a140f5946e2462dd57cbd7f7aad17c"
        const val FUTURE_HASH = "62403b3172e59e921b013d63afe844749d43eef500301dabe63c09b1f10cb9e3"
        const val FUTURE_SIGNER = "1c03b9f373b3a4994003982ffda23052f7bfc6fe0138c6341ed6476cb1f76f9d"
    }
}
