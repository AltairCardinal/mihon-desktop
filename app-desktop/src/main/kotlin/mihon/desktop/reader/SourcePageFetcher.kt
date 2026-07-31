package mihon.desktop.reader

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.source.service.toSourceAppError
import java.io.File

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
        source.javaClass.getMethod("getClient").invoke(source) as OkHttpClient
    }.getOrDefault(fallbackClient)

    /** Source's request headers (contains Referer, User-Agent, etc.), or null if unavailable. */
    private val headers: Headers? = runCatching {
        source.javaClass.getMethod("getHeaders").invoke(source) as Headers
    }.getOrNull()

    /**
     * Downloads [page] to [destDir] using the source's client and headers.
     *
     * @return a local `file://` URI on success, or the shared source error on failure.
     */
    suspend fun fetch(page: Page, destDir: File): SourcePageFetchResult {
        val imageUrl = page.imageUrl?.takeIf { it.isNotBlank() }
            ?: return SourcePageFetchResult.Failure(
                AppError.MalformedData(IllegalStateException("Page ${page.index + 1} has no image URL")),
            )
        val ext = imageUrl.substringAfterLast('.').substringBefore('?').take(4).ifBlank { "jpg" }
        val destFile = File(destDir, "page_${page.index.toString().padStart(4, '0')}.$ext")

        if (destFile.exists() && destFile.length() > 0L) {
            return SourcePageFetchResult.Success(destFile.toURI().toString())
        }

        val request = Request.Builder().url(imageUrl).apply {
            headers?.forEach { (name, value) -> header(name, value) }
        }.build()

        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpException(response.code)
                    destFile.outputStream().buffered().use { out ->
                        response.body.byteStream().copyTo(out)
                    }
                }
            }
            SourcePageFetchResult.Success(destFile.toURI().toString())
        } catch (error: CancellationException) {
            destFile.delete()
            throw error
        } catch (error: Exception) {
            destFile.delete()
            SourcePageFetchResult.Failure(error.toSourceAppError())
        }
    }

    /** Backward-compatible nullable API for callers that do not present failures. */
    suspend fun fetchToFile(page: Page, destDir: File): String? =
        (fetch(page, destDir) as? SourcePageFetchResult.Success)?.uri
}

sealed interface SourcePageFetchResult {
    data class Success(val uri: String) : SourcePageFetchResult
    data class Failure(val error: AppError) : SourcePageFetchResult
}
