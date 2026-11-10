package mihon.desktop.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderUnavailableContentTest {
    @Test
    fun `missing content publishes original region and presentation failure without decode or draw`() = runTest {
        var reads = 0
        var decodes = 0
        val reporter = ReaderIoReporter(clock = ReaderMonotonicClock { 0L })
        val content = DesktopReaderPageContentOwner(this, {
            reads++
            null
        }, reporter)
        val pipeline = DesktopReaderPageImagePipeline(
            this,
            content,
            reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodes++
                error("Unavailable content cannot decode")
            },
        )
        val owner = DesktopReaderPresentationImageOwner(this, pipeline, ReaderPageIoObserver(reporter))
        try {
            owner.beginGeneration(1)
            val holder = owner.createHolder(
                DesktopReaderPresentationImageSlotIdentity(key().contentKey.pageId, 1),
                key(),
            )
            holder.acquire()
            runCurrent()
            assertEquals(1, reads)
            assertEquals(0, decodes)
            val failure = holder.state.value as DesktopReaderPresentationImageState.Failed
            assertTrue(failure.cause is IllegalStateException)
            assertTrue(holder.optionalRegionHolder!!.state.value.previewFailed)
            assertFalse(holder.acknowledgeDraw(holder.identity))
            assertNull(owner.firstPresentedGeneration.value)
            assertTrue(content.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            owner.close()
            pipeline.close()
            content.close()
        }
    }

    @Test
    fun `real content cancellation propagates its original cancellation instead of a failure`() = runTest {
        val cancellation = CancellationException("Actual encoded read cancelled")
        val reporter = ReaderIoReporter(clock = ReaderMonotonicClock { 0L })
        val content = DesktopReaderPageContentOwner(this, { throw cancellation }, reporter)
        val shared = DesktopReaderSharedPageContent(key().contentKey, this, content)
        try {
            var caught: CancellationException? = null
            try {
                shared.withContent { error("Cancelled content cannot be consumed") }
            } catch (error: CancellationException) {
                caught = error
            }
            assertEquals(cancellation.message, caught?.message)
            assertTrue(generateSequence(caught as Throwable?) { it.cause }.any { it === cancellation })
            assertTrue(content.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            shared.close()
            content.close()
        }
    }

    @Test
    fun `shared close during an awaited read keeps cancellation and releases leases`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val reporter = ReaderIoReporter(clock = ReaderMonotonicClock { 0L })
        val content = DesktopReaderPageContentOwner(this, {
            entered.complete(Unit)
            awaitCancellation()
        }, reporter)
        val shared = DesktopReaderSharedPageContent(key().contentKey, this, content)
        try {
            val pending = async { shared.withContent { error("Closed content cannot be consumed") } }
            runCurrent()
            entered.await()
            shared.close()
            runCurrent()
            var cancelled = false
            try {
                pending.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            cancelled = false
            try {
                shared.withContent { Unit }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(content.snapshot().activeLeaseCounts.isEmpty())
        } finally {
            shared.close()
            content.close()
        }
    }

    private fun key() = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(ReaderPageId(ReaderChapterId(41), 4), 1, EncodedPageRef("missing")),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = 2048,
        maxHeight = 2048,
    )
}
