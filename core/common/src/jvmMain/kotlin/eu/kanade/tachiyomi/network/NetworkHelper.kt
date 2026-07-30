package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * Desktop JVM stub for NetworkHelper.
 * Provides the OkHttpClient and user agent needed by HttpSource.
 */
class NetworkHelper(
    val client: OkHttpClient,
    private val sourceClientProvider: (Long) -> OkHttpClient = { client },
) {
    fun clientForSource(sourceId: Long): OkHttpClient = sourceClientProvider(sourceId)

    fun defaultUserAgentProvider(): String = "Mihon Desktop/1.0"
}
