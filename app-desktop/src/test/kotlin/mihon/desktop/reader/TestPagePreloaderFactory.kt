package mihon.desktop.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef

internal fun CoroutineScope.createTestPagePreloader(
    encodedPageReader: suspend (ref: EncodedPageRef) -> ByteArray?,
    windowSize: Int = 3,
    maxDecodedWidth: Int = 2_048,
    maxDecodedHeight: Int = 2_048,
    maxCacheBytes: Long = DesktopReaderPageImagePipeline.DEFAULT_CACHE_BYTES,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.IO,
): PagePreloader {
    val pipelineScope = CoroutineScope(coroutineContext + decodeDispatcher)
    val reporter = ReaderIoReporter(
        probe = ReaderIoProbe.None,
        clock = ReaderMonotonicClock(System::nanoTime),
    )
    val contentOwner = DesktopReaderPageContentOwner(
        scope = pipelineScope,
        encodedPageReader = encodedPageReader,
        ioReporter = reporter,
    )
    val pipeline = DesktopReaderPageImagePipeline(
        scope = pipelineScope,
        pageContentOwner = contentOwner,
        ioReporter = reporter,
        maxEntries = windowSize * 2 + 1,
        maxBytes = maxCacheBytes,
    )
    val preloader = PagePreloader(
        pageImagePipeline = pipeline,
        windowSize = windowSize,
        maxDecodedWidth = maxDecodedWidth,
        maxDecodedHeight = maxDecodedHeight,
    )
    checkNotNull(coroutineContext[Job]) { "Test page preloader requires a lifecycle Job" }
        .invokeOnCompletion {
            runCatching(preloader::close)
            runCatching(pipeline::close)
            runCatching(contentOwner::close)
        }
    return preloader
}
