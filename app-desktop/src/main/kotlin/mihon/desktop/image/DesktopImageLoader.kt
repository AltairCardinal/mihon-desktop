package mihon.desktop.image

import androidx.compose.runtime.compositionLocalOf
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.platform.DesktopNetworkHelper
import okhttp3.Call
import okhttp3.Headers
import okhttp3.Request
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException

/** A remote image that belongs to a source and must use that source's network policy. */
data class DesktopSourceImage(
    val url: String,
    val sourceId: Long,
)

internal val LocalDesktopSourceImageId = compositionLocalOf { 0L }

internal fun desktopSourceImageModel(url: String?, sourceId: Long): Any? = when {
    url == null -> null
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
        DesktopSourceImage(url, sourceId)
    else -> url
}

/** Installs the single production image loader before any Compose image request is created. */
internal fun installDesktopImageLoader(dependencies: DesktopUiDependencies) {
    SingletonImageLoader.setSafe { context ->
        createDesktopImageLoader(
            context = context,
            networkHelper = dependencies.networkHelper,
            sourceManager = dependencies.sourceManager,
        )
    }
}

internal fun createDesktopImageLoader(
    context: PlatformContext,
    networkHelper: DesktopNetworkHelper,
    sourceManager: SourceManager,
): ImageLoader = createDesktopImageLoader(
    context = context,
    defaultCallFactory = networkHelper.client,
    sourceCallFactory = { sourceId ->
        sourceManager.get(sourceId)?.sourceClientOrNull() ?: networkHelper.clientForSource(sourceId)
    },
    sourceHeaders = { sourceId -> sourceManager.get(sourceId)?.sourceHeadersOrNull() },
)

internal fun createDesktopImageLoader(
    context: PlatformContext,
    defaultCallFactory: Call.Factory,
    sourceCallFactory: (Long) -> Call.Factory,
    sourceHeaders: (Long) -> Headers?,
): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(DesktopSourceImageKeyer())
        add(DesktopSourceImageFetcher.Factory(sourceCallFactory, sourceHeaders))
        add(OkHttpNetworkFetcherFactory(defaultCallFactory))
    }
    .build()

private fun Any.sourceClientOrNull(): Call.Factory? = runCatching {
    javaClass.methods
        .firstOrNull { it.name == "getClient" && it.parameterCount == 0 }
        ?.invoke(this) as? Call.Factory
}.getOrNull()

private fun Any.sourceHeadersOrNull(): Headers? = runCatching {
    javaClass.methods
        .firstOrNull { it.name == "getHeaders" && it.parameterCount == 0 }
        ?.invoke(this) as? Headers
}.getOrNull()

private class DesktopSourceImageKeyer : Keyer<DesktopSourceImage> {
    override fun key(data: DesktopSourceImage, options: Options): String =
        "source-image:${data.sourceId}:${data.url}"
}

private class DesktopSourceImageFetcher(
    private val data: DesktopSourceImage,
    private val options: Options,
    private val callFactory: Call.Factory,
    private val headers: Headers?,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(data.url)
            .apply { headers?.let(::headers) }
            .build()
        val response = callFactory.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("HTTP $code while loading source image")
        }
        val body = response.body
        SourceFetchResult(
            source = ImageSource(source = body.source(), fileSystem = options.fileSystem),
            mimeType = body.contentType()?.toString(),
            dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK,
        )
    }

    class Factory(
        private val sourceCallFactory: (Long) -> Call.Factory,
        private val sourceHeaders: (Long) -> Headers?,
    ) : Fetcher.Factory<DesktopSourceImage> {
        override fun create(data: DesktopSourceImage, options: Options, imageLoader: ImageLoader): Fetcher =
            DesktopSourceImageFetcher(
                data = data,
                options = options,
                callFactory = sourceCallFactory(data.sourceId),
                headers = sourceHeaders(data.sourceId),
            )
    }
}
