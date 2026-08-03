package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.reader.ReaderScreenModel
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

data class DesktopReaderRuntime(
    val prefs: ReaderPreferences,
    val preloader: PagePreloader,
    val session: DesktopReaderSession,
    internal val encodedPageStore: DesktopReaderEncodedPageStore,
) : AutoCloseable {
    override fun close() {
        preloader.clear()
        session.close()
    }
}

internal fun desktopReaderRuntimeFactory(): DesktopReaderRuntimeFactory = Injekt.get()

class DesktopReaderRuntimeFactory(
    private val prefs: ReaderPreferences,
    private val downloadProvider: DesktopDownloadProvider,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
    private val progressTracker: ReaderProgressTracker,
    private val mangaRepository: MangaRepository?,
    private val encodedCacheDirectory: File,
) {
    private val encodedPageStoreCoordinator = DesktopReaderEncodedPageStoreCoordinator(encodedCacheDirectory)

    fun createRuntime(
        initialContext: DesktopReaderChapterContext,
        parentScope: CoroutineScope,
        progressTrackerOverride: ReaderProgressTracker? = null,
    ): DesktopReaderRuntime {
        val store = encodedPageStoreCoordinator.openSessionStore()
        val core = ReaderSessionCore(
            initialChapterId = ReaderChapterId(initialContext.chapterId),
            sessionId = UUID.randomUUID().toString(),
            requestScheduler = ReaderRequestScheduler(
                ReaderSchedulerPolicy(
                    nearbyForward = 4,
                    nearbyBackward = 1,
                    maxConcurrentRequests = PagePreloader.DEFAULT_CONCURRENT_REQUESTS,
                ),
            ),
        )
        val tracker = progressTrackerOverride ?: progressTracker
        val session = DesktopReaderSession(
            initialContext = initialContext,
            core = core,
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { context ->
                DesktopReaderChapterContentPort(context, downloadProvider, sourceManager)
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { context, descriptor ->
                DesktopReaderPageFetchPort(context, descriptor, sourceManager, networkHelper, store)
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
                    )
                }
            },
            parentScope = parentScope,
        )
        return DesktopReaderRuntime(
            prefs = prefs,
            preloader = PagePreloader(encodedPageReader = store::read, windowSize = 3),
            session = session,
            encodedPageStore = store,
        ).also { session.start() }
    }

    fun createModel(
        runtime: DesktopReaderRuntime,
        isWebtoon: Boolean,
        mangaViewerFlags: Long,
        dualPageOverride: Boolean?,
        ownedRuntimeScope: CoroutineScope? = null,
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
        runtime = runtime,
        ownedRuntimeScope = ownedRuntimeScope,
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
            )
        } catch (error: Throwable) {
            runtime.close()
            runtimeScope.cancel()
            throw error
        }
    }
}
