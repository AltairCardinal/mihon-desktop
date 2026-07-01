package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.desktop.ui.reader.ReaderScreenModel
import tachiyomi.domain.source.service.SourceManager
import java.io.File

class DesktopReaderPageLoader(
    private val downloadProvider: DesktopDownloadProvider,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
) {
    suspend fun load(
        model: ReaderScreenModel,
        scope: CoroutineScope,
        sourceId: Long,
        chapterUrl: String,
        mangaTitle: String,
        chapterTitle: String,
        initialPage: Int,
    ) {
        val localPages = if (mangaTitle.isNotBlank()) {
            downloadProvider.getDownloadedPages(sourceId = sourceId, mangaTitle = mangaTitle, chapterName = chapterTitle)
        } else {
            emptyList()
        }
        if (localPages.isNotEmpty()) {
            model.setLoadedPages(localPages.map { it.toURI().toString() }, initialPage)
            return
        }

        val source = sourceManager.getCatalogueSources().find { it.id == sourceId }
            ?: run {
                model.setLoadError("Source not found (id=$sourceId)")
                return
            }
        val chapter = SChapter.create().apply {
            url = chapterUrl
            name = chapterTitle
        }
        val pages = when (val result = safeSourceCall { source.getPageList(chapter) }) {
            is SourceCallResult.Success -> result.value
            is SourceCallResult.Timeout -> {
                model.setLoadError("Source timed out loading pages")
                return
            }
            is SourceCallResult.Error -> {
                model.setLoadError(result.message)
                return
            }
        }
        if (pages.isEmpty()) {
            model.setLoadError("Source returned 0 pages")
            return
        }

        val fetcher = SourcePageFetcher(source = source, fallbackClient = networkHelper.client)
        val tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "mihon_reader_${sourceId}_${chapterUrl.hashCode()}",
        ).also { it.mkdirs() }
        model.setLoadingPageSlots(pages.size, initialPage)
        pages.mapIndexed { index, page ->
            scope.launch {
                fetcher.fetchToFile(page, tempDir)?.let { model.appendLoadedPage(index, it) }
            }
        }.forEach { it.join() }
        if (model.hasLoadedPage()) {
            model.setLoadingDone()
        } else {
            model.setLoadError("Failed to load any page images")
        }
    }
}
