package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.reader.content.DownloadArtifactProbe
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.partial.DisabledPartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.desktop.download.DirectPartialPageReadLeaseSource
import mihon.desktop.download.PartialPageReadLeaseSource
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderSessionCore
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

data class DesktopReaderRuntime(
    val prefs: ReaderPreferences,
    val pageImagePipeline: DesktopReaderPageImagePipeline,
    val presentationImageOwner: DesktopReaderPresentationImageOwner,
    val pageContentOwner: DesktopReaderPageContentOwner,
    val session: DesktopReaderSession,
    internal val encodedPageStore: DesktopReaderEncodedPageStore,
    private val prefetchPreferenceJob: Job,
    internal val contentAdapter: DesktopReaderContentAdapter = DesktopReaderContentAdapter(),
) : AutoCloseable {
    private class CloseAttempt(
        val ownerThread: Thread,
    ) {
        val completed = CountDownLatch(1)

        @Volatile
        var failure: Throwable? = null

        fun awaitCompletion() {
            var interrupted = false
            while (true) {
                try {
                    completed.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private val closeAttempt = AtomicReference<CloseAttempt?>()

    override fun close() {
        val candidate = CloseAttempt(Thread.currentThread())
        if (!closeAttempt.compareAndSet(null, candidate)) {
            val activeAttempt = checkNotNull(closeAttempt.get())
            if (activeAttempt.ownerThread === Thread.currentThread() && activeAttempt.completed.count != 0L) {
                return
            }
            activeAttempt.awaitCompletion()
            activeAttempt.failure?.let { throw it }
            return
        }

        var failure: Throwable? = null
        fun closeStage(stage: () -> Unit) {
            try {
                stage()
            } catch (error: Throwable) {
                val firstFailure = failure
                if (firstFailure == null) {
                    failure = error
                } else if (firstFailure !== error) {
                    firstFailure.addSuppressed(error)
                }
            }
        }

        try {
            closeStage { prefetchPreferenceJob.cancel() }
            closeStage(presentationImageOwner::close)
            closeStage(pageImagePipeline::close)
            closeStage(session::close)
            closeStage(pageContentOwner::close)
            closeStage(contentAdapter::close)
        } finally {
            candidate.failure = failure
            candidate.completed.countDown()
        }
        failure?.let { throw it }
    }
}

internal fun desktopReaderRuntimeFactory(): DesktopReaderRuntimeFactory = Injekt.get()

class DesktopReaderRuntimeFactory internal constructor(
    private val prefs: ReaderPreferences,
    private val downloadProvider: DesktopDownloadProvider,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
    private val progressTracker: ReaderProgressTracker,
    private val mangaRepository: MangaRepository?,
    private val encodedCacheDirectory: File,
    private val downloadArtifactProbeFactory: (DesktopReaderChapterContext) -> DownloadArtifactProbe = { context ->
        downloadProvider.canonicalArtifactProbe()
    },
    private val readerIoProbe: ReaderIoProbe = ReaderIoProbe.None,
    private val readerMonotonicClock: ReaderMonotonicClock = ReaderMonotonicClock(System::nanoTime),
    private val readerIoGate: ReaderIoGate = ReaderIoGate.None,
    private val readerContentOperationProbe: DesktopReaderContentOperationProbe = DesktopReaderContentOperationProbe.None,
    private val disallowNonAsciiFilenames: () -> Boolean = { false },
    private val pageImageDecoder: DesktopReaderPageImageDecoder = SkiaDesktopReaderPageImageDecoder(),
    private val partialDownloadSnapshotLookup: PartialDownloadSnapshotLookup = DisabledPartialDownloadSnapshotLookup,
    private val partialPageReadLeaseSource: PartialPageReadLeaseSource = DirectPartialPageReadLeaseSource,
) {
    internal val configuredReaderIoProbe: ReaderIoProbe get() = readerIoProbe
    internal val configuredPartialDownloadSnapshotLookup: PartialDownloadSnapshotLookup
        get() = partialDownloadSnapshotLookup
    private val encodedPageStoreCoordinator = DesktopReaderEncodedPageStoreCoordinator(encodedCacheDirectory)
    private val partialPageFallbackCoordinator = DesktopReaderPartialPageFallbackCoordinator()

    fun createRuntime(
        initialContext: DesktopReaderChapterContext,
        parentScope: CoroutineScope,
        progressTrackerOverride: ReaderProgressTracker? = null,
    ): DesktopReaderRuntime {
        val ioReporter = ReaderIoReporter(readerIoProbe.bind(), readerMonotonicClock)
        val store = encodedPageStoreCoordinator.openSessionStore()
        val pageContentOwner = DesktopReaderPageContentOwner(parentScope, store::read, ioReporter)
        lateinit var session: DesktopReaderSession
        val pageImagePipeline = DesktopReaderPageImagePipeline(
            scope = parentScope,
            pageContentOwner = pageContentOwner,
            ioReporter = ioReporter,
            decoder = pageImageDecoder,
            partialDecodeFailureHandler = { request -> session.handlePartialPageDecodeFailure(request) },
        )
        val contentAdapter = DesktopReaderContentAdapter(
            contentOperationProbe = readerContentOperationProbe,
        )
        val core = ReaderSessionCore(
            initialChapterId = ReaderChapterId(initialContext.chapterId),
            sessionId = UUID.randomUUID().toString(),
            requestScheduler = ReaderRequestScheduler(
                ReaderSchedulerPolicy(
                    nearbyForward = 4,
                    nearbyBackward = 1,
                    maxConcurrentRequests = DEFAULT_CONCURRENT_REQUESTS,
                ),
            ),
        )
        val tracker = progressTrackerOverride ?: progressTracker
        var presentationImageOwner: DesktopReaderPresentationImageOwner? = null
        session = DesktopReaderSession(
            initialContext = initialContext,
            core = core,
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { context, leaseGeneration ->
                DesktopReaderChapterContentPort(
                    context = context,
                    downloadProvider = downloadProvider,
                    sourceManager = sourceManager,
                    contentAdapter = contentAdapter,
                    downloadArtifactLocator = downloadProvider.downloadArtifactLookup(
                        sourceId = context.sourceId,
                        candidateProbe = downloadArtifactProbeFactory(context),
                    ),
                    disallowNonAsciiFilenames = disallowNonAsciiFilenames(),
                    leaseGeneration = leaseGeneration,
                    partialDownloadSnapshotLookup = partialDownloadSnapshotLookup,
                )
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { context, descriptor ->
                DesktopReaderPageFetchPort(
                    context = context,
                    descriptor = descriptor,
                    sourceManager = sourceManager,
                    networkHelper = networkHelper,
                    encodedPageStore = store,
                    contentAdapter = contentAdapter,
                    partialDownloadSnapshotLookup = partialDownloadSnapshotLookup,
                    partialPageFallbackCoordinator = partialPageFallbackCoordinator,
                    partialPageCopyPort = DesktopReaderPartialPageFileCopyPort(partialPageReadLeaseSource),
                )
            },
            progressPort = DesktopReaderProgressPort { context, effect ->
                if (context.localChapterPath == null) {
                    tracker.track(
                        eventId = effect.idempotencyKey,
                        chapterId = effect.chapterId.value,
                        lastPageRead = effect.lastPageRead,
                        totalPages = effect.totalPages,
                        sourceId = context.sourceId,
                        mangaId = context.mangaId,
                        chapterNumber = context.chapterNumber,
                        wasRead = effect.wasRead,
                        downloadIdentity = DownloadChapterIdentity(
                            sourceDisplayName = context.sourceDisplayName,
                            mangaTitle = context.mangaTitle,
                            chapterName = context.chapterTitle,
                            scanlator = context.scanlator,
                            chapterUrl = context.chapterUrl,
                            disallowNonAsciiFilenames = context.disallowNonAsciiFilenames,
                        ),
                    )
                }
            },
            chapterLeasePort = contentAdapter,
            parentScope = parentScope,
            initialNextChapterPrefetchMode = prefs.nextChapterPrefetchMode,
            ioReporter = ioReporter,
            ioGate = readerIoGate,
            onGenerationPublished = { generation ->
                checkNotNull(presentationImageOwner) {
                    "Reader generation wiring must be bound before the session starts"
                }.beginGeneration(generation)
            },
            partialPageFallbackCoordinator = partialPageFallbackCoordinator,
        )
        val prefetchPreferenceJob = parentScope.launch {
            prefs.nextChapterPrefetchPreference.changes().collect(session::setNextChapterPrefetchMode)
        }
        val pageIoObserver = ReaderPageIoObserver(ioReporter) { pageId, generation ->
            parentScope.launch { session.onFirstPagePresented(pageId, generation) }
        }
        val boundPresentationImageOwner = DesktopReaderPresentationImageOwner(
            scope = parentScope,
            pageImagePipeline = pageImagePipeline,
            pageIoObserver = pageIoObserver,
        )
        presentationImageOwner = boundPresentationImageOwner
        return DesktopReaderRuntime(
            prefs = prefs,
            pageImagePipeline = pageImagePipeline,
            presentationImageOwner = boundPresentationImageOwner,
            pageContentOwner = pageContentOwner,
            session = session,
            encodedPageStore = store,
            contentAdapter = contentAdapter,
            prefetchPreferenceJob = prefetchPreferenceJob,
        ).also { session.start() }
    }

    fun createModel(
        runtime: DesktopReaderRuntime,
        isWebtoon: Boolean,
        mangaViewerFlags: Long,
        dualPageOverride: Boolean?,
        ownedRuntimeScope: CoroutineScope? = null,
        onProductionClosed: () -> Unit = {},
    ): ReaderScreenModel = ReaderScreenModel(
        isWebtoon = isWebtoon,
        mangaViewerFlags = mangaViewerFlags,
        dualPageOverride = dualPageOverride,
        prefs = runtime.prefs,
        initialSessionState = runtime.session.state.value,
        onViewportSettled = runtime.session::settleViewport,
        onPageRetry = runtime.session::retryPage,
        onChapterRetry = runtime.session::retryChapter,
        onChapterActivated = { context ->
            runtime.session.activate(context)
            runtime.session.state.value
        },
        onNextChapterPrefetchChanged = runtime.session::updateNextChapter,
        runtime = runtime,
        ownedRuntimeScope = ownedRuntimeScope,
        onProductionClosed = onProductionClosed,
        persistViewerFlags = { targetMangaId, flags ->
            mangaRepository?.update(MangaUpdate(id = targetMangaId, viewerFlags = flags))
        },
    )

    fun createScreenModel(
        initialContext: DesktopReaderChapterContext,
        isWebtoon: Boolean,
        mangaViewerFlags: Long,
        dualPageOverride: Boolean?,
        progressTrackerOverride: ReaderProgressTracker? = null,
        onProductionClosed: () -> Unit = {},
    ): ReaderScreenModel {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtime = try {
            createRuntime(initialContext, runtimeScope, progressTrackerOverride)
        } catch (error: Throwable) {
            runtimeScope.cancel()
            throw error
        }
        return try {
            createModel(
                runtime = runtime,
                isWebtoon = isWebtoon,
                mangaViewerFlags = mangaViewerFlags,
                dualPageOverride = dualPageOverride,
                ownedRuntimeScope = runtimeScope,
                onProductionClosed = onProductionClosed,
            )
        } catch (error: Throwable) {
            runtime.close()
            runtimeScope.cancel()
            throw error
        }
    }

    private companion object {
        const val DEFAULT_CONCURRENT_REQUESTS = 3
    }
}
