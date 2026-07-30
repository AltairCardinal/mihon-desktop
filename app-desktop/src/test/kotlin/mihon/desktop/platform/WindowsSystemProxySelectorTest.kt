package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class WindowsSystemProxySelectorTest {

    @Test
    fun `uniform Windows proxy applies to HTTP and HTTPS destinations`() {
        val selector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "127.0.0.1:10808",
                    proxyOverride = "",
                )
            },
        )

        listOf("http://example.org", "https://raw.githubusercontent.com").forEach { target ->
            val proxy = selector.select(URI(target)).single()
            assertEquals(Proxy.Type.HTTP, proxy.type())
            assertEquals(InetSocketAddress("127.0.0.1", 10808), proxy.address())
        }
    }

    @Test
    fun `protocol map selects HTTP and SOCKS entries`() {
        val selector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "http=127.0.0.1:8080;https=127.0.0.1:8443;socks=127.0.0.1:1080",
                    proxyOverride = "",
                )
            },
        )

        assertEquals(
            InetSocketAddress("127.0.0.1", 8443),
            selector.select(URI("https://example.org")).single().address(),
        )
        assertEquals(
            Proxy.Type.SOCKS,
            selector.select(URI("ftp://example.org")).single().type(),
        )
    }

    @Test
    fun `Windows bypass patterns keep local and private destinations direct`() {
        val selector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy(Proxy.Type.HTTP, InetSocketAddress("fallback", 9000))),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "127.0.0.1:10808",
                    proxyOverride = "<local>;localhost;127.*;10.*;*.lan",
                )
            },
        )

        listOf(
            "http://printer",
            "http://localhost",
            "http://127.0.0.1",
            "http://10.2.3.4",
            "http://reader.lan",
        ).forEach { target ->
            assertEquals(Proxy.NO_PROXY, selector.select(URI(target)).single(), target)
        }
        assertEquals(
            Proxy.Type.HTTP,
            selector.select(URI("https://raw.githubusercontent.com")).single().type(),
        )
    }

    @Test
    fun `disabled or malformed Windows settings fall back without silently inventing a route`() {
        val fallbackProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("fallback", 9000))
        listOf(
            WindowsUserProxySettings(false, "127.0.0.1:10808", ""),
            WindowsUserProxySettings(true, "not-a-proxy", ""),
        ).forEach { settings ->
            val selector = WindowsSystemProxySelector(
                fallback = FixedSelector(fallbackProxy),
                settingsProvider = { settings },
            )

            assertEquals(fallbackProxy, selector.select(URI("https://example.org")).single())
        }
    }

    private class FixedSelector(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }
}
