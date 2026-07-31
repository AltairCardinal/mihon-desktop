package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.reader.ReaderScreenModel
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class DesktopReaderRuntime(
    val prefs: ReaderPreferences,
    val preloader: PagePreloader,
    val tracker: ReaderProgressTracker,
    val pageLoader: DesktopReaderPageLoader,
)

object DesktopReaderRuntimeFactory {
    fun createRuntime(progressTracker: ReaderProgressTracker?, sourceId: Long): DesktopReaderRuntime {
        val networkHelper = Injekt.get<NetworkHelper>()
        return DesktopReaderRuntime(
            prefs = Injekt.get<ReaderPreferences>(),
            preloader = buildReaderPreloader(networkHelper, sourceId),
            tracker = progressTracker ?: Injekt.get<ReaderProgressTracker>(),
            pageLoader = DesktopReaderPageLoader(
                downloadProvider = Injekt.get<DesktopDownloadProvider>(),
                sourceManager = Injekt.get<SourceManager>(),
                networkHelper = networkHelper,
            ),
        )
    }

    fun createModel(
        chapterTitle: String,
        pageUrls: List<String>,
        initialPage: Int,
        chapterId: Long,
        isWebtoon: Boolean,
        sourceId: Long,
        chapterUrl: String,
        mangaTitle: String,
        mangaViewerFlags: Long,
        dualPageOverride: Boolean?,
        prefs: ReaderPreferences,
        pageLoader: DesktopReaderPageLoader,
    ): ReaderScreenModel {
        val mangaRepository = runCatching { Injekt.get<MangaRepository>() }.getOrNull()
        return ReaderScreenModel(
            chapterTitle = chapterTitle,
            pageUrls = pageUrls,
            initialPage = initialPage,
            chapterId = chapterId,
            isWebtoon = isWebtoon,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            mangaViewerFlags = mangaViewerFlags,
            dualPageOverride = dualPageOverride,
            prefs = prefs,
            adjacentChapterLoader = { chapter ->
                pageLoader.loadAdjacentChapter(
                    chapter = chapter,
                    sourceId = sourceId,
                    mangaTitle = mangaTitle,
                )
            },
            persistViewerFlags = { mangaId, flags ->
                mangaRepository?.update(MangaUpdate(id = mangaId, viewerFlags = flags))
            },
        )
    }
}

internal fun buildReaderPreloader(networkHelper: NetworkHelper, sourceId: Long): PagePreloader = PagePreloader(
    fetcher = { url ->
        try {
            val request = okhttp3.Request.Builder().url(url).build()
            networkHelper.clientForSource(sourceId).newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    },
    windowSize = 3,
)
