package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderPageModel
import tachiyomi.domain.source.service.SourceManager
import java.io.File

class DesktopReaderPageLoader(
    private val downloadProvider: DesktopDownloadProvider,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
) {
    suspend fun load(
        model: ReaderScreenModel,
        sourceId: Long,
        chapterUrl: String,
        mangaTitle: String,
        chapterTitle: String,
        initialPage: Int,
    ) {
        when (
            val result = resolvePageUrls(
                sourceId = sourceId,
                chapterUrl = chapterUrl,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                onPageCount = { model.setLoadingPageSlots(it, initialPage) },
                onPageLoaded = model::appendLoadedPage,
            )
        ) {
            is PageLoadResult.Loaded -> model.setLoadedPages(result.urls, initialPage)
            is PageLoadResult.Error -> model.setLoadError(result.message)
        }
    }

    suspend fun loadAdjacentChapter(
        chapter: ReaderChapterModel,
        sourceId: Long,
        mangaTitle: String,
    ): ReaderChapterState = when (
        val result = resolvePageUrls(
            sourceId = sourceId,
            chapterUrl = chapter.url,
            mangaTitle = mangaTitle,
            chapterTitle = chapter.name,
        )
    ) {
        is PageLoadResult.Loaded -> ReaderChapterState.Loaded(
            result.urls.mapIndexed { index, url ->
                ReaderPageModel(index = index, url = url, imageUrl = url.takeIf(String::isNotBlank))
            },
        )
        is PageLoadResult.Error -> ReaderChapterState.Error(
            error = AppError.Unknown(IllegalStateException(result.message)),
            retryTargetChapterId = chapter.id,
        )
    }

    private suspend fun resolvePageUrls(
        sourceId: Long,
        chapterUrl: String,
        mangaTitle: String,
        chapterTitle: String,
        onPageCount: (Int) -> Unit = {},
        onPageLoaded: (Int, String) -> Unit = { _, _ -> },
    ): PageLoadResult {
        val localPages = if (mangaTitle.isNotBlank()) {
            downloadProvider.getDownloadedPages(
                sourceId = sourceId,
                mangaTitle = mangaTitle,
                chapterName = chapterTitle,
            )
        } else {
            emptyList()
        }
        if (localPages.isNotEmpty()) {
            return PageLoadResult.Loaded(localPages.map { it.toURI().toString() })
        }

        val source = sourceManager.getCatalogueSources().find { it.id == sourceId }
            ?: return PageLoadResult.Error("Source not found (id=$sourceId)")
        val chapter = SChapter.create().apply {
            url = chapterUrl
            name = chapterTitle
        }
        val pages = when (val result = safeSourceCall { source.getPageList(chapter) }) {
            is SourceCallResult.Success -> result.value
            is SourceCallResult.Timeout -> return PageLoadResult.Error("Source timed out loading pages")
            is SourceCallResult.Error -> return PageLoadResult.Error(result.message)
        }
        if (pages.isEmpty()) return PageLoadResult.Error("Source returned 0 pages")

        val fetcher = SourcePageFetcher(source = source, fallbackClient = networkHelper.client)
        val tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "mihon_reader_${sourceId}_${chapterUrl.hashCode()}",
        ).also { it.mkdirs() }
        onPageCount(pages.size)
        val urls = coroutineScope {
            pages.mapIndexed { index, page ->
                async {
                    fetcher.fetchToFile(page, tempDir)?.also { onPageLoaded(index, it) }.orEmpty()
                }
            }.awaitAll()
        }
        return if (urls.any(String::isNotBlank)) {
            PageLoadResult.Loaded(urls)
        } else {
            PageLoadResult.Error("Failed to load any page images")
        }
    }

    private sealed interface PageLoadResult {
        data class Loaded(val urls: List<String>) : PageLoadResult
        data class Error(val message: String) : PageLoadResult
    }
}
