package mihon.desktop.platform

import eu.kanade.tachiyomi.network.DesktopCookieJar
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopCloudflareInterceptor
import mihon.desktop.network.DesktopCloudflareCredentialInterceptor
import mihon.desktop.settings.DohProvider
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopNetworkHelper(
    cacheDir: File = DesktopPlatformPaths.current().networkCacheDir,
    cookieStorageFile: File = DesktopPlatformPaths.current().cookiesFile,
    dohProvider: DohProvider = DohProvider.OFF,
    challengeManager: CloudflareChallengeManager? = null,
) : AutoCloseable {

    val cookieJar = DesktopCookieJar(
        storageFile = cookieStorageFile,
    )

    /** Base client without DoH — used to bootstrap DnsOverHttps. */
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .cache(
            Cache(
                directory = cacheDir.also { it.mkdirs() },
                maxSize = 5L * 1024 * 1024, // 5 MiB
            ),
        )
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        .apply {
            challengeManager?.let {
                addInterceptor(DesktopCloudflareInterceptor(it))
                addNetworkInterceptor(DesktopCloudflareCredentialInterceptor(it))
            }
        }
        .addNetworkInterceptor(IgnoreGzipInterceptor())
        .addNetworkInterceptor(BrotliInterceptor)
        .build()

    val client: OkHttpClient = if (dohProvider == DohProvider.OFF) {
        baseClient
    } else {
        val dohUrl = when (dohProvider) {
            DohProvider.GOOGLE -> "https://dns.google/dns-query"
            DohProvider.CLOUDFLARE -> "https://cloudflare-dns.com/dns-query"
            DohProvider.ADGUARD -> "https://dns.adguard.com/dns-query"
            DohProvider.OFF -> error("unreachable")
        }
        val dns = DnsOverHttps.Builder()
            .client(baseClient)
            .url(dohUrl.toHttpUrl())
            .includeIPv6(false)
            .build()
        baseClient.newBuilder().dns(dns).build()
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
        if (client !== baseClient) {
            baseClient.dispatcher.cancelAll()
            baseClient.dispatcher.executorService.shutdown()
            baseClient.connectionPool.evictAll()
            baseClient.cache?.close()
        }
    }

    private fun defaultUserAgentProvider(): String = "Mihon Desktop/1.0"
}
