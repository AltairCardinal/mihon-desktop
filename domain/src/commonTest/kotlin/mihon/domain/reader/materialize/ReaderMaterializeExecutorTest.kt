package mihon.domain.reader.materialize

import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderMaterializeExecutorTest {

    @Test
    fun `chapter content succeeds atomically and rejects an empty page list`() = runTest {
        val request = ReaderChapterContentRequest(ReaderChapterId(7), generation = 3)
        val pages = listOf(
            ReaderPageDescriptor(sourcePageIndex = 0, url = ""),
            ReaderPageDescriptor(sourcePageIndex = 1, url = "/page/1"),
        )

        val loaded = CanonicalReaderMaterializeExecutor.materializeChapter(
            request,
            ReaderChapterContentPort { pages },
        )
        val empty = CanonicalReaderMaterializeExecutor.materializeChapter(
            request,
            ReaderChapterContentPort { emptyList() },
        )

        assertEquals(pages, (loaded as ReaderChapterMaterializeResult.Loaded).pages)
        assertEquals(AppError.NoResults, (empty as ReaderChapterMaterializeResult.Failed).error)
    }

    @Test
    fun `chapter content preserves classified port errors`() = runTest {
        val request = ReaderChapterContentRequest(ReaderChapterId(7), generation = 3)

        val result = CanonicalReaderMaterializeExecutor.materializeChapter(
            request,
            ReaderChapterContentPort { throw AppErrorException(AppError.Server(500)) },
        )

        assertEquals(AppError.Server(500), (result as ReaderChapterMaterializeResult.Failed).error)
    }

    @Test
    fun `missing image url resolves then downloads to an opaque encoded reference`() = runTest {
        val request = pageRequest(imageUrl = null)
        val port = RecordingPageFetchPort(resolvedImageUrl = "https://example.test/image")
        val events = mutableListOf<ReaderPageMaterializeEvent>()

        val result = CanonicalReaderMaterializeExecutor.materializePage(request, port) { event ->
            events += event
            true
        }

        assertEquals(
            listOf(
                ReaderPageMaterializeEvent.ResolvingImage,
                ReaderPageMaterializeEvent.Downloading("https://example.test/image"),
                ReaderPageMaterializeEvent.Ready(
                    imageUrl = "https://example.test/image",
                    encodedPageRef = EncodedPageRef("encoded-1"),
                ),
            ),
            events,
        )
        assertEquals(
            ReaderPageMaterializeResult.Ready(
                imageUrl = "https://example.test/image",
                encodedPageRef = EncodedPageRef("encoded-1"),
            ),
            result,
        )
        assertEquals(1, port.resolveCalls)
        assertEquals(1, port.fetchCalls)
    }

    @Test
    fun `blank resolved image url becomes malformed data without fetching`() = runTest {
        val port = RecordingPageFetchPort(resolvedImageUrl = "  ")
        val events = mutableListOf<ReaderPageMaterializeEvent>()

        val result = CanonicalReaderMaterializeExecutor.materializePage(pageRequest(imageUrl = ""), port) { event ->
            events += event
            true
        }

        val failure = assertInstanceOf(ReaderPageMaterializeResult.Failed::class.java, result)
        assertInstanceOf(AppError.MalformedData::class.java, failure.error)
        assertInstanceOf(ReaderPageMaterializeEvent.Failed::class.java, events.last())
        assertEquals(0, port.fetchCalls)
    }

    @Test
    fun `retry forces a fresh fetch even when encoded content is cached`() = runTest {
        val cached = EncodedPageRef("cached")
        val normalPort = RecordingPageFetchPort(cached = cached)
        val retryPort = RecordingPageFetchPort(cached = cached)

        val normal = CanonicalReaderMaterializeExecutor.materializePage(pageRequest(), normalPort) { true }
        val retry = CanonicalReaderMaterializeExecutor.materializePage(
            request = pageRequest(),
            port = retryPort,
            forceRefresh = true,
            publish = { true },
        )

        assertEquals(ReaderPageMaterializeResult.Ready("https://example.test/image", cached), normal)
        assertEquals(0, normalPort.fetchCalls)
        assertEquals(1, retryPort.fetchCalls)
        assertEquals(
            ReaderPageMaterializeResult.Ready("https://example.test/image", EncodedPageRef("encoded-1")),
            retry,
        )
    }

    @Test
    fun `a stale request rejected at its first event leaves current ready state untouched`() = runTest {
        val port = RecordingPageFetchPort(failure = AppErrorException(AppError.Network()))
        val currentReadyState = ReaderPageMaterializeEvent.Ready(
            "https://example.test/current",
            EncodedPageRef("current"),
        )
        val request = pageRequest()
        val activeGeneration = request.generation + 1
        var visibleState: Any = currentReadyState
        val rejectedEvents = mutableListOf<ReaderPageMaterializeEvent>()

        val result = CanonicalReaderMaterializeExecutor.materializePage(request, port) { event ->
            if (request.generation != activeGeneration) {
                rejectedEvents += event
                false
            } else {
                visibleState = event
                true
            }
        }

        assertEquals(ReaderPageMaterializeResult.Rejected, result)
        assertEquals(currentReadyState, visibleState)
        assertEquals(listOf(ReaderPageMaterializeEvent.Downloading("https://example.test/image")), rejectedEvents)
        assertEquals(0, port.fetchCalls)
    }

    private fun pageRequest(imageUrl: String? = "https://example.test/image") = ReaderPageFetchRequest(
        pageId = ReaderPageId(ReaderChapterId(7), sourcePageIndex = 0),
        generation = 3,
        url = "/page/0",
        imageUrl = imageUrl,
    )

    private class RecordingPageFetchPort(
        private val resolvedImageUrl: String = "https://example.test/image",
        private val cached: EncodedPageRef? = null,
        private val failure: Throwable? = null,
    ) : ReaderPageFetchPort {
        var resolveCalls = 0
        var fetchCalls = 0

        override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String {
            resolveCalls++
            return resolvedImageUrl
        }

        override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = cached

        override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
            fetchCalls++
            failure?.let { throw it }
            return EncodedPageRef("encoded-$fetchCalls")
        }
    }
}
