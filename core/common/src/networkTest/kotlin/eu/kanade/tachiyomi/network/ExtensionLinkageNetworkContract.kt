package eu.kanade.tachiyomi.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Shared Android/JVM/Desktop contract. Uses real enqueue and waits for actual worker termination. */
object ExtensionLinkageNetworkContract {
    fun verify(clients: List<OkHttpClient>, interceptorFactory: (() -> Interceptor)? = null) {
        val fixture = File(requireNotNull(System.getProperty("ec.future.jar")))
        URLClassLoader(arrayOf(fixture.toURI().toURL()), javaClass.classLoader).use { loader ->
            val factory = interceptorFactory ?: {
                loader.loadClass("ec.future.FutureInterceptor").getDeclaredConstructor().newInstance() as Interceptor
            }
            clients.forEach { base -> listOf(false, true).forEach { network -> verifyOne(base, factory(), network) } }
        }
    }

    private fun verifyOne(base: OkHttpClient, external: Interceptor, network: Boolean) {
        val workerFailures = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = Collections.synchronizedList(mutableListOf<Thread>())
        val executor = object : ThreadPoolExecutor(
            0,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "EC-private-http-worker").also { workers.add(it) } },
        ) {
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                if (failure != null) workerFailures.add(failure)
                super.afterExecute(task, failure)
            }
        }
        val guard = Interceptor { chain ->
            if (chain.request().url.encodedPath == "/bad") external.intercept(chain) else chain.proceed(chain.request())
        }
        val client = base.newBuilder().dispatcher(Dispatcher(executor)).apply {
            if (network) addNetworkInterceptor(guard) else addInterceptor(guard)
        }.build()
        try {
            MockWebServer().also { it.start() }.use { server ->
                server.enqueue(MockResponse(body = "healthy"))
                val failures = AtomicInteger()
                val responses = AtomicInteger()
                val failure = AtomicReference<IOException>()
                val callback = CountDownLatch(1)
                client.newCall(Request.Builder().url(server.url("/bad")).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        failure.set(e)
                        failures.incrementAndGet()
                        callback.countDown()
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        responses.incrementAndGet()
                        callback.countDown()
                    }
                })
                assertTrue(callback.await(5, TimeUnit.SECONDS), "missing bad-source callback")
                // onFailure precedes OkHttp's possible rethrow. A latch alone is insufficient.
                synchronized(workers) { workers.toList() }.forEach {
                    it.join(5000)
                    assertFalse(it.isAlive, "HTTP worker did not terminate")
                }
                assertEquals(1, failures.get())
                assertEquals(0, responses.get())
                assertEquals("SourceCompatibilityException", failure.get()?.javaClass?.simpleName)
                assertTrue(failure.get()?.cause is NoSuchMethodError)
                assertTrue(workerFailures.isEmpty(), "Worker escaped after callback: $workerFailures")
                assertEquals(0, client.dispatcher.runningCallsCount())
                val healthy = CountDownLatch(1)
                val healthyBody = AtomicReference<String>()
                client.newCall(Request.Builder().url(server.url("/healthy")).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        healthyBody.set("failure: $e")
                        healthy.countDown()
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use { healthyBody.set(it.body.string()) }
                        healthy.countDown()
                    }
                })
                assertTrue(healthy.await(5, TimeUnit.SECONDS))
                synchronized(workers) { workers.toList() }.forEach {
                    it.join(5000)
                    assertFalse(it.isAlive)
                }
                assertEquals("healthy", healthyBody.get())
                assertEquals(1, server.requestCount)
                assertEquals(0, client.dispatcher.runningCallsCount())
                assertTrue(workerFailures.isEmpty())
            }
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
