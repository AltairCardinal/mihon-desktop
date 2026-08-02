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
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import mihon.domain.reader.storage.ReaderEncodedPageStore
import tachiyomi.domain.source.service.toSourceAppError
import java.io.IOException
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
    private val encodedPageStore: ReaderEncodedPageStore = AndroidReaderEncodedPageStore(chapterCache),
) : ReaderPageFetchPort {

    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String = sourceCall {
        source.getImageUrl(page)
    }

    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? {
        val imageUrl = request.requireImageUrl()
        val ref = EncodedPageRef(imageUrl)
        return if (encodedPageStore.contains(ref)) ref else null
    }

    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
        val imageUrl = request.requireImageUrl()
        page.imageUrl = imageUrl
        val response = sourceCall { source.getImage(page) }
        val result = try {
            encodedPageStore.store(EncodedPageRef(imageUrl)) {
                if (!chapterCache.putImageToCache(imageUrl, response)) {
                    throw IOException("Chapter cache rejected the image write: $imageUrl")
                }
                chapterCache.getImageFile(imageUrl).length()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            throw AppErrorException(AppError.Storage(error))
        }
        return when (result) {
            is EncodedPageStoreWriteResult.Stored -> result.entry.ref
            is EncodedPageStoreWriteResult.RejectedQuota -> throw AppErrorException(
                AppError.Storage(
                    IllegalStateException(
                        "Encoded page exceeds cache quota: ${result.entry.byteCount} > ${result.maxBytes}",
                    ),
                ),
            )
        }
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
