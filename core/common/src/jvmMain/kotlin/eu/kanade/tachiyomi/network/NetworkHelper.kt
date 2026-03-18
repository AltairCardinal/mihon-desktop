package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * Desktop JVM stub for NetworkHelper.
 * Provides the OkHttpClient and user agent needed by HttpSource.
 */
class NetworkHelper(val client: OkHttpClient) {
    fun defaultUserAgentProvider(): String = "Mihon Desktop/1.0"
}
