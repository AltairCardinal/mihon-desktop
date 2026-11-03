package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.archiveReader
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val resolvedChapterPath: UniFile? = null,
) : PageLoader() {

    private val context: Application by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val latencyRequest = eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.requestFor(dbChapter.id ?: -1)
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.record(
            "CHAPTER_PATH_BEGIN",
            chapter = dbChapter.id ?: -1,
            request = latencyRequest,
        )
        val chapterPath = resolvedChapterPath ?: downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.record(
            "CHAPTER_PATH_END",
            chapter = dbChapter.id ?: -1,
            request = latencyRequest,
        )
        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath)
        } else {
            getPagesFromDirectory(chapterPath)
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val id = chapter.chapter.id ?: -1
        val request = eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.requestFor(id)
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.record(
            "ARCHIVE_OPEN_BEGIN",
            chapter = id,
            request = request,
        )
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.record("ARCHIVE_OPEN_END", chapter = id, request = request)
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.record(
            "ARCHIVE_LIST_BEGIN",
            chapter = id,
            request = request,
        )
        return loader.getPages().also {
            eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.record(
                "ARCHIVE_LIST_END",
                chapter = id,
                request = request,
            )
        }
    }

    private fun getPagesFromDirectory(chapterPath: UniFile?): List<ReaderPage> {
        val pages = if (resolvedChapterPath != null) {
            downloadManager.buildPageList(chapterPath)
        } else {
            downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        }
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream((page.uri as? Uri) ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }
}
