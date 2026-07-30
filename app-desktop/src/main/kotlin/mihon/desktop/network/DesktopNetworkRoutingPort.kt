package mihon.desktop.network

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.StateFlow
import mihon.desktop.settings.DesktopProxyRuntimeConfig
import mihon.desktop.settings.GlobalNetworkMode
import java.net.Proxy

data class DesktopRouteObservation(
    val scope: String,
    val host: String,
    val proxyType: Proxy.Type,
    val proxyAddress: String?,
    val observedAtMillis: Long,
)

enum class DesktopPluginNetworkSupport { FULL, PARTIAL, UNKNOWN }

data class DesktopConnectionTestResult(
    val host: String,
    val statusCode: Int?,
    val route: DesktopRouteObservation?,
    val error: String?,
) {
    val successful: Boolean get() = statusCode in 200..399 && error == null
}

interface DesktopNetworkRoutingPort {
    val routeObservations: StateFlow<List<DesktopRouteObservation>>
    val activeGlobalMode: GlobalNetworkMode
    val activeGlobalProxy: DesktopProxyRuntimeConfig?

    fun pluginNetworkSupport(sources: List<Source>): DesktopPluginNetworkSupport

    fun pluginEffectiveRoute(packageName: String): String

    suspend fun testConnection(url: String, sourceId: Long? = null): DesktopConnectionTestResult
}
