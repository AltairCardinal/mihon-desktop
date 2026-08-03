package mihon.desktop.reader

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.domain.source.service.toSourceAppError
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Fetches page images through the source's own OkHttp client and headers.
 *
 * Problem: Coil uses Java's default HttpURLConnection which bypasses the source's
 * custom OkHttp client. Sources like manhuagui require a Referer header set in
 * headersBuilder() — without it image servers return empty or forbidden responses.
 *
 * Solution: Access the source's client and headers reflectively (HttpSource may be
 * in a child classloader, making direct casting impossible), build OkHttp requests
 * with the correct headers, and save responses to local temp files. Coil then loads
 * from file:// URIs which never require external headers.
 */
class SourcePageFetcher(
    private val source: CatalogueSource,
    private val fallbackClient: OkHttpClient,
) {

    /** Source's OkHttp client, resolved reflectively. Falls back to [fallbackClient]. */
    val client: OkHttpClient = runCatching {
        source.javaClass.getMethod("getClient").apply { trySetAccessible() }.invoke(source) as OkHttpClient
    }.getOrDefault(fallbackClient)

    /** Source's request headers (contains Referer, User-Agent, etc.), or null if unavailable. */
    private val headers: Headers? = runCatching {
        source.javaClass.getMethod("getHeaders").apply { trySetAccessible() }.invoke(source) as Headers
    }.getOrNull()

    suspend fun resolveImageUrl(page: Page): String {
        page.imageUrl?.takeIf(String::isNotBlank)?.let { return it }
        val resolved = try {
            when (source) {
                is HttpSource -> source.getImageUrl(page)
                else -> invokeReflectiveImageUrl(page)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            throw AppErrorException(cause.toSourceAppError())
        }
        return resolved?.takeIf(String::isNotBlank)
            ?: throw AppErrorException(
                AppError.MalformedData(IllegalStateException("Page ${page.index + 1} has no image URL")),
            )
    }

    /**
     * Downloads [page] to [destDir] using the source's client and headers.
     *
     * @return a local `file://` URI on success, or the shared source error on failure.
     */
    suspend fun fetch(page: Page, destDir: File): SourcePageFetchResult {
        val imageUrl = try {
            resolveImageUrl(page)
        } catch (error: AppErrorException) {
            return SourcePageFetchResult.Failure(error.error)
        }
        val ext = imageUrl.substringAfterLast('.').substringBefore('?').take(4).ifBlank { "jpg" }
        val destFile = File(destDir, "page_${page.index.toString().padStart(4, '0')}.$ext")

        page.imageUrl = imageUrl
        return fetchToDestination(page, destFile)
    }

    suspend fun fetchToDestination(page: Page, destFile: File): SourcePageFetchResult {
        val imageUrl = try {
            resolveImageUrl(page)
        } catch (error: AppErrorException) {
            return SourcePageFetchResult.Failure(error.error)
        }

        if (withContext(Dispatchers.IO) { destFile.isDecodableImage() }) {
            return SourcePageFetchResult.Success(destFile.toURI().toString())
        }
        if (destFile.exists()) destFile.delete()

        destFile.parentFile?.mkdirs()
        val fallbackRequest = Request.Builder().url(imageUrl).apply {
            headers?.forEach { (name, value) -> header(name, value) }
        }.build()

        return try {
            withContext(Dispatchers.IO) {
                val response = sourceImageResponse(page) ?: client.newCall(fallbackRequest).execute()
                response.use {
                    if (!response.isSuccessful) throw HttpException(response.code)
                    destFile.outputStream().buffered().use { out ->
                        response.body.byteStream().copyTo(out)
                    }
                    check(destFile.isDecodableImage()) {
                        "Page ${page.index + 1} response is not a decodable image"
                    }
                }
            }
            SourcePageFetchResult.Success(destFile.toURI().toString())
        } catch (error: CancellationException) {
            destFile.delete()
            throw error
        } catch (error: Exception) {
            destFile.delete()
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            SourcePageFetchResult.Failure(cause.toSourceAppError())
        }
    }

    /** Backward-compatible nullable API for callers that do not present failures. */
    suspend fun fetchToFile(page: Page, destDir: File): String? =
        (fetch(page, destDir) as? SourcePageFetchResult.Success)?.uri

    private suspend fun invokeReflectiveImageUrl(page: Page): String? {
        val method = source.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "getImageUrl" &&
                candidate.parameterCount == 2 &&
                candidate.parameterTypes.first().isAssignableFrom(page.javaClass)
        }?.apply { trySetAccessible() } ?: return null
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            val result = method.invoke(source, page, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as? String
        }
    }

    private suspend fun sourceImageResponse(page: Page): Response? = when (source) {
        is HttpSource -> source.getImage(page)
        else -> invokeReflectiveImage(page)
    }

    /** Child-classloader HttpSource implementations cannot always be cast to this process's HttpSource. */
    private suspend fun invokeReflectiveImage(page: Page): Response? {
        val method = source.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "getImage" &&
                candidate.parameterCount == 2 &&
                candidate.parameterTypes.first().isAssignableFrom(page.javaClass)
        }?.apply { trySetAccessible() } ?: return null
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            val result = method.invoke(source, page, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as? Response
        }
    }

    private fun File.isDecodableImage(): Boolean =
        isFile && length() > 0L && SkiaImageDecoder.canDecodePixels(readBytes())
}

sealed interface SourcePageFetchResult {
    data class Success(val uri: String) : SourcePageFetchResult
    data class Failure(val error: AppError) : SourcePageFetchResult
}
