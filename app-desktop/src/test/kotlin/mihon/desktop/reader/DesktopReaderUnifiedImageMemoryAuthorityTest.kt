package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RED contract for the single runtime authority over every decoded native image allocation. */
class DesktopReaderUnifiedImageMemoryAuthorityTest {

    @Test
    fun `one physical bitmap is counted once across leases and every image kind shares one snapshot`() {
        val authority = DesktopReaderImageMemoryAuthority(maxBytes = TOTAL_BUDGET)
        val disposals = DesktopReaderImageMemoryKind.entries.associateWith { AtomicInteger() }
        val bitmaps = DesktopReaderImageMemoryKind.entries.associateWith {
            Bitmap().apply { check(allocN32Pixels(2, 2)) }
        }
        val allocations = DesktopReaderImageMemoryKind.entries.associateWith { kind ->
            authority.register(
                kind = kind,
                estimatedBytes = KIND_BYTES,
                retention = DesktopReaderImageMemoryRetention.ACTIVE,
                disposer = {
                    bitmaps.getValue(kind).close()
                    disposals.getValue(kind).incrementAndGet()
                },
            )
        }
        val secondFullLease = allocations.getValue(DesktopReaderImageMemoryKind.FULL)
            .retain(DesktopReaderImageMemoryRetention.ACTIVE)
        val frameInFlight = allocations.getValue(DesktopReaderImageMemoryKind.FRAME)
            .retain(DesktopReaderImageMemoryRetention.IN_FLIGHT)

        try {
            val withOverlappingLeases = authority.snapshot()
            assertEquals(TOTAL_BUDGET, withOverlappingLeases.maxBytes)
            assertEquals(KIND_BYTES * DesktopReaderImageMemoryKind.entries.size, withOverlappingLeases.residentBytes)
            assertEquals(KIND_BYTES * DesktopReaderImageMemoryKind.entries.size, withOverlappingLeases.pinnedBytes)
            assertEquals(KIND_BYTES, withOverlappingLeases.inFlightBytes)
            assertEquals(0L, withOverlappingLeases.cacheBytes)
            assertEquals(0L, withOverlappingLeases.overBudgetBytes)
            assertEquals(
                DesktopReaderImageMemoryKind.entries.associateWith { KIND_BYTES },
                withOverlappingLeases.byKind,
            )
            assertEquals(
                KIND_BYTES,
                withOverlappingLeases.byKind.getValue(DesktopReaderImageMemoryKind.FULL),
                "A second lease of the same FULL bitmap must not double-count physical memory",
            )

            frameInFlight.close()
            allocations.getValue(DesktopReaderImageMemoryKind.FULL).close()
            assertEquals(
                KIND_BYTES,
                authority.snapshot().byKind.getValue(DesktopReaderImageMemoryKind.FULL),
                "The retained FULL lease must keep one allocation resident",
            )
            assertEquals(0L, authority.snapshot().inFlightBytes)
            assertEquals(0, disposals.getValue(DesktopReaderImageMemoryKind.FULL).get())

            secondFullLease.close()
            assertEquals(0L, authority.snapshot().byKind[DesktopReaderImageMemoryKind.FULL] ?: 0L)
            assertEquals(1, disposals.getValue(DesktopReaderImageMemoryKind.FULL).get())
        } finally {
            frameInFlight.close()
            secondFullLease.close()
            allocations.values.forEach(AutoCloseable::close)
            authority.close()
        }

        assertEquals(0L, authority.snapshot().residentBytes)
        DesktopReaderImageMemoryKind.entries.forEach { kind ->
            assertEquals(1, disposals.getValue(kind).get(), "$kind allocation must be disposed exactly once")
            assertTrue(bitmaps.getValue(kind).isClosed)
        }
    }

    @Test
    fun `FULL and TILE share 192 MiB and cross-purpose LRU eviction preserves ordinary revision semantics`() =
        runTest {
            val authority = DesktopReaderImageMemoryAuthority(maxBytes = TOTAL_BUDGET)
            val fixture = PipelineFixture(this, authority)
            try {
                fixture.pipeline.beginGeneration(GENERATION)
                fixture.cacheFull(FULL_A, 64L * MIB)
                fixture.cacheFull(FULL_B, 64L * MIB)
                val revisionAfterFull = fixture.pipeline.cacheRevision.value
                fixture.cacheTile(TILE_A, 64L * MIB)

                assertEquals(
                    revisionAfterFull,
                    fixture.pipeline.cacheRevision.value,
                    "A TILE admission must remain neutral to the ordinary decoded cache revision",
                )
                checkNotNull(fixture.pipeline.acquireCached(FULL_B)).close()
                checkNotNull(fixture.pipeline.acquireCached(TILE_A)).close()

                fixture.cacheTile(TILE_B, 16L * MIB)

                val snapshot = fixture.pipeline.snapshot()
                assertSame(authority, fixture.pipeline.memoryAuthority)
                assertEquals(authority.snapshot(), snapshot.memory)
                assertFalse(FULL_A in snapshot.cache.keys, "The globally oldest FULL cache retention must be evicted")
                assertTrue(FULL_B in snapshot.cache.keys)
                assertTrue(TILE_A in snapshot.tileCache.keys)
                assertTrue(TILE_B in snapshot.tileCache.keys)
                assertEquals(64L * MIB, snapshot.cache.usedBytes)
                assertEquals(80L * MIB, snapshot.tileCache.usedBytes)
                assertEquals(144L * MIB, snapshot.memory.residentBytes)
                assertEquals(144L * MIB, snapshot.memory.cacheBytes)
                assertEquals(
                    revisionAfterFull + 1L,
                    fixture.pipeline.cacheRevision.value,
                    "A cross-purpose eviction advances ordinary revision only because ordinary membership changed",
                )
                assertEquals(1, fixture.disposals.getValue(FULL_A).get())
                assertEquals(0, fixture.disposals.getValue(TILE_A).get())
            } finally {
                fixture.close()
                authority.close()
            }

            assertEquals(0L, authority.snapshot().residentBytes)
            fixture.disposals.values.forEach { count -> assertEquals(1, count.get()) }
        }

    @Test
    fun `active pins may exceed the soft budget survive close and release the last physical asset exactly once`() {
        val authority = DesktopReaderImageMemoryAuthority(maxBytes = 64L * MIB)
        val cachedDisposed = AtomicInteger()
        val fullDisposed = AtomicInteger()
        val derivedDisposed = AtomicInteger()
        val cached = authority.register(
            kind = DesktopReaderImageMemoryKind.TILE,
            estimatedBytes = 32L * MIB,
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
            disposer = { cachedDisposed.incrementAndGet() },
        )
        val cachedKey = tileKey(9)
        assertTrue(authority.admitCache(cachedKey, cached) {})
        cached.close()

        val fullBitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
        val full = authority.register(
            kind = DesktopReaderImageMemoryKind.FULL,
            estimatedBytes = 48L * MIB,
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
            disposer = {
                fullBitmap.close()
                fullDisposed.incrementAndGet()
            },
        )
        val externalDrawPin = full.retain(DesktopReaderImageMemoryRetention.ACTIVE)
        full.close()
        val derived = authority.register(
            kind = DesktopReaderImageMemoryKind.DERIVED,
            estimatedBytes = 48L * MIB,
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
            disposer = { derivedDisposed.incrementAndGet() },
        )

        assertEquals(0L, authority.snapshot().cacheBytes, "Pressure must first evict the unpinned TILE cache")
        assertEquals(96L * MIB, authority.snapshot().residentBytes)
        assertEquals(96L * MIB, authority.snapshot().pinnedBytes)
        assertEquals(32L * MIB, authority.snapshot().overBudgetBytes)
        assertEquals(1, cachedDisposed.get())
        assertFalse(fullBitmap.isClosed)
        assertEquals(2, fullBitmap.width)

        authority.close()
        assertEquals(96L * MIB, authority.snapshot().residentBytes, "Authority close must not invalidate external pins")
        assertFalse(fullBitmap.isClosed)

        derived.close()
        assertEquals(48L * MIB, authority.snapshot().residentBytes)
        assertEquals(0L, authority.snapshot().overBudgetBytes)
        assertEquals(1, derivedDisposed.get())
        externalDrawPin.close()

        assertEquals(0L, authority.snapshot().residentBytes)
        assertEquals(1, fullDisposed.get())
        assertTrue(fullBitmap.isClosed)
    }

    @Test
    fun `replacement commit racing acquire and clear never exposes or retains an unadmitted allocation`() {
        val authority = DesktopReaderImageMemoryAuthority(maxBytes = 64L * MIB)
        val cache = DesktopReaderImageCache(
            maxEntries = 2,
            maxBytes = 64L * MIB,
            memoryAuthority = authority,
            ownsMemoryAuthority = false,
        )
        val oldDisposerEntered = CountDownLatch(1)
        val releaseOldDisposer = CountDownLatch(1)
        val oldDisposals = AtomicInteger()
        val replacementDisposals = AtomicInteger()
        val oldBitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
        val replacementBitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
        val oldAsset = imageAsset(oldBitmap) {
            oldDisposerEntered.countDown()
            check(releaseOldDisposer.await(5, TimeUnit.SECONDS)) { "Timed out releasing the old allocation" }
            oldBitmap.close()
            oldDisposals.incrementAndGet()
        }
        val replacementAsset = imageAsset(replacementBitmap) {
            replacementBitmap.close()
            replacementDisposals.incrementAndGet()
        }
        val key = fullKey(17)
        val commitResult = AtomicReference<Boolean?>()
        val commitFailure = AtomicReference<Throwable?>()

        try {
            assertTrue(cache.commit(key, oldAsset))
            oldAsset.close()
            val replacementCommit = thread(name = "reader-cache-replacement") {
                try {
                    commitResult.set(cache.commit(key, replacementAsset))
                } catch (error: Throwable) {
                    commitFailure.set(error)
                }
            }

            try {
                assertTrue(oldDisposerEntered.await(5, TimeUnit.SECONDS), "Replacement never reached old disposal")
                assertTrue(
                    cache.snapshot().keys.isEmpty(),
                    "A replacement must remain provisional until the authority admits its allocation identity",
                )
                assertEquals(0L, cache.snapshot().usedBytes)
                val racedAcquire = cache.acquire(key)
                racedAcquire?.close()
                assertNull(
                    racedAcquire,
                    "An entry is not acquirable until the authority has atomically admitted its allocation identity",
                )
                cache.clear()
            } finally {
                releaseOldDisposer.countDown()
                replacementCommit.join(5_000)
            }

            assertFalse(replacementCommit.isAlive, "Replacement commit did not finish")
            assertNull(commitFailure.get())
            assertEquals(false, commitResult.get(), "Clear must make the in-progress admission roll back")
            replacementAsset.close()

            assertTrue(cache.snapshot().keys.isEmpty())
            assertEquals(0L, authority.snapshot().cacheBytes)
            assertEquals(0L, authority.snapshot().residentBytes)
            assertEquals(1, oldDisposals.get())
            assertEquals(1, replacementDisposals.get())
        } finally {
            releaseOldDisposer.countDown()
            oldAsset.close()
            replacementAsset.close()
            cache.close()
            authority.close()
        }
    }

    private class PipelineFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        authority: DesktopReaderImageMemoryAuthority,
    ) : AutoCloseable {
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock { 0L },
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { byteArrayOf(1) },
            ioReporter = reporter,
        )
        val disposals = listOf(FULL_A, FULL_B, TILE_A, TILE_B).associateWith { AtomicInteger() }
        private val requestedBytes = mutableMapOf<ReaderPageDecodeKey, Long>()
        val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                val bytes = checkNotNull(requestedBytes[key])
                val bitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
                DesktopReaderImageAsset(
                    bitmap = bitmap.asComposeImageBitmap(),
                    sourceWidth = 2,
                    sourceHeight = 2,
                    estimatedBytes = bytes,
                    sampled = false,
                    disposer = {
                        bitmap.close()
                        disposals.getValue(key).incrementAndGet()
                    },
                )
            },
            memoryAuthority = authority,
        )

        suspend fun cacheFull(key: ReaderPageDecodeKey, bytes: Long) {
            requestedBytes[key] = bytes
            checkNotNull(pipeline.acquire(key)).close()
        }

        suspend fun cacheTile(key: ReaderPageDecodeKey, bytes: Long) {
            requestedBytes[key] = bytes
            val session = pipeline.openRegionSession(key.contentKey)
            try {
                val lease = checkNotNull(pipeline.acquireRegionTile(session, key))
                try {
                    assertTrue(pipeline.commitRegionTile(key, lease))
                } finally {
                    lease.close()
                }
            } finally {
                session.close()
            }
        }

        override fun close() {
            pipeline.close()
            contentOwner.close()
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val TOTAL_BUDGET = 192L * MIB
        const val KIND_BYTES = 8L * MIB
        const val GENERATION = 1L
        val CHAPTER_ID = ReaderChapterId(940L)
        val ENCODED_REF = EncodedPageRef("opaque://unified-memory")
        val FULL_A = fullKey(0)
        val FULL_B = fullKey(1)
        val TILE_A = tileKey(2)
        val TILE_B = tileKey(3)

        fun fullKey(pageIndex: Int) = ReaderPageDecodeKey(
            contentKey = contentKey(pageIndex),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        fun tileKey(pageIndex: Int) = ReaderPageDecodeKey(
            contentKey = contentKey(pageIndex),
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 2_048,
            maxHeight = 2_048,
            region = PixelBounds(0, 0, 2_048, 2_048),
        )

        fun contentKey(pageIndex: Int) = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(CHAPTER_ID, pageIndex),
            generation = GENERATION,
            encodedPageRef = ENCODED_REF,
        )

        fun imageAsset(bitmap: Bitmap, disposer: () -> Unit) = DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            estimatedBytes = KIND_BYTES,
            sampled = false,
            disposer = disposer,
        )
    }
}
