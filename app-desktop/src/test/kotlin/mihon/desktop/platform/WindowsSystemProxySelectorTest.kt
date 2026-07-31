package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
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
            socks5CapabilityProbe = { false },
        )

        listOf("http://example.org", "https://raw.githubusercontent.com").forEach { target ->
            val proxy = selector.select(URI(target)).single()
            assertEquals(Proxy.Type.HTTP, proxy.type())
            assertEquals(InetSocketAddress("127.0.0.1", 10808), proxy.address())
        }
    }

    @Test
    fun `ambiguous loopback mixed proxy prefers SOCKS and caches the capability result`() {
        var probeCount = 0
        val selector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "127.0.0.1:10808",
                    proxyOverride = "",
                )
            },
            socks5CapabilityProbe = {
                probeCount += 1
                true
            },
        )

        listOf("http://example.org", "https://raw.githubusercontent.com").forEach { target ->
            val proxy = selector.select(URI(target)).single()
            assertEquals(Proxy.Type.SOCKS, proxy.type())
            assertEquals(InetSocketAddress("127.0.0.1", 10808), proxy.address())
        }
        assertEquals(1, probeCount)
    }

    @Test
    fun `explicit proxy protocol is honored without mixed endpoint probing`() {
        var probeCount = 0
        val httpSelector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "http://127.0.0.1:10808",
                    proxyOverride = "",
                )
            },
            socks5CapabilityProbe = {
                probeCount += 1
                true
            },
        )
        val socksSelector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "socks5://127.0.0.1:10808",
                    proxyOverride = "",
                )
            },
            socks5CapabilityProbe = {
                probeCount += 1
                false
            },
        )

        assertEquals(Proxy.Type.HTTP, httpSelector.select(URI("https://example.org")).single().type())
        assertEquals(Proxy.Type.SOCKS, socksSelector.select(URI("https://example.org")).single().type())
        assertEquals(0, probeCount)
    }

    @Test
    fun `remote ambiguous proxy remains HTTP without local capability probing`() {
        var probeCount = 0
        val selector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "proxy.example.org:8080",
                    proxyOverride = "",
                )
            },
            socks5CapabilityProbe = {
                probeCount += 1
                true
            },
        )

        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.org")).single().type())
        assertEquals(0, probeCount)
    }

    @Test
    fun `proxy connection failure invalidates a cached local capability result`() {
        var probeCount = 0
        var supportsSocks5 = false
        val proxyAddress = InetSocketAddress("127.0.0.1", 10808)
        val target = URI("https://example.org")
        val selector = WindowsSystemProxySelector(
            fallback = FixedSelector(Proxy.NO_PROXY),
            settingsProvider = {
                WindowsUserProxySettings(
                    enabled = true,
                    proxyServer = "127.0.0.1:10808",
                    proxyOverride = "",
                )
            },
            socks5CapabilityProbe = {
                probeCount += 1
                supportsSocks5
            },
        )

        assertEquals(Proxy.Type.HTTP, selector.select(target).single().type())
        supportsSocks5 = true
        assertEquals(Proxy.Type.HTTP, selector.select(target).single().type())
        assertEquals(1, probeCount)

        selector.connectFailed(target, proxyAddress, IOException("proxy restarted"))

        assertEquals(Proxy.Type.SOCKS, selector.select(target).single().type())
        assertEquals(2, probeCount)
    }

    @Test
    fun `SOCKS5 capability probe recognizes a no-auth local endpoint`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            var receivedGreeting = byteArrayOf()
            val responder = Thread {
                server.accept().use { socket ->
                    receivedGreeting = socket.getInputStream().readNBytes(3)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x05, 0x00))
                        flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            assertTrue(
                probeSocks5Capability(
                    InetSocketAddress(loopback, server.localPort),
                    timeoutMillis = 1_000,
                ),
            )
            responder.join(2_000)
            assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), receivedGreeting)
        }
    }

    @Test
    fun `SOCKS5 capability probe rejects an authentication method it did not advertise`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            val responder = Thread {
                server.accept().use { socket ->
                    socket.getInputStream().readNBytes(3)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x05, 0x02))
                        flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            assertFalse(
                probeSocks5Capability(
                    InetSocketAddress(loopback, server.localPort),
                    timeoutMillis = 1_000,
                ),
            )
            responder.join(2_000)
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
            socks5CapabilityProbe = { false },
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
