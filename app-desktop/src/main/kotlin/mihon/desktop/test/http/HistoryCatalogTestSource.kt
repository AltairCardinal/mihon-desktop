package mihon.desktop.test.http

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.serialization.json.Json
import mihon.desktop.source.MangaDexSource
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/** Test Mode adapter uses the existing production parser and HTTP client against local fixtures. */
internal class HistoryCatalogTestSource(client: OkHttpClient, baseUrl: String) : CatalogueSource by MangaDexSource(
    client,
    Json { ignoreUnknownKeys = true },
    baseUrl,
    browserJsonFetcher = null,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1"))
    }
    override val id = SOURCE_ID
    override val name = "History catalogue acceptance"

    companion object {
        const val SOURCE_ID = 9_876_543_210L
        const val MANGA_URL = "/manga/history-catalog"
        const val MANGA_TITLE = "History catalogue acceptance"
    }
}

internal object HistoryCatalogTestSourceBridge {
    private val source = AtomicReference<HistoryCatalogTestSource?>()
    fun install(value: HistoryCatalogTestSource) = source.set(value)
    fun sources(): List<CatalogueSource> = listOfNotNull(source.get())
    fun clear(value: HistoryCatalogTestSource) = source.compareAndSet(value, null)
}
