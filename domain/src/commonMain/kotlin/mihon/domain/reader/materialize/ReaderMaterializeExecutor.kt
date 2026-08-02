package mihon.domain.reader.materialize

import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId

data class ReaderChapterContentRequest(
    val chapterId: ReaderChapterId,
    val generation: Long,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
    }
}

fun interface ReaderChapterContentPort {
    suspend fun loadChapterContent(request: ReaderChapterContentRequest): List<ReaderPageDescriptor>
}

sealed interface ReaderChapterMaterializeResult {
    data class Loaded(val pages: List<ReaderPageDescriptor>) : ReaderChapterMaterializeResult
    data class Failed(
        val error: AppError,
        val cause: Throwable? = error.cause,
    ) : ReaderChapterMaterializeResult
}

data class ReaderPageFetchRequest(
    val pageId: ReaderPageId,
    val generation: Long,
    val url: String,
    val imageUrl: String?,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
    }
}

interface ReaderPageFetchPort {
    suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String
    suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef?
    suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef
}

sealed interface ReaderPageMaterializeEvent {
    data object ResolvingImage : ReaderPageMaterializeEvent
    data class Downloading(val imageUrl: String) : ReaderPageMaterializeEvent
    data class Ready(
        val imageUrl: String,
        val encodedPageRef: EncodedPageRef,
    ) : ReaderPageMaterializeEvent
    data class Failed(
        val error: AppError,
        val cause: Throwable? = error.cause,
    ) : ReaderPageMaterializeEvent
}

sealed interface ReaderPageMaterializeResult {
    data class Ready(
        val imageUrl: String,
        val encodedPageRef: EncodedPageRef,
    ) : ReaderPageMaterializeResult
    data class Failed(val error: AppError) : ReaderPageMaterializeResult
    data object Rejected : ReaderPageMaterializeResult
}

interface ReaderMaterializeExecutor {
    suspend fun materializeChapter(
        request: ReaderChapterContentRequest,
        port: ReaderChapterContentPort,
    ): ReaderChapterMaterializeResult

    suspend fun materializePage(
        request: ReaderPageFetchRequest,
        port: ReaderPageFetchPort,
        forceRefresh: Boolean = false,
        publish: (ReaderPageMaterializeEvent) -> Boolean,
    ): ReaderPageMaterializeResult
}

object CanonicalReaderMaterializeExecutor : ReaderMaterializeExecutor {

    override suspend fun materializeChapter(
        request: ReaderChapterContentRequest,
        port: ReaderChapterContentPort,
    ): ReaderChapterMaterializeResult = try {
        val pages = port.loadChapterContent(request)
        if (pages.isEmpty()) {
            ReaderChapterMaterializeResult.Failed(AppError.NoResults)
        } else {
            ReaderChapterMaterializeResult.Loaded(pages)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        ReaderChapterMaterializeResult.Failed(error.toMaterializeAppError(), error.materializeCause())
    }

    override suspend fun materializePage(
        request: ReaderPageFetchRequest,
        port: ReaderPageFetchPort,
        forceRefresh: Boolean,
        publish: (ReaderPageMaterializeEvent) -> Boolean,
    ): ReaderPageMaterializeResult {
        return try {
            val imageUrl = request.imageUrl
                ?.takeIf(String::isNotBlank)
                ?: resolveImageUrl(request, port, publish)
                ?: return ReaderPageMaterializeResult.Rejected
            val resolvedRequest = request.copy(imageUrl = imageUrl)
            val encodedPageRef = if (forceRefresh) {
                null
            } else {
                port.findEncodedPage(resolvedRequest)
            } ?: run {
                if (!publish(ReaderPageMaterializeEvent.Downloading(imageUrl))) {
                    return ReaderPageMaterializeResult.Rejected
                }
                port.fetchEncodedPage(resolvedRequest)
            }
            val ready = ReaderPageMaterializeEvent.Ready(imageUrl, encodedPageRef)
            if (!publish(ready)) {
                ReaderPageMaterializeResult.Rejected
            } else {
                ReaderPageMaterializeResult.Ready(imageUrl, encodedPageRef)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val appError = error.toMaterializeAppError()
            val failed = ReaderPageMaterializeEvent.Failed(appError, error.materializeCause())
            if (publish(failed)) {
                ReaderPageMaterializeResult.Failed(appError)
            } else {
                ReaderPageMaterializeResult.Rejected
            }
        }
    }

    private suspend fun resolveImageUrl(
        request: ReaderPageFetchRequest,
        port: ReaderPageFetchPort,
        publish: (ReaderPageMaterializeEvent) -> Boolean,
    ): String? {
        if (!publish(ReaderPageMaterializeEvent.ResolvingImage)) return null
        val imageUrl = port.resolveImageUrl(request)
        if (imageUrl.isBlank()) {
            val cause = IllegalArgumentException("Resolved image URL must not be blank")
            throw AppErrorException(AppError.MalformedData(cause))
        }
        return imageUrl
    }
}

private fun Throwable.toMaterializeAppError(): AppError = when (this) {
    is AppErrorException -> error
    else -> AppError.Unknown(this)
}

private fun Throwable.materializeCause(): Throwable = when (this) {
    is AppErrorException -> cause ?: this
    else -> this
}
