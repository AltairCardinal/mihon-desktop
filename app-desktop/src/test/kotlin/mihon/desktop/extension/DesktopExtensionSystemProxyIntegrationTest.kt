package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.WindowsSystemProxySelector
import mihon.desktop.platform.WindowsUserProxySettings
import mihon.desktop.settings.GlobalNetworkMode
import mihon.domain.extension.service.ExtensionCatalogService
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DesktopExtensionSystemProxyIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extension catalog uses detected SOCKS transport for a Windows mixed proxy`() = runBlocking {
        TestSocks5Proxy().use { proxy ->
            MockWebServer().also { it.start() }.use { repositoryServer ->
                val baseUrl = repositoryServer.url("/").toString().removeSuffix("/")
                repositoryServer.enqueue(
                    MockResponse(
                        body = """{"meta":{"name":"repo","shortName":"R","website":"$baseUrl","signingKeyFingerprint":"repo-fingerprint"}}""",
                    ),
                )
                repositoryServer.enqueue(MockResponse(body = INDEX_JSON))
                val selector = WindowsSystemProxySelector(
                    fallback = FixedProxySelector(Proxy.NO_PROXY),
                    settingsProvider = {
                        WindowsUserProxySettings(
                            enabled = true,
                            proxyServer = "127.0.0.1:${proxy.port}",
                            proxyOverride = "",
                        )
                    },
                )
                val helper = DesktopNetworkHelper(
                    cacheDir = tempDir.resolve("network-cache").toFile(),
                    globalMode = GlobalNetworkMode.SYSTEM,
                    systemProxySelector = selector,
                )
                try {
                    val repositories = FakeExtensionRepoRepository().also {
                        it.insertRepo(baseUrl, "repo", "R", baseUrl, "repo-fingerprint")
                    }
                    val api = DesktopExtensionApi(
                        client = helper.client,
                        json = Json { ignoreUnknownKeys = true },
                        extensionRepoRepository = repositories,
                        catalogService = ExtensionCatalogService(),
                    )

                    val catalog = api.refreshCatalog()

                    assertEquals(
                        listOf("eu.kanade.tachiyomi.extension.en.example"),
                        catalog.entries.map { it.artifact.packageName },
                    )
                    assertTrue(catalog.failures.isEmpty())
                    assertTrue(proxy.awaitCapabilityProbe())
                    assertEquals(1, proxy.capabilityProbeCount.get())
                    assertTrue(proxy.tunnelCount.get() >= 1)
                    assertTrue(
                        helper.routeObservations.value.any {
                            it.proxyType == Proxy.Type.SOCKS &&
                                it.proxyAddress.orEmpty().contains(proxy.port.toString())
                        },
                    )
                } finally {
                    helper.close()
                }
            }
        }
    }

    private class FixedProxySelector(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }

    private class TestSocks5Proxy : AutoCloseable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val server = ServerSocket(0, 16, loopback)
        private val running = AtomicBoolean(true)
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val capabilityProbed = CountDownLatch(1)

        val port: Int = server.localPort
        val capabilityProbeCount = AtomicInteger()
        val tunnelCount = AtomicInteger()

        fun awaitCapabilityProbe(): Boolean = capabilityProbed.await(2, TimeUnit.SECONDS)

        init {
            executor.execute {
                while (running.get()) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += socket
                    executor.execute { handle(socket) }
                }
            }
        }

        private fun handle(client: Socket) {
            try {
                client.use {
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    require(input.readRequired() == SocksVersion)
                    val methodCount = input.readRequired()
                    input.readExactly(methodCount)
                    output.write(byteArrayOf(SocksVersion.toByte(), NoAuthentication.toByte()))
                    output.flush()

                    val requestVersion = input.read()
                    if (requestVersion < 0) {
                        capabilityProbeCount.incrementAndGet()
                        capabilityProbed.countDown()
                        return
                    }
                    require(requestVersion == SocksVersion)
                    require(input.readRequired() == ConnectCommand)
                    input.readRequired()
                    val targetHost = when (val addressType = input.readRequired()) {
                        Ipv4Address -> InetAddress.getByAddress(input.readExactly(4)).hostAddress
                        DomainAddress -> input.readExactly(input.readRequired()).toString(Charsets.UTF_8)
                        Ipv6Address -> InetAddress.getByAddress(input.readExactly(16)).hostAddress
                        else -> error("Unsupported SOCKS address type: $addressType")
                    }
                    val targetPort = (input.readRequired() shl 8) or input.readRequired()

                    Socket().use { upstream ->
                        upstream.connect(InetSocketAddress(targetHost, targetPort), ConnectTimeoutMillis)
                        output.write(
                            byteArrayOf(
                                SocksVersion.toByte(),
                                RequestSucceeded.toByte(),
                                0x00,
                                Ipv4Address.toByte(),
                                127,
                                0,
                                0,
                                1,
                                0,
                                0,
                            ),
                        )
                        output.flush()
                        tunnelCount.incrementAndGet()

                        val upload = executor.submit {
                            runCatching {
                                input.copyTo(upstream.getOutputStream())
                                upstream.shutdownOutput()
                            }
                        }
                        upstream.getInputStream().copyTo(output)
                        runCatching { upload.get(2, TimeUnit.SECONDS) }
                    }
                }
            } finally {
                sockets -= client
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val SocksVersion = 0x05
        const val NoAuthentication = 0x00
        const val ConnectCommand = 0x01
        const val RequestSucceeded = 0x00
        const val Ipv4Address = 0x01
        const val DomainAddress = 0x03
        const val Ipv6Address = 0x04
        const val ConnectTimeoutMillis = 2_000
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example.apk","lang":"en","code":42,"version":"1.4.7","nsfw":0,"sha256":"0123456789abcdef","sources":[{"id":7,"lang":"en","name":"Example Source","baseUrl":"https://source.example"}]}]"""
    }
}

private fun InputStream.readRequired(): Int = read().takeIf { it >= 0 } ?: throw EOFException()

private fun InputStream.readExactly(byteCount: Int): ByteArray =
    readNBytes(byteCount).also { if (it.size != byteCount) throw EOFException() }
