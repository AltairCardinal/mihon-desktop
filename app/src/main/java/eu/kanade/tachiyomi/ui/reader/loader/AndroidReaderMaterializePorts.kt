package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageDescriptor
import tachiyomi.domain.source.service.toSourceAppError
import java.io.InputStream

internal class AndroidReaderChapterContentPort(
    private val chapter: ReaderChapter,
    private val loader: PageLoader,
) : ReaderChapterContentPort {

    var materializedPages: List<ReaderPage> = emptyList()
        private set

    override suspend fun loadChapterContent(request: ReaderChapterContentRequest): List<ReaderPageDescriptor> {
        materializedPages = try {
            loader.getPages().onEach { page ->
                page.chapter = chapter
                if (page.status == Page.State.Ready && page.stream != null) {
                    page.encodedPageRef = page.encodedPageRef ?: EncodedPageRef(
                        "android-reader:${request.chapterId.value}:${request.generation}:${page.index}",
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw error.asPortException(loader.isLocal)
        }
        return materializedPages.map { page ->
            ReaderPageDescriptor(
                sourcePageIndex = page.index,
                url = page.url,
                imageUrl = page.imageUrl,
                encodedPageRef = page.encodedPageRef,
                initialLoadState = page.toSharedLoadState(),
            )
        }
    }
}

internal class AndroidReaderPageFetchPort(
    private val page: ReaderPage,
    private val source: HttpSource,
    private val chapterCache: ChapterCache,
) : ReaderPageFetchPort {

    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String = sourceCall {
        source.getImageUrl(page)
    }

    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? {
        val imageUrl = request.requireImageUrl()
        return if (chapterCache.isImageInCache(imageUrl)) EncodedPageRef(imageUrl) else null
    }

    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
        val imageUrl = request.requireImageUrl()
        page.imageUrl = imageUrl
        val response = sourceCall { source.getImage(page) }
        try {
            chapterCache.putImageToCache(imageUrl, response)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            throw AppErrorException(AppError.Storage(error))
        }
        return EncodedPageRef(imageUrl)
    }

    fun openEncodedPage(ref: EncodedPageRef): InputStream = chapterCache.getImageFile(ref.value).inputStream()

    private suspend fun <T> sourceCall(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: AppErrorException) {
        throw error
    } catch (error: Throwable) {
        throw AppErrorException(error.toSourceAppError())
    }
}

private fun ReaderPageFetchRequest.requireImageUrl(): String = imageUrl
    ?.takeIf(String::isNotBlank)
    ?: throw AppErrorException(AppError.MalformedData(IllegalArgumentException("Image URL is missing")))

private fun Throwable.asPortException(isLocal: Boolean): AppErrorException = when (this) {
    is AppErrorException -> this
    else -> AppErrorException(if (isLocal) AppError.Storage(this) else toSourceAppError())
}
