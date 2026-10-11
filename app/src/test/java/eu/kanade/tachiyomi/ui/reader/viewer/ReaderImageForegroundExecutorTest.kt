package eu.kanade.tachiyomi.ui.reader.viewer

import coil3.request.Disposable
import coil3.request.ImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ReaderImageForegroundExecutorTest {
    @Test
    fun `original Coil cancellation remains busy until non cooperative decode actually exits`() = runTest {
        val baseline = ReaderPageImageView.foregroundWork.value
        val release = CompletableDeferred<Unit>()
        val job = async<ImageResult>(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { release.await() }
            throw CancellationException("cancelled original request")
        }
        val original = object : Disposable {
            override val job = job
            override val isDisposed get() = job.isCancelled
            override fun dispose() = job.cancel()
        }
        assertTrue(observeReaderImageRequest { original } === original)
        assertEquals(baseline + 1, ReaderPageImageView.foregroundWork.value)
        original.dispose()
        assertTrue(!job.isCompleted)
        assertEquals(baseline + 1, ReaderPageImageView.foregroundWork.value)
        release.complete(Unit)
        job.join()
        assertEquals(baseline, ReaderPageImageView.foregroundWork.value)
    }

    @Test
    fun `original Coil enqueue failure releases its observation exactly once`() {
        val baseline = ReaderPageImageView.foregroundWork.value
        assertThrows(RejectedExecutionException::class.java) {
            observeReaderImageRequest { throw RejectedExecutionException() }
        }
        assertEquals(baseline, ReaderPageImageView.foregroundWork.value)
    }

    @Test
    fun `accepted original queued task stays busy until its actual runnable exits`() {
        val baseline = ReaderPageImageView.foregroundWork.value
        val accepted = mutableListOf<Runnable>()
        val executor = ReaderImageForegroundExecutor(Executor { accepted.add(it) })
        var ran = false
        executor.execute { ran = true }
        assertEquals(baseline + 1, ReaderPageImageView.foregroundWork.value)
        assertTrue(!ran)
        accepted.single().run()
        assertTrue(ran)
        assertEquals(baseline, ReaderPageImageView.foregroundWork.value)
    }

    @Test
    fun `rejection and synchronous original failure balance observed work exactly once`() {
        val baseline = ReaderPageImageView.foregroundWork.value
        val rejected = ReaderImageForegroundExecutor(Executor { throw RejectedExecutionException() })
        assertThrows(RejectedExecutionException::class.java) { rejected.execute {} }
        assertEquals(baseline, ReaderPageImageView.foregroundWork.value)
        val immediate = ReaderImageForegroundExecutor(Executor { it.run() })
        assertThrows(IllegalStateException::class.java) { immediate.execute { error("original failure") } }
        assertEquals(baseline, ReaderPageImageView.foregroundWork.value)
    }
}
