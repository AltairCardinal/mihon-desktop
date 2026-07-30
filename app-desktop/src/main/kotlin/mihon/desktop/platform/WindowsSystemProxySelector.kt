package mihon.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal data class WindowsUserProxySettings(
    val enabled: Boolean,
    val proxyServer: String,
    val proxyOverride: String,
)

internal class WindowsSystemProxySelector(
    private val fallback: ProxySelector,
    private val settingsProvider: () -> WindowsUserProxySettings? = ::readWindowsUserProxySettings,
) : ProxySelector() {

    override fun select(uri: URI): List<Proxy> {
        val settings = runCatching(settingsProvider).getOrNull() ?: return fallback.select(uri)
        if (!settings.enabled) return fallback.select(uri)
        if (settings.bypasses(uri.host.orEmpty())) return listOf(Proxy.NO_PROXY)
        return settings.proxyFor(uri)?.let(::listOf) ?: fallback.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        fallback.connectFailed(uri, sa, ioe)
    }
}

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

private fun WindowsUserProxySettings.proxyFor(uri: URI): Proxy? {
    val specification = proxyServer.trim()
    if (specification.isEmpty()) return null
    if ('=' !in specification) return specification.toProxy(Proxy.Type.HTTP)

    val entries = specification
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            entry.substring(0, separator).trim().lowercase() to entry.substring(separator + 1).trim()
        }
        .toMap()
    entries[uri.scheme?.lowercase()]?.toProxy(Proxy.Type.HTTP)?.let { return it }
    return entries["socks"]?.toProxy(Proxy.Type.SOCKS)
}

private fun String.toProxy(type: Proxy.Type): Proxy? {
    val value = trim().removePrefix("http://").removePrefix("https://").removePrefix("socks://")
    val separator = value.lastIndexOf(':')
    if (separator <= 0 || separator == value.lastIndex) return null
    val host = value.substring(0, separator).removeSurrounding("[", "]").takeIf(String::isNotBlank) ?: return null
    val port = value.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return Proxy(type, InetSocketAddress(host, port))
}

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
