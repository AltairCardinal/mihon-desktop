package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * Desktop JVM stub for NetworkHelper.
 * Provides the OkHttpClient and user agent needed by HttpSource.
 */
class NetworkHelper(
    client: OkHttpClient,
    private val extensionClientProvider: () -> OkHttpClient = { client },
    private val sourceClientProvider: (Long) -> OkHttpClient = { client },
) {
    /**
     * The client exposed by the extensions API.
     *
     * Desktop resolves this getter against the calling extension classloader so extensions that
     * derive a client with `network.client.newBuilder()` keep their package-scoped network policy.
     * Host calls continue to receive the global client.
     */
    val client: OkHttpClient
        get() = extensionClientProvider()

    fun clientForSource(sourceId: Long): OkHttpClient = sourceClientProvider(sourceId)

    fun defaultUserAgentProvider(): String = "Mihon Desktop/1.0"
}
