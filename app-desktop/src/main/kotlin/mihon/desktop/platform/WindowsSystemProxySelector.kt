package mihon.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal data class WindowsUserProxySettings(
    val enabled: Boolean,
    val proxyServer: String,
    val proxyOverride: String,
)

internal class WindowsSystemProxySelector(
    private val fallback: ProxySelector,
    private val settingsProvider: () -> WindowsUserProxySettings? = ::readWindowsUserProxySettings,
    private val socks5CapabilityProbe: (InetSocketAddress) -> Boolean = ::probeSocks5Capability,
) : ProxySelector() {
    private val socks5Capabilities = ConcurrentHashMap<InetSocketAddress, Boolean>()

    override fun select(uri: URI): List<Proxy> {
        val settings = runCatching(settingsProvider).getOrNull() ?: return fallback.select(uri)
        if (!settings.enabled) return fallback.select(uri)
        if (settings.bypasses(uri.host.orEmpty())) return listOf(Proxy.NO_PROXY)
        val endpoint = settings.proxyEndpointFor(uri) ?: return fallback.select(uri)
        val type = if (endpoint.shouldProbeForSocks5() && endpoint.address.isLoopbackEndpoint()) {
            val supportsSocks5 = socks5Capabilities.computeIfAbsent(endpoint.address) {
                runCatching { socks5CapabilityProbe(it) }.getOrDefault(false)
            }
            if (supportsSocks5) Proxy.Type.SOCKS else Proxy.Type.HTTP
        } else {
            endpoint.type
        }
        return listOf(Proxy(type, endpoint.address))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        if (sa is InetSocketAddress) {
            socks5Capabilities.remove(sa)
        }
        fallback.connectFailed(uri, sa, ioe)
    }
}

internal fun probeSocks5Capability(
    address: InetSocketAddress,
    timeoutMillis: Int = Socks5ProbeTimeoutMillis,
): Boolean = runCatching {
    Socket().use { socket ->
        socket.connect(address, timeoutMillis)
        socket.soTimeout = timeoutMillis
        socket.getOutputStream().apply {
            write(Socks5NoAuthenticationGreeting)
            flush()
        }
        val input = socket.getInputStream()
        val version = input.read()
        val method = input.read()
        version == Socks5Version && method == Socks5NoAuthenticationMethod
    }
}.getOrDefault(false)

private fun readWindowsUserProxySettings(): WindowsUserProxySettings? = runCatching {
    val enabled = Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        InternetSettingsRegistryPath,
        "ProxyEnable",
    ) != 0
    WindowsUserProxySettings(
        enabled = enabled,
        proxyServer = registryString("ProxyServer"),
        proxyOverride = registryString("ProxyOverride"),
    )
}.getOrNull()

private fun registryString(name: String): String =
    runCatching {
        Advapi32Util.registryGetStringValue(
            WinReg.HKEY_CURRENT_USER,
            InternetSettingsRegistryPath,
            name,
        )
    }.getOrDefault("")

private fun WindowsUserProxySettings.proxyEndpointFor(uri: URI): WindowsProxyEndpoint? {
    val specification = proxyServer.trim()
    if (specification.isEmpty()) return null
    if ('=' !in specification) {
        return specification.toProxyEndpoint(
            defaultType = Proxy.Type.HTTP,
            protocolExplicit = false,
        )
    }

    val entries = specification
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            entry.substring(0, separator).trim().lowercase() to entry.substring(separator + 1).trim()
        }
        .toMap()
    entries[uri.scheme?.lowercase()]?.toProxyEndpoint(
        defaultType = Proxy.Type.HTTP,
        protocolExplicit = true,
    )?.let { return it }
    return entries["socks"]?.toProxyEndpoint(
        defaultType = Proxy.Type.SOCKS,
        protocolExplicit = true,
    )
}

private fun String.toProxyEndpoint(
    defaultType: Proxy.Type,
    protocolExplicit: Boolean,
): WindowsProxyEndpoint? {
    val specification = trim()
    val lowerSpecification = specification.lowercase()
    val (type, explicit, value) = when {
        lowerSpecification.startsWith("http://") ->
            Triple(Proxy.Type.HTTP, true, specification.substring(HttpSchemePrefix.length))
        lowerSpecification.startsWith("https://") ->
            Triple(Proxy.Type.HTTP, true, specification.substring(HttpsSchemePrefix.length))
        lowerSpecification.startsWith("socks5://") ->
            Triple(Proxy.Type.SOCKS, true, specification.substring(Socks5SchemePrefix.length))
        lowerSpecification.startsWith("socks://") ->
            Triple(Proxy.Type.SOCKS, true, specification.substring(SocksSchemePrefix.length))
        else -> Triple(defaultType, protocolExplicit, specification)
    }
    val separator = value.lastIndexOf(':')
    if (separator <= 0 || separator == value.lastIndex) return null
    val host = value.substring(0, separator).removeSurrounding("[", "]").takeIf(String::isNotBlank) ?: return null
    val port = value.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return WindowsProxyEndpoint(
        address = InetSocketAddress(host, port),
        type = type,
        protocolExplicit = explicit,
    )
}

private data class WindowsProxyEndpoint(
    val address: InetSocketAddress,
    val type: Proxy.Type,
    val protocolExplicit: Boolean,
) {
    fun shouldProbeForSocks5(): Boolean = type == Proxy.Type.HTTP && !protocolExplicit
}

private fun InetSocketAddress.isLoopbackEndpoint(): Boolean =
    address?.isLoopbackAddress == true || hostString.equals("localhost", ignoreCase = true)

private fun WindowsUserProxySettings.bypasses(host: String): Boolean {
    if (host.isBlank()) return false
    return proxyOverride.split(';').any { rawPattern ->
        val pattern = rawPattern.trim()
        when {
            pattern.isEmpty() -> false
            pattern.equals("<local>", ignoreCase = true) -> '.' !in host
            else -> pattern
                .split('*')
                .joinToString(".*") { Regex.escape(it) }
                .let { Regex("^$it$", RegexOption.IGNORE_CASE).matches(host) }
        }
    }
}

private const val InternetSettingsRegistryPath =
    "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
private const val HttpSchemePrefix = "http://"
private const val HttpsSchemePrefix = "https://"
private const val SocksSchemePrefix = "socks://"
private const val Socks5SchemePrefix = "socks5://"
private const val Socks5ProbeTimeoutMillis = 500
private const val Socks5Version = 0x05
private const val Socks5NoAuthenticationMethod = 0x00
private val Socks5NoAuthenticationGreeting =
    byteArrayOf(Socks5Version.toByte(), 0x01, Socks5NoAuthenticationMethod.toByte())
