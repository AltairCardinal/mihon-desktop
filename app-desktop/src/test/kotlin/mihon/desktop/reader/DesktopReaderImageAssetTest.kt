package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderImageAssetTest {

    @Test
    fun `asset disposes exactly once after its final idempotent lease closes`() {
        var disposeCalls = 0
        val asset = asset(disposer = { disposeCalls++ })
        val first = asset.retain()
        val second = first.retain()

        asset.close()
        asset.close()
        first.close()
        first.close()

        assertEquals(0, disposeCalls)
        assertSame(asset, second.asset)

        second.close()
        second.close()

        assertEquals(1, disposeCalls)
        assertThrows(IllegalStateException::class.java) { second.retain() }
    }

    @Test
    fun `entry limit evicts the least recently used asset`() {
        val disposed = mutableListOf<Int>()
        val cache = DesktopReaderImageCache(maxEntries = 2, maxBytes = 100)
        val first = asset(estimatedBytes = 10, disposer = { disposed += 1 })
        val second = asset(estimatedBytes = 10, disposer = { disposed += 2 })
        val third = asset(estimatedBytes = 10, disposer = { disposed += 3 })

        assertTrue(cache.commit(key(0), first))
        assertTrue(cache.commit(key(1), second))
        first.close()
        second.close()
        cache.acquire(key(0))!!.close()

        assertTrue(cache.commit(key(2), third))
        third.close()

        assertEquals(listOf(key(0), key(2)), cache.snapshot().keys)
        assertEquals(listOf(2), disposed)
        assertNull(cache.acquire(key(1)))
        cache.close()
        assertEquals(listOf(2, 1, 3), disposed)
    }

    @Test
    fun `byte limit uses the same access ordered eviction policy`() {
        val disposed = mutableListOf<Int>()
        val cache = DesktopReaderImageCache(maxEntries = 10, maxBytes = 8)
        val first = asset(estimatedBytes = 4, disposer = { disposed += 1 })
        val second = asset(estimatedBytes = 4, disposer = { disposed += 2 })
        val third = asset(estimatedBytes = 4, disposer = { disposed += 3 })

        assertTrue(cache.commit(key(0), first))
        assertTrue(cache.commit(key(1), second))
        first.close()
        second.close()
        cache.acquire(key(0))!!.close()

        assertTrue(cache.commit(key(2), third))
        third.close()

        assertEquals(listOf(key(0), key(2)), cache.snapshot().keys)
        assertEquals(8, cache.snapshot().usedBytes)
        assertEquals(listOf(2), disposed)
        cache.close()
    }

    @Test
    fun `oversized commit preserves existing entries and caller ownership`() {
        var existingDisposals = 0
        var oversizedDisposals = 0
        val cache = DesktopReaderImageCache(maxEntries = 2, maxBytes = 8)
        val existing = asset(estimatedBytes = 4, disposer = { existingDisposals++ })
        val oversized = asset(estimatedBytes = 9, disposer = { oversizedDisposals++ })

        assertTrue(cache.commit(key(0), existing))
        existing.close()
        val before = cache.snapshot()

        assertFalse(cache.commit(key(1), oversized))

        assertEquals(before, cache.snapshot())
        assertEquals(0, existingDisposals)
        assertEquals(0, oversizedDisposals)
        oversized.retain().close()
        assertEquals(0, oversizedDisposals, "A rejected commit must not close the caller lease")

        oversized.close()
        assertEquals(1, oversizedDisposals)
        cache.close()
        assertEquals(1, existingDisposals)
    }

    @Test
    fun `active caller remains valid after its cache entry is evicted`() {
        var firstDisposals = 0
        val cache = DesktopReaderImageCache(maxEntries = 1, maxBytes = 100)
        val first = asset(disposer = { firstDisposals++ })
        val firstBitmap = first.bitmap
        val second = asset()

        assertTrue(cache.commit(key(0), first))
        val active = requireNotNull(cache.acquire(key(0)))
        first.close()

        assertTrue(cache.commit(key(1), second))
        second.close()

        assertEquals(0, firstDisposals)
        assertSame(firstBitmap, active.asset.bitmap)
        assertNull(cache.acquire(key(0)))

        active.close()
        assertEquals(1, firstDisposals)
        cache.close()
    }

    @Test
    fun `replacement releases only the previous cache lease`() {
        var previousDisposals = 0
        var replacementDisposals = 0
        val cache = DesktopReaderImageCache(maxEntries = 2, maxBytes = 100)
        val previous = asset(estimatedBytes = 7, disposer = { previousDisposals++ })
        val replacement = asset(estimatedBytes = 11, disposer = { replacementDisposals++ })

        assertTrue(cache.commit(key(0), previous))
        previous.close()
        assertTrue(cache.commit(key(0), replacement))

        assertEquals(1, previousDisposals)
        assertEquals(0, replacementDisposals)
        assertEquals(11, cache.snapshot().usedBytes)
        cache.acquire(key(0))!!.let { acquired ->
            assertSame(replacement, acquired.asset)
            acquired.close()
        }

        replacement.close()
        cache.clear()
        assertEquals(1, replacementDisposals)
        cache.close()
    }

    @Test
    fun `clear and close release cache leases exactly once and remain idempotent`() {
        val disposals = mutableMapOf<Int, Int>()
        val cache = DesktopReaderImageCache(maxEntries = 2, maxBytes = 100)
        val first = asset(disposer = { disposals[0] = disposals.getOrDefault(0, 0) + 1 })
        val second = asset(disposer = { disposals[1] = disposals.getOrDefault(1, 0) + 1 })

        cache.commit(key(0), first)
        cache.commit(key(1), second)
        first.close()
        second.close()

        cache.clear()
        cache.clear()
        assertEquals(mapOf(0 to 1, 1 to 1), disposals)
        assertEquals(
            DesktopReaderImageCacheSnapshot(
                keys = emptyList(),
                entryCount = 0,
                usedBytes = 0,
                maxEntries = 2,
                maxBytes = 100,
            ),
            cache.snapshot(),
        )

        val third = asset(disposer = { disposals[2] = disposals.getOrDefault(2, 0) + 1 })
        cache.commit(key(2), third)
        third.close()
        cache.close()
        cache.close()

        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), disposals)
        assertTrue(cache.snapshot().keys.isEmpty())
        assertThrows(IllegalStateException::class.java) { cache.acquire(key(2)) }
    }

    private fun asset(
        estimatedBytes: Long = 10,
        disposer: () -> Unit = {},
    ): DesktopReaderImageAsset {
        val bitmap = Bitmap().apply { allocN32Pixels(2, 3) }.asComposeImageBitmap()
        return DesktopReaderImageAsset(
            bitmap = bitmap,
            sourceWidth = 2,
            sourceHeight = 3,
            estimatedBytes = estimatedBytes,
            sampled = false,
            disposer = disposer,
        )
    }

    private fun key(index: Int): ReaderPageDecodeKey = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(ReaderChapterId(7L), index),
            generation = 3L,
            encodedPageRef = EncodedPageRef("opaque://page-$index"),
        ),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = 2_048,
        maxHeight = 2_048,
    )
}
