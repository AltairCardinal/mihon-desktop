package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RED contract for same-chapter Retry attempt identity and selective pipeline invalidation. */
class DesktopReaderPageAttemptPipelineTest {

    @Test
    fun `full frame region and both caches use attempt identity while Retry preserves another page`() = runTest {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val fixture = Fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                asset(sourceWidth = LARGE_WIDTH, sourceHeight = LARGE_HEIGHT)
            },
        )
        var oldRegionOwner: DesktopReaderRegionPresentationOwner? = null
        var newRegionOwner: DesktopReaderRegionPresentationOwner? = null
        try {
            fixture.pipeline.beginGeneration(GENERATION)
            oldRegionOwner = regionOwner(this, fixture.pipeline)
            val targetOld = contentKey(TARGET_PAGE_ID, OLD_ATTEMPT)
            val other = contentKey(OTHER_PAGE_ID, OLD_ATTEMPT)

            cacheFullAndRegion(oldRegionOwner, fixture.pipeline, targetOld)
            cacheFullAndRegion(oldRegionOwner, fixture.pipeline, other)
            decodeFrame(fixture.pipeline, targetOld)

            val targetOldFull = fullKey(targetOld)
            val targetOldTile = tileKey(targetOld)
            val otherFull = fullKey(other)
            val otherTile = tileKey(other)
            assertTrue(targetOldFull in fixture.pipeline.snapshot().cache.keys)
            assertTrue(otherFull in fixture.pipeline.snapshot().cache.keys)
            assertTrue(targetOldTile in fixture.pipeline.snapshot().tileCache.keys)
            assertTrue(otherTile in fixture.pipeline.snapshot().tileCache.keys)

            oldRegionOwner.close()
            oldRegionOwner = null
            assertTrue(
                fixture.pipeline.beginPageAttempt(
                    pageId = TARGET_PAGE_ID,
                    generation = GENERATION,
                    attemptGeneration = RETRY_ATTEMPT,
                ),
            )
            assertFalse(
                fixture.pipeline.beginPageAttempt(
                    pageId = TARGET_PAGE_ID,
                    generation = GENERATION,
                    attemptGeneration = OLD_ATTEMPT,
                ),
            )

            assertFalse(targetOldFull in fixture.pipeline.snapshot().cache.keys)
            assertFalse(targetOldTile in fixture.pipeline.snapshot().tileCache.keys)
            assertNull(fixture.pipeline.acquireCached(targetOldFull))
            assertNull(fixture.pipeline.acquireCached(targetOldTile))
            checkNotNull(fixture.pipeline.acquireCached(otherFull)).close()
            checkNotNull(fixture.pipeline.acquireCached(otherTile)).close()

            val targetRetry = contentKey(TARGET_PAGE_ID, RETRY_ATTEMPT)
            newRegionOwner = regionOwner(this, fixture.pipeline)
            cacheFullAndRegion(newRegionOwner, fixture.pipeline, targetRetry)
            decodeFrame(fixture.pipeline, targetRetry)

            PageDecodePurpose.entries.forEach { purpose ->
                val attempts = decodedKeys
                    .filter { key -> key.contentKey.pageId == TARGET_PAGE_ID && key.purpose == purpose }
                    .mapTo(linkedSetOf()) { key -> key.contentKey.attemptGeneration }
                assertEquals(
                    setOf(OLD_ATTEMPT, RETRY_ATTEMPT),
                    attempts,
                    "$purpose decode identity must advance with Retry attempt",
                )
            }
            assertNotEquals(fullKey(targetOld), fullKey(targetRetry))
            assertNotEquals(frameKey(targetOld), frameKey(targetRetry))
            assertNotEquals(tileKey(targetOld), tileKey(targetRetry))
            assertTrue(fullKey(targetRetry) in fixture.pipeline.snapshot().cache.keys)
            assertTrue(tileKey(targetRetry) in fixture.pipeline.snapshot().tileCache.keys)
            assertTrue(otherFull in fixture.pipeline.snapshot().cache.keys)
            assertTrue(otherTile in fixture.pipeline.snapshot().tileCache.keys)
        } finally {
            newRegionOwner?.close()
            oldRegionOwner?.close()
            fixture.close()
        }
    }

    @Test
    fun `Retry rejects non cooperative old full frame and region results and releases every asset`() = runTest {
        val entered = CountDownLatch(PageDecodePurpose.entries.size)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(PageDecodePurpose.entries.size)
        val returnedPurposes = CopyOnWriteArrayList<PageDecodePurpose>()
        val disposals = PageDecodePurpose.entries.associateWith { AtomicInteger() }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fixture = Fixture(
            scope = ioScope,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                asset(
                    sourceWidth = LARGE_WIDTH,
                    sourceHeight = LARGE_HEIGHT,
                    onDispose = { disposals.getValue(key.purpose).incrementAndGet() },
                )
            },
        )
        fixture.pipeline.beginGeneration(GENERATION)
        val oldContent = contentKey(TARGET_PAGE_ID, OLD_ATTEMPT)
        val animationSession = fixture.pipeline.openAnimationSession(oldContent)
        val regionSession = fixture.pipeline.openRegionSession(oldContent)
        try {
            fun launchAcquire(purpose: PageDecodePurpose, acquire: suspend () -> DesktopReaderImageAssetLease?) {
                ioScope.launch {
                    try {
                        val lease = acquire()
                        if (lease != null) {
                            returnedPurposes += purpose
                            if (purpose == PageDecodePurpose.REGION_TILE) {
                                fixture.pipeline.commitRegionTile(tileKey(oldContent), lease)
                            }
                            lease.close()
                        }
                    } catch (_: CancellationException) {
                        // The old attempt is expected to be cancelled or rejected at handoff.
                    } finally {
                        finished.countDown()
                    }
                }
            }

            launchAcquire(PageDecodePurpose.FULL_PAGE) {
                fixture.pipeline.acquire(fullKey(oldContent))
            }
            launchAcquire(PageDecodePurpose.ANIMATION_FRAME) {
                fixture.pipeline.acquireAnimationFrame(animationSession, frameKey(oldContent))
            }
            launchAcquire(PageDecodePurpose.REGION_TILE) {
                fixture.pipeline.acquireRegionTile(regionSession, tileKey(oldContent))
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })

            assertTrue(
                fixture.pipeline.beginPageAttempt(
                    pageId = TARGET_PAGE_ID,
                    generation = GENERATION,
                    attemptGeneration = RETRY_ATTEMPT,
                ),
            )
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { finished.await(5, TimeUnit.SECONDS) })
            awaitCondition { fixture.pipeline.snapshot().inFlightCount == 0 }

            assertTrue(returnedPurposes.isEmpty(), "No old-attempt decode may escape to a presentation caller")
            assertTrue(
                fixture.pipeline.snapshot().cache.keys.none { key ->
                    key.contentKey.pageId == TARGET_PAGE_ID &&
                        key.contentKey.attemptGeneration == OLD_ATTEMPT
                },
            )
            assertTrue(
                fixture.pipeline.snapshot().tileCache.keys.none { key ->
                    key.contentKey.pageId == TARGET_PAGE_ID &&
                        key.contentKey.attemptGeneration == OLD_ATTEMPT
                },
            )
            awaitCondition { fixture.contentOwner.snapshot().activeLeaseCounts.isEmpty() }
            PageDecodePurpose.entries.forEach { purpose ->
                awaitCondition { disposals.getValue(purpose).get() > 0 }
                assertEquals(1, disposals.getValue(purpose).get(), "$purpose late asset must be released exactly once")
            }
        } finally {
            release.countDown()
            animationSession.close()
            regionSession.close()
            fixture.close()
            ioScope.cancel()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
    ) : AutoCloseable {
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock { 0L },
        )
        val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { ENCODED_BYTES },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = decoder,
        )

        override fun close() {
            pipeline.close()
            contentOwner.close()
        }
    }

    private companion object {
        const val GENERATION = 12L
        const val OLD_ATTEMPT = 0L
        const val RETRY_ATTEMPT = 1L
        const val LARGE_WIDTH = 4_001
        const val LARGE_HEIGHT = 4_000
        val CHAPTER_ID = ReaderChapterId(812L)
        val TARGET_PAGE_ID = ReaderPageId(CHAPTER_ID, 0)
        val OTHER_PAGE_ID = ReaderPageId(CHAPTER_ID, 1)
        val ENCODED_REF = EncodedPageRef("opaque://attempt-stable-page")
        val ENCODED_BYTES = byteArrayOf(0x41, 0x54, 0x54, 0x45, 0x4D, 0x50, 0x54)

        fun contentKey(pageId: ReaderPageId, attemptGeneration: Long) = ReaderPageContentOpenRequest(
            pageId = pageId,
            generation = GENERATION,
            encodedPageRef = ENCODED_REF,
            attemptGeneration = attemptGeneration,
        )

        fun fullKey(contentKey: ReaderPageContentOpenRequest) = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        fun frameKey(contentKey: ReaderPageContentOpenRequest) = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.ANIMATION_FRAME,
            maxWidth = 2_048,
            maxHeight = 2_048,
            frameIndex = 0,
        )

        fun tileKey(contentKey: ReaderPageContentOpenRequest) = ReaderPageDecodeKey(
            contentKey = contentKey,
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 2_048,
            maxHeight = 2_048,
            region = PixelBounds(0, 0, LARGE_WIDTH, LARGE_HEIGHT),
        )

        fun identity(contentKey: ReaderPageContentOpenRequest) = DesktopReaderPresentationImageSlotIdentity(
            pageId = contentKey.pageId,
            generation = contentKey.generation,
            attemptGeneration = contentKey.attemptGeneration,
        )

        fun regionOwner(
            scope: CoroutineScope,
            pipeline: DesktopReaderPageImagePipeline,
        ) = DesktopReaderRegionPresentationOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            managesPipelineGeneration = false,
        ).also { owner -> owner.beginGeneration(GENERATION) }

        suspend fun cacheFullAndRegion(
            owner: DesktopReaderRegionPresentationOwner,
            pipeline: DesktopReaderPageImagePipeline,
            contentKey: ReaderPageContentOpenRequest,
        ) {
            checkNotNull(pipeline.acquire(fullKey(contentKey))).close()
            val holder = owner.createHolder(identity(contentKey), fullKey(contentKey))
            try {
                holder.acquire()
                awaitCondition { holder.snapshot().regionTilesEnabled }
                holder.updateViewportTiles(setOf(tileKey(contentKey)))
                awaitCondition { tileKey(contentKey) in holder.snapshot().readyTiles }
            } finally {
                holder.close()
            }
        }

        suspend fun decodeFrame(
            pipeline: DesktopReaderPageImagePipeline,
            contentKey: ReaderPageContentOpenRequest,
        ) {
            val session = pipeline.openAnimationSession(contentKey)
            try {
                checkNotNull(pipeline.acquireAnimationFrame(session, frameKey(contentKey))).close()
            } finally {
                session.close()
            }
        }

        fun asset(
            sourceWidth: Int,
            sourceHeight: Int,
            onDispose: () -> Unit = {},
        ): DesktopReaderImageAsset {
            val bitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
            return DesktopReaderImageAsset(
                bitmap = bitmap.asComposeImageBitmap(),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                estimatedBytes = 16L,
                sampled = true,
                disposer = {
                    bitmap.close()
                    onDispose()
                },
            )
        }

        suspend fun awaitCondition(condition: () -> Boolean) {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    while (!condition()) delay(10)
                }
            }
        }
    }
}
