package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable
import rx.schedulers.Schedulers
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SourceRxCompatibilityBoundaryContractTest {
    @Test
    fun `custom async operator reporting an IO wrapped linkage reaches the host compatibility boundary`() =
        runBlocking {
            val missing = NoSuchMethodError("future reported asynchronous operator")
            val worker = AtomicReference<Thread>()
            val escaped = AtomicReference<Throwable?>()
            val onErrors = AtomicInteger()
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ec-reported-custom-rx").also {
                    it.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> escaped.set(failure) }
                    worker.set(it)
                }
            }
            try {
                val source = RxSource {
                    Observable.just(MangasPage(emptyList(), false)).map<MangasPage> {
                        throw java.io.IOException("reported extension failure", missing)
                    }.doOnError { onErrors.incrementAndGet() }.subscribeOn(Schedulers.from(executor))
                }
                val result = SourceMangaSearchService().loadPageResult(source, request()) as SourcePageResult.Failure
                val transport = (result.error as AppError.ExtensionIncompatible).cause as SourceCompatibilityException
                assertTrue(transport.inspectSourceFailure().causes.any { it === missing })
                assertEquals(SourceOperationPhase.SEARCH, transport.phase)
                assertEquals(SourceRecoveryAction.None, result.recoveryAction)
                assertEquals(1, onErrors.get())
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                worker.get()?.let {
                    it.join(5_000)
                    assertFalse(it.isAlive)
                }
            }
            assertNull(escaped.get())
        }

    @Test
    fun `custom synchronous map linkage returns compatibility through real Source ABI and service`() =
        runBlocking {
            val missing = NoSuchMethodError("future synchronous operator")
            val mapThread = AtomicReference<Thread>()
            val onErrors = AtomicInteger()
            val source = RxSource {
                Observable.just(MangasPage(emptyList(), false)).map<MangasPage> {
                    mapThread.set(Thread.currentThread())
                    throw missing
                }.doOnError { onErrors.incrementAndGet() }
            }
            val result = SourceMangaSearchService().loadPageResult(source, request()) as SourcePageResult.Failure
            val error = result.error as AppError.ExtensionIncompatible
            val transport = error.cause as SourceCompatibilityException
            assertTrue(transport.inspectSourceFailure().causes.any { it === missing })
            assertEquals(SourceOperationPhase.SEARCH, transport.phase)
            assertEquals(SourceRecoveryAction.None, result.recoveryAction)
            assertSame(Thread.currentThread(), mapThread.get())
            assertEquals(0, onErrors.get(), "Rx1 fatal bypasses onError but stays inside the synchronous host call")
        }

    @Test
    fun `extension owned Rx executor fatal never reaches await error but caller cancellation unsubscribes`() =
        runBlocking {
            val missing = NoSuchMethodError("future independently scheduled operator")
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val workers = Collections.synchronizedList(mutableListOf<Thread>())
            val escaped = CountDownLatch(1)
            val unsubscribed = CountDownLatch(1)
            val onErrors = AtomicInteger()
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ec-extension-owned-rx").also { thread ->
                    thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                        failures += failure
                        escaped.countDown()
                    }
                    workers += thread
                }
            }
            val source = RxSource {
                Observable.just(MangasPage(emptyList(), false)).map<MangasPage> { throw missing }
                    .doOnError { onErrors.incrementAndGet() }
                    .subscribeOn(Schedulers.from(executor))
                    .doOnUnsubscribe { unsubscribed.countDown() }
            }
            val observed = AtomicReference<SourcePageResult?>()
            val caller = async(start = CoroutineStart.UNDISPATCHED) {
                SourceMangaSearchService().loadPageResult(source, request()).also(observed::set)
            }
            try {
                assertTrue(escaped.await(5, TimeUnit.SECONDS), "The actual executor worker must expose the fatal")
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                val threads = synchronized(workers) { workers.toList() }
                threads.forEach {
                    it.join(5_000)
                    assertFalse(it.isAlive)
                }
                val actual = synchronized(failures) { failures.toList() }
                assertEquals(1, actual.size)
                assertTrue(actual.single().inspectSourceFailure().causes.any { it === missing })
                assertEquals(0, onErrors.get())
                assertTrue(caller.isActive)
                assertNull(observed.get(), "No terminal value was handed back to awaitSingle")
                assertEquals(1L, unsubscribed.count, "The host subscription is still owned by the waiting caller")
                caller.cancel(CancellationException("caller leaves source operation"))
                caller.join()
                assertTrue(caller.isCancelled)
                assertTrue(unsubscribed.await(5, TimeUnit.SECONDS))
                val healthy = RxSource { Observable.just(MangasPage(emptyList(), false)) }
                assertTrue(SourceMangaSearchService().loadPageResult(healthy, request()) is SourcePageResult.Empty)
            } finally {
                caller.cancel()
                caller.join()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                synchronized(workers) { workers.toList() }.forEach { it.join(5_000) }
            }
        }

    private fun request() = SourcePageRequest(93L, 1, 1, SourceQuery.Search("creator", FilterList()))

    @Suppress("OVERRIDE_DEPRECATION")
    private class RxSource(private val observable: () -> Observable<MangasPage>) : HttpSource() {
        override val id = 93L
        override val name = "Custom Rx fixture"
        override val lang = "en"
        override val baseUrl = "https://fixture.invalid"
        override val supportsLatest = false
        override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = observable()
        override fun chapterPageParse(response: Response) = SChapter.create()
    }
}
