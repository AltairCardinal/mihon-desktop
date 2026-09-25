package mihon.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.sync.transport.SyncBlobCache
import mihon.data.sync.transport.SyncBlobCacheKey
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SyncBlobCacheOwnerMutexRaceTest {
    @Test
    fun `loader failure reaches waiter and same key can retry`() = runBlocking {
        val cache = SyncBlobCache()
        val key = SyncBlobCacheKey(
            apiOrigin = "https://api.example",
            repository = "owner/repo",
            branch = "main",
            objectFormat = "git-sha1",
            objectOid = "d".repeat(40),
            validationScope = "space",
            connectionRevision = "revision",
        )
        val loaderStarted = CompletableDeferred<Unit>()
        val failLoader = CompletableDeferred<Unit>()
        val loaderCalls = AtomicInteger()
        val owner = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                cache.getOrLoad(key) {
                    loaderCalls.incrementAndGet()
                    loaderStarted.complete(Unit)
                    failLoader.await()
                    error("loader failed")
                }
            }
        }
        loaderStarted.await()
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                cache.getOrLoad(key) {
                    error("same-key waiter must join the failed owner flight")
                }
            }
        }

        failLoader.complete(Unit)
        val ownerResult = owner.await()
        val waiterResult = waiter.await()
        val retry = cache.getOrLoad(key) {
            loaderCalls.incrementAndGet()
            byteArrayOf(9)
        }

        assertEquals("loader failed", ownerResult.exceptionOrNull()?.message)
        assertEquals("loader failed", waiterResult.exceptionOrNull()?.message)
        assertEquals(listOf(9.toByte()), retry.toList())
        assertEquals(2, loaderCalls.get())
    }

    @Test
    fun `owner cancellation during publication mutex competition completes waiter and same key retry`() = runBlocking {
        val cache = SyncBlobCache()
        val cacheMutex = cache.javaClass.getDeclaredField("mutex").apply { isAccessible = true }
            .get(cache) as Mutex
        val key = SyncBlobCacheKey(
            apiOrigin = "https://api.example",
            repository = "owner/repo",
            branch = "main",
            objectFormat = "git-sha1",
            objectOid = "c".repeat(40),
            validationScope = "space",
            connectionRevision = "revision",
        )
        val loaderStarted = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<ByteArray>()

        val owner = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad(key) {
                loaderStarted.complete(Unit)
                releaseLoader.await()
            }
        }
        loaderStarted.await()
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching { cache.getOrLoad(key) { error("same-key waiter must join the owner flight") } }
        }

        cacheMutex.lock()
        var completedBeforeUnlock = false
        try {
            releaseLoader.complete(byteArrayOf(7))
            // Let the owner resume from its loader and queue on the held publication mutex.
            delay(50)
            owner.cancel()
            completedBeforeUnlock = withTimeoutOrNull(100) {
                owner.join()
                true
            } ?: false
        } finally {
            cacheMutex.unlock()
        }

        withTimeout(500) { owner.join() }
        val waiterResult = withTimeoutOrNull(500) { waiter.await() }
        waiter.cancel()
        withTimeout(500) { waiter.join() }

        val retry = withTimeoutOrNull(500) {
            cache.getOrLoad(key) { byteArrayOf(8) }
        }
        assertAll(
            "owner cancellation cleanup must wait for the publication mutex and release same-key callers",
            { assertFalse(completedBeforeUnlock, "old cleanup completed while its mutex was unavailable") },
            { assertNotNull(waiterResult, "owner cancellation left the existing same-key waiter pending") },
            { assertNotNull(retry, "owner cancellation left a stale same-key flight that never completes") },
            {
                waiterResult?.getOrNull()?.let { assertEquals(listOf(7.toByte()), it.toList()) }
            },
            {
                retry?.let { assertTrue(it.contentEquals(byteArrayOf(7)) || it.contentEquals(byteArrayOf(8))) }
            },
        )
    }
}
