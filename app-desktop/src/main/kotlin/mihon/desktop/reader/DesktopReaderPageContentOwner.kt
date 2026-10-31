package mihon.desktop.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val encodedPageReader: suspend (EncodedPageRef) -> ByteArray?,
    private val ioReporter: ReaderIoReporter,
    private val encodedPrefixReader: (suspend (EncodedPageRef, Int) -> ByteArray?)? = null,
    ioGate: ReaderIoGate = ReaderIoGate.None,
) : AutoCloseable {
    private val contentGate = ioGate.bindContentReads()
    internal val controlOwnerId = contentGate.registerContentOwner()

    private val coordinator = ReaderPageContentOpenCoordinator(
        scope = scope,
        port = ReaderPageContentOpenPort<ByteArray> { request ->
            open(request, ReaderIoPurpose.CURRENT_PAGE)
        },
    )

    private suspend fun open(request: ReaderPageContentOpenRequest, purpose: ReaderIoPurpose): ByteArray? {
        val limit = request.maxBytes
        ioReporter.report(
            type = if (limit == null) ReaderIoEventType.OPEN_PAGE else ReaderIoEventType.HEADER_READ,
            chapterId = request.pageId.chapterId,
            pageId = request.pageId,
            generation = request.generation,
            purpose = purpose,
            byteLimit = limit,
        )
        return try {
            if (controlOwnerId != 0L) {
                contentGate.awaitContentRead(controlOwnerId, request)
                currentCoroutineContext().ensureActive()
                contentGate.contentReadStarted(controlOwnerId, request)
            }
            val bytes = if (limit == null) {
                encodedPageReader(request.encodedPageRef)
            } else {
                requireNotNull(encodedPrefixReader) {
                    "Bounded header reader is unavailable"
                }(request.encodedPageRef, limit)
            }
            if (controlOwnerId != 0L) contentGate.contentReadCompleted(controlOwnerId, request, bytes?.size, null)
            bytes
        } catch (failure: Throwable) {
            if (controlOwnerId != 0L) contentGate.contentReadCompleted(controlOwnerId, request, null, failure)
            throw failure
        }
    }

    suspend fun acquire(request: ReaderPageContentOpenRequest): ReaderPageContentLease<ByteArray>? =
        coordinator.acquire(request)

    suspend fun acquireHeader(
        request: ReaderPageContentOpenRequest,
        purpose: ReaderIoPurpose = ReaderIoPurpose.CURRENT_PAGE,
    ): ReaderPageContentLease<ByteArray>? {
        val openPort = ReaderPageContentOpenPort<ByteArray> { openRequest -> open(openRequest, purpose) }
        return coordinator.acquire(
            request.copy(maxBytes = mihon.domain.reader.ReaderDualPageFactLimits.MAX_HEADER_BYTES.toInt()),
            openPort,
        )
    }

    internal fun snapshot(): ReaderPageContentOpenSnapshot = coordinator.snapshot()

    override fun close() {
        try {
            coordinator.close()
        } finally {
            contentGate.closeContentOwner(controlOwnerId)
        }
    }
}
