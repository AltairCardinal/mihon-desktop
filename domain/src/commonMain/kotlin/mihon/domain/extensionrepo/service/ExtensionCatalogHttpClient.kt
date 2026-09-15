package mihon.domain.extensionrepo.service

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Catalog redirects must be validated before a request reaches a new destination. */
fun OkHttpClient.withCatalogRedirectPolicy(): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        var request = chain.request()
        val visited = mutableSetOf<String>()
        var result: okhttp3.Response? = null
        try {
            for (hop in 0..4) {
                val url = request.url.toString()
                ExtensionStoreCatalogDecoder.requireUnvisitedCatalogUrl(visited, url)
                visited += url
                val response = chain.proceed(request)
                if (response.code !in setOf(301, 302, 303, 307, 308)) {
                    result = response.withBoundedCatalogBody()
                    break
                }
                val location = response.use { it.header("Location") }
                require(hop < 4) { "Too many catalog HTTP redirects" }
                val target = location?.let { request.url.resolve(it) }
                require(target != null) { "Catalog redirect has no valid Location" }
                ExtensionStoreCatalogDecoder.requireSupportedCatalogUrl(target.toString())
                request = request.newBuilder().apply {
                    if (target.host != request.url.host || target.port != request.url.port ||
                        target.scheme != request.url.scheme
                    ) {
                        removeHeader("Authorization")
                    }
                    url(target)
                }.build()
            }
            requireNotNull(result)
        } catch (error: IllegalArgumentException) {
            throw InvalidCatalogRequestException(error)
        }
    }
    .build()

class InvalidCatalogRequestException(cause: IllegalArgumentException) : IOException(cause)

private fun Response.withBoundedCatalogBody(): Response {
    val original = body
    val maximum = 64L * 1024 * 1024
    if (original.contentLength() > maximum) {
        close()
        throw InvalidCatalogRequestException(IllegalArgumentException("Catalog exceeds the maximum size"))
    }
    val limitedSource = object : ForwardingSource(original.source()) {
        var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, minOf(byteCount, maximum - total + 1))
            if (count > 0) total += count
            if (total > maximum) {
                close()
                throw InvalidCatalogRequestException(IllegalArgumentException("Catalog exceeds the maximum size"))
            }
            return count
        }
    }.buffer()
    return newBuilder().body(object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source() = limitedSource
    }).build()
}
