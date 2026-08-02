package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

class AndroidReaderEncodedPageStoreIntegrationTest {

    @TempDir
    lateinit var cacheDirectory: Path

    @Test
    fun `quota eviction survives cache reopen with physical presence as authority`() = runTest {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val firstRef = EncodedPageRef("https://example.test/first")
        val secondRef = EncodedPageRef("https://example.test/second")
        val firstCache = ChapterCache(context, Json, maxCacheBytes = 64)
        val firstStore = AndroidReaderEncodedPageStore(firstCache, maxBytes = 8)
        firstStore.beginSession(emptySet())

        val firstWrite = firstStore.store(firstRef) {
            firstCache.putImageToCache(firstRef.value, response(firstRef, "123456"))
            firstCache.getImageFile(firstRef.value).length()
        }
        val secondWrite = firstStore.store(secondRef) {
            firstCache.putImageToCache(secondRef.value, response(secondRef, "abcdef"))
            firstCache.getImageFile(secondRef.value).length()
        }

        assertTrue(firstWrite is EncodedPageStoreWriteResult.Stored)
        assertEquals(setOf(firstRef), (secondWrite as EncodedPageStoreWriteResult.Stored).evictedRefs)
        assertFalse(firstCache.isImageInCache(firstRef.value))
        assertTrue(firstCache.isImageInCache(secondRef.value))
        assertEquals(6, firstStore.diagnostics().usedBytes)
        firstStore.endSession()
        val closedAccess = try {
            firstStore.contains(secondRef)
            null
        } catch (error: IllegalStateException) {
            error
        }
        assertNotNull(closedAccess, "An ended session must not reopen implicitly")
        firstCache.close()

        val reopenedCache = ChapterCache(context, Json, maxCacheBytes = 64)
        try {
            val reopenedStore = AndroidReaderEncodedPageStore(reopenedCache, maxBytes = 8)
            val lifecycle = reopenedStore.beginSession(setOf(firstRef, secondRef))

            assertEquals(setOf(secondRef), lifecycle.availableRefs)
            assertEquals(setOf(firstRef), lifecycle.missingRefs)
            assertTrue(reopenedStore.contains(secondRef))
            assertEquals(setOf(secondRef), reopenedStore.diagnostics().refs)
        } finally {
            reopenedCache.close()
        }
    }

    @Test
    fun `session begin counts physically observed entries and startup quota eviction`() = runTest {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val cache = ChapterCache(context, Json, maxCacheBytes = 64)
        val firstRef = EncodedPageRef("https://example.test/startup-first")
        val secondRef = EncodedPageRef("https://example.test/startup-second")
        assertTrue(cache.putImageToCache(firstRef.value, response(firstRef, "123456")))
        assertTrue(cache.putImageToCache(secondRef.value, response(secondRef, "abcdef")))
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 8)

        val lifecycle = store.beginSession(setOf(firstRef, secondRef))

        assertEquals(setOf(firstRef), lifecycle.evictedRefs)
        assertFalse(cache.isImageInCache(firstRef.value))
        assertTrue(cache.isImageInCache(secondRef.value))
        assertEquals(2, store.diagnostics().hitCount)
        assertEquals(1, store.diagnostics().evictionCount)
        assertEquals(setOf(secondRef), store.diagnostics().refs)
        cache.close()
    }

    @Test
    fun `physical LRU eviction is reconciled before the logical commit chooses another victim`() = runTest {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val cache = ChapterCache(context, Json, maxCacheBytes = 12)
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 12)
        val firstRef = EncodedPageRef("https://example.test/logical-oldest")
        val secondRef = EncodedPageRef("https://example.test/physical-oldest")
        val incomingRef = EncodedPageRef("https://example.test/incoming-six")
        store.beginSession(emptySet())
        store.store(firstRef) {
            assertTrue(cache.putImageToCache(firstRef.value, response(firstRef, "1111")))
            cache.getImageFile(firstRef.value).length()
        }
        store.store(secondRef) {
            assertTrue(cache.putImageToCache(secondRef.value, response(secondRef, "2222")))
            cache.getImageFile(secondRef.value).length()
        }
        assertTrue(cache.isImageInCache(firstRef.value), "Touch only the physical LRU order")

        val result = store.store(incomingRef) {
            assertTrue(cache.putImageToCache(incomingRef.value, response(incomingRef, "123456")))
            cache.getImageFile(incomingRef.value).length()
        } as EncodedPageStoreWriteResult.Stored

        assertEquals(setOf(secondRef), result.evictedRefs)
        assertTrue(cache.isImageInCache(firstRef.value))
        assertFalse(cache.isImageInCache(secondRef.value))
        assertTrue(cache.isImageInCache(incomingRef.value))
        assertEquals(setOf(firstRef, incomingRef), store.diagnostics().refs)
        cache.close()
    }

    @Test
    fun `ending a session during an encoded write rejects and removes the late physical entry`() = runTest {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val cache = ChapterCache(context, Json, maxCacheBytes = 16)
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 16)
        val ref = EncodedPageRef("https://example.test/late")
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        store.beginSession(emptySet())

        val write = async {
            runCatching {
                store.store(ref) {
                    writerStarted.complete(Unit)
                    releaseWriter.await()
                    cache.putImageToCache(ref.value, response(ref, "late"))
                    cache.getImageFile(ref.value).length()
                }
            }
        }
        writerStarted.await()
        store.endSession()
        releaseWriter.complete(Unit)

        val failure = write.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(cache.isImageInCache(ref.value))
        assertFalse(store.diagnostics().isSessionOpen)
        cache.close()
    }

    @Test
    fun `logical commit rejects a writer result when the physical entry is absent`() = runTest {
        val cache = mockk<ChapterCache>()
        val ref = EncodedPageRef("https://example.test/missing-after-write")
        every { cache.isImageInCache(ref.value) } returns false
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 16)
        store.beginSession(emptySet())

        val failure = runCatching { store.store(ref) { 4 } }.exceptionOrNull()

        assertInstanceOf(IOException::class.java, failure)
        assertTrue(store.diagnostics().refs.isEmpty())
    }

    @Test
    fun `writer failure after physical commit removes the unindexed entry`() = runTest {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDirectory.toFile()
        val cache = ChapterCache(context, Json, maxCacheBytes = 64)
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 16)
        val ref = EncodedPageRef("https://example.test/writer-failure")
        store.beginSession(emptySet())

        val failure = runCatching {
            store.store(ref) {
                assertTrue(cache.putImageToCache(ref.value, response(ref, "written")))
                throw IOException("failed after physical commit")
            }
        }.exceptionOrNull()

        assertInstanceOf(IOException::class.java, failure)
        assertFalse(cache.isImageInCache(ref.value))
        assertTrue(store.diagnostics().refs.isEmpty())
        cache.close()
    }

    @Test
    fun `failed physical eviction keeps the logical entry and reports the failure`() = runTest {
        val cache = mockk<ChapterCache>()
        val physical = mockk<File>()
        val ref = EncodedPageRef("https://example.test/eviction-failure")
        every { cache.isImageInCache(ref.value) } returns true
        every { cache.getImageFile(ref.value) } returns physical
        every { physical.length() } returns 4
        every { physical.exists() } returns false
        every { cache.removeImageFromCache(ref.value) } throws IOException("remove failed")
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 16)
        store.beginSession(setOf(ref))

        val failure = runCatching { store.evict(ref) }.exceptionOrNull()

        assertInstanceOf(IOException::class.java, failure)
        assertEquals(setOf(ref), store.diagnostics().refs)
    }

    @Test
    fun `quota commit does not advance the logical index when physical victim removal fails`() = runTest {
        val cache = mockk<ChapterCache>()
        val physical = mockk<File>()
        val retained = EncodedPageRef("https://example.test/retained")
        val incoming = EncodedPageRef("https://example.test/incoming")
        val present = mutableSetOf(retained.value)
        every { cache.isImageInCache(any()) } answers { firstArg<String>() in present }
        every { cache.getImageFile(any()) } returns physical
        every { physical.length() } returns 4
        every { physical.exists() } returns false
        every { cache.removeImageFromCache(retained.value) } throws IOException("victim remove failed")
        every { cache.removeImageFromCache(incoming.value) } answers { present.remove(incoming.value) }
        val store = AndroidReaderEncodedPageStore(cache, maxBytes = 4)
        store.beginSession(setOf(retained))

        val failure = runCatching {
            store.store(incoming) {
                present += incoming.value
                4
            }
        }.exceptionOrNull()

        assertInstanceOf(IOException::class.java, failure)
        assertEquals(setOf(retained), store.diagnostics().refs)
        assertEquals(setOf(retained.value), present)
    }

    private fun response(ref: EncodedPageRef, body: String): Response = Response.Builder()
        .request(Request.Builder().url(ref.value).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody())
        .build()
}
