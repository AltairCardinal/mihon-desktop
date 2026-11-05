package mihon.desktop.extension

import eu.kanade.tachiyomi.network.ExtensionLinkageNetworkContract
import eu.kanade.tachiyomi.network.NetworkHelper
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.GlobalNetworkMode
import okhttp3.Interceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

@Isolated
class DesktopExtensionLinkageIsolationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `production extension loader and desktop aliases isolate future bytecode in real enqueue`() {
        val context = DesktopExtensionNetworkContext()
        val network = DesktopNetworkHelper(
            cacheDir = tempDir.resolve("cache"),
            cookieStorageFile = tempDir.resolve("cookies.json"),
            globalMode = GlobalNetworkMode.DIRECT,
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            extensionNetworkContext = context,
        )
        val fixture = File(requireNotNull(System.getProperty("ec.future.jar")))
        val loader = DesktopExtensionLoader(tempDir, context)
        val loaded = loader.loadFromSingleJar(fixture)
        val previousFacade = ExtensionNetworkTestBridge.networkHelper
        val facade = NetworkHelper(
            network.client,
            extensionClientProvider = network::clientForCurrentExtension,
            sourceClientProvider = network::clientForSource,
        )
        ExtensionNetworkTestBridge.networkHelper = facade
        val aliasJar = tempDir.resolve("ec.aliases.jar")
        createDerivedClientFixtureJar(aliasJar)
        val aliasSources = loader.loadFromSingleJar(aliasJar)
        try {
            assertEquals(1, loaded.size, loader.diagnostics.toString())
            assertEquals(1, aliasSources.size, loader.diagnostics.toString())
            val aliasSource = aliasSources.single().source
            val aliasOwner = aliasSources.single().jarFile.nameWithoutExtension
            network.bindSourceOwner { if (it == aliasSource.id) aliasOwner else null }
            @Suppress("DEPRECATION")
            val aliases = aliasSource.javaClass.getMethod("callFromExtension", Function0::class.java).invoke(
                aliasSource,
                {
                    assertEquals(aliasOwner, context.currentPackage())
                    listOf(
                        facade.client,
                        facade.nonCloudflareClient,
                        facade.cloudflareClient,
                        facade.clientForSource(aliasSource.id),
                    )
                },
            ) as List<okhttp3.OkHttpClient>
            aliases.forEach { assertNotSame(network.client, it, "Expected an extension-scoped client") }
            ExtensionLinkageNetworkContract.verify(
                aliases,
            ) {
                loaded.single().classLoader.loadClass("ec.future.FutureInterceptor")
                    .getDeclaredConstructor().newInstance() as Interceptor
            }
            assertTrue(network.routeObservations.value.isNotEmpty())
            assertTrue(
                network.routeObservations.value.all {
                    it.scope == aliasOwner
                },
                network.routeObservations.value.toString(),
            )
        } finally {
            (loaded + aliasSources).map {
                it.classLoader
            }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
            network.close()
            ExtensionNetworkTestBridge.networkHelper = previousFacade
        }
    }
}
