package mihon.desktop.ui.reader.presentation

import kotlinx.coroutines.CoroutineScope
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock

internal class PresentationImageOwnerFixture(
    scope: CoroutineScope,
) : AutoCloseable {
    private val reporter = ReaderIoReporter(
        probe = ReaderIoProbe.None,
        clock = ReaderMonotonicClock { 0L },
    )
    private val contentOwner = DesktopReaderPageContentOwner(
        scope = scope,
        encodedPageReader = { null },
        ioReporter = reporter,
    )
    private val pipeline = DesktopReaderPageImagePipeline(
        scope = scope,
        pageContentOwner = contentOwner,
        ioReporter = reporter,
    )
    val owner = DesktopReaderPresentationImageOwner(
        scope = scope,
        pageImagePipeline = pipeline,
        pageIoObserver = ReaderPageIoObserver(reporter) { _, _ -> },
    )

    override fun close() {
        owner.close()
        pipeline.close()
        contentOwner.close()
    }
}
