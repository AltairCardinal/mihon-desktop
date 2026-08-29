package mihon.desktop.reader

import kotlinx.coroutines.CoroutineScope
import mihon.domain.reader.content.ReaderPageContentLease
import mihon.domain.reader.content.ReaderPageContentOpenCoordinator
import mihon.domain.reader.content.ReaderPageContentOpenPort
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.content.ReaderPageContentOpenSnapshot
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.session.EncodedPageRef

/** Desktop adapter that uniquely owns encoded page opens for a reader runtime. */
class DesktopReaderPageContentOwner internal constructor(
    scope: CoroutineScope,
    encodedPageReader: suspend (EncodedPageRef) -> ByteArray?,
    ioReporter: ReaderIoReporter,
) : AutoCloseable {
    private val coordinator = ReaderPageContentOpenCoordinator(
        scope = scope,
        port = ReaderPageContentOpenPort<ByteArray> { request ->
            ioReporter.report(
                type = ReaderIoEventType.OPEN_PAGE,
                chapterId = request.pageId.chapterId,
                pageId = request.pageId,
                generation = request.generation,
                purpose = ReaderIoPurpose.CURRENT_PAGE,
            )
            encodedPageReader(request.encodedPageRef)
        },
    )

    suspend fun acquire(request: ReaderPageContentOpenRequest): ReaderPageContentLease<ByteArray>? =
        coordinator.acquire(request)

    internal fun snapshot(): ReaderPageContentOpenSnapshot = coordinator.snapshot()

    override fun close() = coordinator.close()
}
