package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import mihon.core.archive.ArchiveReader
import mihon.core.archive.EpubReader
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterWindowEffect
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
    private val pageLoaderFactory: ((ReaderChapter) -> PageLoader)? = null,
    private val beforeBeginPageListLoad: (() -> Unit)? = null,
    private val materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
    private val routedPageLoaderFactories: ReaderPageLoaderFactories? = null,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        loadChapterInternal(chapter, pageListEffect = null)
    }

    internal suspend fun loadChapter(
        chapter: ReaderChapter,
        pageListEffect: ReaderChapterWindowEffect.BeginPageListLoad,
    ) {
        loadChapterInternal(chapter, pageListEffect)
    }

    private suspend fun loadChapterInternal(
        chapter: ReaderChapter,
        pageListEffect: ReaderChapterWindowEffect.BeginPageListLoad?,
    ) {
        beforeBeginPageListLoad?.invoke()
        val generation = if (pageListEffect == null) {
            chapter.beginPageListLoadIfNeeded()
        } else {
            chapter.beginPageListLoad(pageListEffect)
        }
        if (generation == null) {
            if (pageListEffect != null) awaitExistingWindowLoad(chapter)
            return
        }
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            var resolvedLoader: PageLoader? = null
            var terminalHandled = false
            try {
                val loader = pageLoaderFactory?.invoke(chapter) ?: getPageLoader(chapter)
                resolvedLoader = loader
                if (!chapter.installPageLoader(generation, loader)) {
                    chapter.retirePageLoader(generation, loader)
                    return@withIOContext
                }

                val port = AndroidReaderChapterContentPort(chapter, loader)
                val request = ReaderChapterContentRequest(
                    chapterId = ReaderChapterId(checkNotNull(chapter.chapter.id)),
                    generation = generation,
                )
                when (val result = materializeExecutor.materializeChapter(request, port)) {
                    is ReaderChapterMaterializeResult.Loaded -> {
                        val accepted = chapter.completePageListLoad(
                            generation = generation,
                            pages = port.materializedPages,
                            descriptors = result.pages,
                        )
                        if (!accepted) {
                            chapter.retirePageLoader(generation, loader)
                        }

                        // If the accepted chapter is partially read, set the starting page to the
                        // last the user read; otherwise preserve the requested page.
                        if (accepted && !chapter.chapter.read) {
                            chapter.requestedPage = chapter.chapter.last_page_read
                        }
                    }
                    is ReaderChapterMaterializeResult.Failed -> {
                        terminalHandled = true
                        val failure = result.toThrowable()
                        if (!chapter.failPageListLoad(generation, failure, result.error)) {
                            chapter.retirePageLoader(generation, loader)
                        }
                        throw failure
                    }
                }
            } catch (e: Throwable) {
                val failure = e.unwrapAppErrorCause()
                if (!terminalHandled && !chapter.failPageListLoad(generation, failure, e.toChapterAppError())) {
                    resolvedLoader?.let { chapter.retirePageLoader(generation, it) }
                }
                throw failure
            }
        }
    }

    private suspend fun awaitExistingWindowLoad(chapter: ReaderChapter) {
        val terminal = chapter.sharedSessionStateFlow.first {
            it.activeChapter.loadState !is ReaderChapterLoadState.LoadingPageList
        }.activeChapter.loadState
        when (terminal) {
            ReaderChapterLoadState.Loaded -> Unit
            is ReaderChapterLoadState.Error -> throw AppErrorException(terminal.error)
            ReaderChapterLoadState.Wait -> throw CancellationException("Chapter left the retained reader window")
            ReaderChapterLoadState.LoadingPageList -> error("Terminal page-list wait returned LoadingPageList")
        }
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            manga.source,
            skipCache = true,
        )
        val storedContent = isDownloaded || source is LocalSource
        val route = classifyStoredContentFailure(storedContent) {
            selectReaderChapterContentRoute(
                downloaded = isDownloaded,
                source = source,
                localFormat = (source as? LocalSource)?.let { localSource ->
                    { localSource.getFormat(chapter.chapter) }
                },
            )
        }
        return classifyStoredContentFailure(route.isStoredContent) {
            createReaderPageLoader(
                route = route,
                chapter = chapter,
                source = source,
                factories = routedPageLoaderFactories ?: defaultPageLoaderFactories(),
            )
        }
    }

    private fun defaultPageLoaderFactories() = defaultReaderPageLoaderFactories(
        context = context,
        manga = manga,
        source = source,
        downloadManager = downloadManager,
        downloadProvider = downloadProvider,
    )

    private inline fun <T> classifyStoredContentFailure(
        storedContent: Boolean,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: AppErrorException) {
        throw error
    } catch (error: Throwable) {
        if (storedContent) throw AppErrorException(AppError.Storage(error))
        throw error
    }

    private fun ReaderChapterMaterializeResult.Failed.toThrowable(): Throwable = cause
        ?: if (error == AppError.NoResults) {
            Exception(context.stringResource(MR.strings.page_list_empty_error))
        } else {
            AppErrorException(error)
        }

    private fun Throwable.toChapterAppError(): AppError = when (this) {
        is AppErrorException -> error
        is CancellationException -> AppError.Cancelled
        else -> AppError.Unknown(this)
    }

    private fun Throwable.unwrapAppErrorCause(): Throwable = when (this) {
        is AppErrorException -> cause ?: this
        else -> this
    }
}

internal fun defaultReaderPageLoaderFactories(
    context: Context,
    manga: Manga,
    source: Source,
    downloadManager: DownloadManager,
    downloadProvider: DownloadProvider,
    archiveReaderFactory: (UniFile) -> ArchiveReader = { file -> file.archiveReader(context) },
    epubReaderFactory: (UniFile) -> EpubReader = { file -> file.epubReader(context) },
    chapterCacheProvider: () -> ChapterCache = { Injekt.get() },
) = ReaderPageLoaderFactories(
    download = { chapter ->
        DownloadPageLoader(
            chapter,
            manga,
            source,
            downloadManager,
            downloadProvider,
        )
    },
    localDirectory = ::DirectoryPageLoader,
    localArchive = { file -> ArchivePageLoader(archiveReaderFactory(file)) },
    localEpub = { file -> EpubPageLoader(epubReaderFactory(file)) },
    online = { chapter, httpSource -> HttpPageLoader(chapter, httpSource, chapterCacheProvider()) },
    missingSource = { missingSource ->
        error(context.stringResource(MR.strings.source_not_installed, missingSource.toString()))
    },
    unsupported = { error(context.stringResource(MR.strings.loader_not_implemented_error)) },
)
