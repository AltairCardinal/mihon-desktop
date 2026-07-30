package mihon.desktop.platform

import eu.kanade.tachiyomi.network.DesktopCookieJar
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopCloudflareInterceptor
import mihon.desktop.network.DesktopCloudflareCredentialInterceptor
import mihon.desktop.network.DesktopExtensionCookiePort
import mihon.desktop.network.DesktopCloudflareCookieImportResult
import mihon.desktop.network.DesktopNetworkMaintenancePort
import mihon.desktop.network.DesktopNetworkRoutingPort
import mihon.desktop.network.DesktopConnectionTestResult
import mihon.desktop.network.DesktopPluginNetworkSupport
import mihon.desktop.network.DesktopRouteObservation
import mihon.desktop.network.CF_CLEARANCE_COOKIE_NAME
import mihon.desktop.network.CookieImportResult
import mihon.desktop.network.validateCloudflareCookieInput
import mihon.desktop.settings.DohProvider
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.DesktopProxyRuntimeConfig
import mihon.desktop.settings.GlobalNetworkMode
import mihon.desktop.settings.PluginNetworkMode
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.InterruptedIOException
import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DesktopNetworkHelper(
    cacheDir: File = DesktopPlatformPaths.current().networkCacheDir,
    cookieStorageFile: File = DesktopPlatformPaths.current().cookiesFile,
    dohProvider: DohProvider = DohProvider.OFF,
    proxyConfig: DesktopProxyRuntimeConfig? = null,
    globalMode: GlobalNetworkMode = if (proxyConfig == null) GlobalNetworkMode.SYSTEM else GlobalNetworkMode.MANUAL,
    private val systemProxySelector: ProxySelector = desktopSystemProxySelector(),
    private val appPreferences: DesktopAppPreferences? = null,
    private val connectionTestTimeoutMillis: Long = 15_000,
    challengeManager: CloudflareChallengeManager? = null,
) : AutoCloseable, DesktopExtensionCookiePort, DesktopNetworkMaintenancePort, DesktopNetworkRoutingPort {
    private val routeMonitor = DesktopRouteMonitor()
    private val pluginClients = ConcurrentHashMap<PluginClientKey, OkHttpClient>()
    @Volatile
    private var sourceOwner: (Long) -> String? = { null }

    override val routeObservations: StateFlow<List<DesktopRouteObservation>> = routeMonitor.observations
    override val activeGlobalMode: GlobalNetworkMode = globalMode
    override val activeGlobalProxy: DesktopProxyRuntimeConfig? = proxyConfig

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
        .eventListenerFactory { routeMonitor.listener(GlobalScope) }
        .apply {
            when (globalMode) {
                GlobalNetworkMode.SYSTEM -> proxySelector(systemProxySelector)
                GlobalNetworkMode.DIRECT -> proxy(Proxy.NO_PROXY)
                GlobalNetworkMode.MANUAL -> {
                    val effectiveProxy = proxyConfig?.let {
                        Proxy(it.type, InetSocketAddress(it.host, it.port))
                    } ?: InvalidManualProxy
                    proxy(effectiveProxy)
                }
            }
            challengeManager?.let {
                addInterceptor(DesktopCloudflareInterceptor(it))
                addNetworkInterceptor(DesktopCloudflareCredentialInterceptor(it))
            }
        }
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

    fun bindSourceOwner(owner: (Long) -> String?) {
        sourceOwner = owner
        pluginClients.clear()
    }

    fun clientForSource(sourceId: Long): OkHttpClient {
        val packageName = sourceOwner(sourceId) ?: return client
        val preferences = appPreferences ?: return client
        val mode = preferences.pluginNetworkMode(packageName).get()
        val manualProxy = preferences.pluginProxyRuntimeConfig(packageName)
        val key = PluginClientKey(packageName, mode, manualProxy)
        return pluginClients.computeIfAbsent(key) {
            client.newBuilder()
                .applyPluginProxy(mode, manualProxy, systemProxySelector)
                .addNetworkInterceptor(PluginDomainObservationInterceptor(packageName, preferences))
                .eventListenerFactory { routeMonitor.listener(packageName) }
                .build()
        }
    }

    override fun pluginNetworkSupport(sources: List<Source>): DesktopPluginNetworkSupport {
        val httpSources = sources.filterIsInstance<HttpSource>()
        if (httpSources.isEmpty()) return DesktopPluginNetworkSupport.UNKNOWN
        val managed = httpSources.count { source ->
            runCatching { source.client === clientForSource(source.id) }.getOrDefault(false)
        }
        return when {
            managed == httpSources.size -> DesktopPluginNetworkSupport.FULL
            managed > 0 -> DesktopPluginNetworkSupport.PARTIAL
            else -> DesktopPluginNetworkSupport.UNKNOWN
        }
    }

    override fun pluginEffectiveRoute(packageName: String): String {
        routeObservations.value.lastOrNull { it.scope == packageName }?.let { route ->
            return "${route.proxyType.name}${route.proxyAddress?.let { " $it" }.orEmpty()}"
        }
        val preferences = appPreferences ?: return activeGlobalRouteLabel()
        return when (preferences.pluginNetworkMode(packageName).get()) {
            PluginNetworkMode.INHERIT_GLOBAL -> activeGlobalRouteLabel()
            PluginNetworkMode.SYSTEM -> "SYSTEM (per destination)"
            PluginNetworkMode.DIRECT -> "DIRECT"
            PluginNetworkMode.MANUAL -> preferences.pluginProxyRuntimeConfig(packageName)?.let {
                "${it.type.name} ${it.host}:${it.port}"
            } ?: "INVALID MANUAL PROXY"
        }
    }

    override suspend fun testConnection(url: String, sourceId: Long?): DesktopConnectionTestResult =
        withContext(Dispatchers.IO) {
            val parsed = url.toHttpUrlOrNull()
                ?: return@withContext DesktopConnectionTestResult("", null, null, "Invalid URL")
            val scope = sourceId?.let(sourceOwner) ?: GlobalScope
            val applicationClient = sourceId?.let(::clientForSource) ?: client
            val request = okhttp3.Request.Builder().url(parsed).head().build()
            val firstAttempt = applicationClient
                .newBuilder()
                .callTimeout(connectionTestTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            try {
                firstAttempt.newCall(request).execute().use { response ->
                    return@withContext response.toConnectionTestResult(parsed.host, scope)
                }
            } catch (error: Exception) {
                if (!error.isTransientConnectionTimeout()) {
                    return@withContext failedConnectionTestResult(parsed.host, scope, error)
                }
            }

            val isolatedDispatcher = Dispatcher()
            val isolatedPool = ConnectionPool()
            val retryClient = applicationClient.newBuilder()
                .dispatcher(isolatedDispatcher)
                .connectionPool(isolatedPool)
                .callTimeout(connectionTestTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            try {
                retryClient.newCall(request).execute().use { response ->
                    response.toConnectionTestResult(parsed.host, scope)
                }
            } catch (error: Exception) {
                failedConnectionTestResult(parsed.host, scope, error)
            } finally {
                isolatedPool.evictAll()
                isolatedDispatcher.executorService.shutdown()
            }
        }

    private fun okhttp3.Response.toConnectionTestResult(host: String, scope: String) =
        DesktopConnectionTestResult(
            host = host,
            statusCode = code,
            route = routeObservations.value.lastOrNull { it.scope == scope && it.host == host },
            error = null,
        )

    private fun failedConnectionTestResult(host: String, scope: String, error: Exception) =
        DesktopConnectionTestResult(
            host = host,
            statusCode = null,
            route = routeObservations.value.lastOrNull { it.scope == scope && it.host == host },
            error = error.message ?: error.javaClass.simpleName,
        )

    override fun clearCookies(sources: List<Source>): Int {
        val domains = sources
            .filterIsInstance<HttpSource>()
            .mapNotNull { runCatching { URI(it.baseUrl).host }.getOrNull() }
            .toSet()
        return cookieJar.clearDomains(domains)
    }

    override fun importCloudflareCookie(
        domain: String,
        value: String,
    ): DesktopCloudflareCookieImportResult = when (val validated = validateCloudflareCookieInput(domain, value)) {
        CookieImportResult.InvalidDomain -> DesktopCloudflareCookieImportResult.InvalidDomain
        CookieImportResult.InvalidValue -> DesktopCloudflareCookieImportResult.InvalidValue
        is CookieImportResult.Valid -> {
            val url = "https://${validated.domain}".toHttpUrlOrNull()
                ?: return DesktopCloudflareCookieImportResult.DomainParseFailed
            cookieJar.addManual(url, CF_CLEARANCE_COOKIE_NAME, validated.value)
            DesktopCloudflareCookieImportResult.Imported(url.host)
        }
    }

    override fun clearCookies() = cookieJar.clear()

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

    private fun activeGlobalRouteLabel(): String = when (activeGlobalMode) {
        GlobalNetworkMode.SYSTEM -> "SYSTEM (per destination)"
        GlobalNetworkMode.DIRECT -> "DIRECT"
        GlobalNetworkMode.MANUAL -> activeGlobalProxy?.let { "${it.type.name} ${it.host}:${it.port}" }
            ?: "INVALID MANUAL PROXY"
    }

    private fun OkHttpClient.Builder.applyPluginProxy(
        mode: PluginNetworkMode,
        manualProxy: DesktopProxyRuntimeConfig?,
        systemProxySelector: ProxySelector,
    ): OkHttpClient.Builder = apply {
        when (mode) {
            PluginNetworkMode.INHERIT_GLOBAL -> Unit
            PluginNetworkMode.SYSTEM -> {
                proxy(null)
                proxySelector(systemProxySelector)
            }
            PluginNetworkMode.DIRECT -> proxy(Proxy.NO_PROXY)
            PluginNetworkMode.MANUAL -> proxy(
                manualProxy?.let { Proxy(it.type, InetSocketAddress(it.host, it.port)) }
                    ?: InvalidManualProxy,
            )
        }
    }

    private data class PluginClientKey(
        val packageName: String,
        val mode: PluginNetworkMode,
        val manualProxy: DesktopProxyRuntimeConfig?,
    )

    private class PluginDomainObservationInterceptor(
        private val packageName: String,
        private val preferences: DesktopAppPreferences,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val host = chain.request().url.host
            val domainPreference = preferences.pluginObservedDomains(packageName)
            synchronized(domainPreference) {
                val current = domainPreference.get()
                if (host !in current) {
                    domainPreference.set((current + host).sorted().take(MaxObservedDomains).toSet())
                }
            }
            return chain.proceed(chain.request())
        }
    }

    private companion object {
        const val GlobalScope = "mihon-global"
        const val MaxObservedDomains = 256

        val InvalidManualProxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved("invalid-manual-proxy.mihon.invalid", 9),
        )

    }
}

private fun Exception.isTransientConnectionTimeout(): Boolean =
    this is SocketTimeoutException ||
        (this is InterruptedIOException && message.orEmpty().contains("timeout", ignoreCase = true))

internal fun desktopSystemProxySelector(): ProxySelector {
    System.setProperty("java.net.useSystemProxies", "true")
    val fallback = ProxySelector.getDefault() ?: object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
    return if (OperatingSystem.detect() == OperatingSystem.WINDOWS) {
        WindowsSystemProxySelector(fallback)
    } else {
        fallback
    }
}

private class DesktopRouteMonitor {
    private val mutableObservations = MutableStateFlow<List<DesktopRouteObservation>>(emptyList())
    val observations: StateFlow<List<DesktopRouteObservation>> = mutableObservations.asStateFlow()

    fun listener(scope: String): EventListener = object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
            val route = connection.route()
            val observation = DesktopRouteObservation(
                scope = scope,
                host = route.address.url.host,
                proxyType = route.proxy.type(),
                proxyAddress = route.proxy.address()?.toString(),
                observedAtMillis = System.currentTimeMillis(),
            )
            synchronized(mutableObservations) {
                mutableObservations.value = (mutableObservations.value + observation).takeLast(MaxRouteObservations)
            }
        }
    }

    private companion object {
        const val MaxRouteObservations = 100
    }
}
