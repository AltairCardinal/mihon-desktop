package mihon.desktop.reader

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.extension.resolveSourceImageResponse
import mihon.desktop.extension.resolveSourceImageUrl
import mihon.desktop.extension.sourceClientOrNull
import mihon.desktop.extension.sourceHeadersOrNull
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.ReaderDualPageFactReadPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import tachiyomi.domain.source.service.toSourceAppError
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Fetches page images through the source's own OkHttp client and headers.
 *
 * Problem: Coil uses Java's default HttpURLConnection which bypasses the source's
 * custom OkHttp client. Sources like manhuagui require a Referer header set in
 * headersBuilder() — without it image servers return empty or forbidden responses.
 *
 * Solution: Access the source's client and headers reflectively (HttpSource may be
 * in a child classloader, making direct casting impossible), build OkHttp requests
 * with the correct headers, and write the response to the canonical encoded-store destination.
 */
class SourcePageFetcher(
    private val source: CatalogueSource,
    private val fallbackClient: OkHttpClient,
) {

    /** Only missing source APIs may fall back; failed source getters stay visible. */
    val client: OkHttpClient
        get() = source.sourceClientOrNull() ?: fallbackClient

    suspend fun resolveImageUrl(page: Page): String {
        page.imageUrl?.takeIf(String::isNotBlank)?.let { return it }
        val resolved = try {
            resolveSourceImageUrl(source, page)
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

    suspend fun fetchToDestination(
        page: Page,
        destFile: File,
        factReadPermit: ReaderDualPageFactReadPermit? = null,
    ): SourcePageFetchResult {
        val imageUrl = try {
            resolveImageUrl(page)
        } catch (error: AppErrorException) {
            return SourcePageFetchResult.Failure(error.error)
        }
        page.imageUrl = imageUrl

        if (withContext(Dispatchers.IO) { destFile.isDecodableImage() }) {
            return SourcePageFetchResult.Success(destFile.toURI().toString())
        }
        if (destFile.exists()) destFile.delete()

        destFile.parentFile?.mkdirs()
        return try {
            withContext(Dispatchers.IO) {
                val response = resolveSourceImageResponse(source, page) ?: run {
                    val request = Request.Builder().url(imageUrl).apply {
                        source.sourceHeadersOrNull()?.let { headers(it) }
                    }.build()
                    client.newCall(request).execute()
                }
                response.use {
                    if (!response.isSuccessful) throw HttpException(response.code)
                    destFile.outputStream().buffered().use { out ->
                        if (factReadPermit == null) {
                            response.body.byteStream().copyTo(out)
                        } else {
                            factReadPermit.bound(
                                DesktopReaderFactBodySource.unbuffered(response.body.source()),
                                System::nanoTime,
                            )
                                .buffer().use { input ->
                                    input.inputStream().copyTo(out)
                                }
                        }
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

    private fun File.isDecodableImage(): Boolean =
        isFile && length() > 0L && SkiaImageDecoder.canDecodePixels(readBytes())
}

sealed interface SourcePageFetchResult {
    data class Success(val uri: String) : SourcePageFetchResult
    data class Failure(val error: AppError) : SourcePageFetchResult
}
