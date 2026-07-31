package mihon.desktop.extension

import eu.kanade.tachiyomi.network.NetworkHelper
import mihon.desktop.network.DesktopPluginNetworkSupport
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.GlobalNetworkMode
import mihon.desktop.settings.PluginNetworkMode
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.prefs.Preferences
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

object ExtensionNetworkTestBridge {
    lateinit var networkHelper: NetworkHelper
}

class DesktopExtensionNetworkRoutingIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `extension derived client remains centrally routed observed and dynamically configurable`() {
        val packageName = "pkg.scoped"
        val preferenceNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = DesktopAppPreferences(DesktopPreferenceStore(preferenceNode)).also {
            it.pluginNetworkMode(packageName).set(PluginNetworkMode.MANUAL)
            it.pluginProxyUrl(packageName).set("socks5://127.0.0.1:17890")
        }
        val networkContext = DesktopExtensionNetworkContext()
        val desktopNetwork = DesktopNetworkHelper(
            cacheDir = File(tempDir, "cache"),
            globalMode = GlobalNetworkMode.DIRECT,
            appPreferences = preferences,
            extensionNetworkContext = networkContext,
        )
        ExtensionNetworkTestBridge.networkHelper = NetworkHelper(
            client = desktopNetwork.client,
            sourceClientProvider = desktopNetwork::clientForSource,
            extensionClientProvider = desktopNetwork::clientForCurrentExtension,
        )
        createFixtureJar(File(tempDir, "$packageName.jar"))

        val loaded = DesktopExtensionLoader(tempDir, networkContext).loadExtensions()
        try {
            val source = loaded.single().source
            desktopNetwork.bindSourceOwner { sourceId -> packageName.takeIf { sourceId == source.id } }
            val capturedClient = source.javaClass.getMethod("getClient").invoke(source) as OkHttpClient

            val manualRoute = capturedClient.proxySelector.select(URI("https://fixture.invalid")).single()
            assertEquals(Proxy.Type.SOCKS, manualRoute.type())
            assertTrue(manualRoute.address().toString().contains("17890"))
            assertEquals(DesktopPluginNetworkSupport.FULL, desktopNetwork.pluginNetworkSupport(listOf(source)))

            preferences.pluginNetworkMode(packageName).set(PluginNetworkMode.DIRECT)
            assertEquals(
                Proxy.NO_PROXY,
                capturedClient.proxySelector.select(URI("https://fixture.invalid")).single(),
            )

            MockWebServer().also { it.start() }.use { server ->
                server.enqueue(MockResponse(body = "ok"))
                capturedClient.newCall(Request.Builder().url(server.url("/private?token=hidden")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }
                assertEquals(setOf(server.hostName), preferences.pluginObservedDomains(packageName).get())
                assertEquals(packageName, desktopNetwork.routeObservations.value.last().scope)
            }
        } finally {
            loaded.map { it.classLoader }.distinct().forEach { (it as? AutoCloseable)?.close() }
            desktopNetwork.close()
            preferenceNode.removeNode()
        }
    }

    private fun createFixtureJar(jar: File) {
        val className = "fixture.extension.DerivedClientHttpSource"
        val classResource = className.replace('.', '/') + ".class"
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry(classResource))
            requireNotNull(javaClass.classLoader.getResourceAsStream(classResource)).use { it.copyTo(output) }
            output.closeEntry()
            output.putNextEntry(JarEntry("META-INF/services/eu.kanade.tachiyomi.source.Source"))
            output.write(className.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }
}
