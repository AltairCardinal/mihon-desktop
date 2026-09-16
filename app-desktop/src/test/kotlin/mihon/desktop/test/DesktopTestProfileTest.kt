package mihon.desktop.test

import dev.mihon.injekt.patchInjekt
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopInstanceStartResult
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopOpenUriEventPort
import mihon.desktop.platform.DesktopOpenUriInstallResult
import mihon.desktop.platform.DesktopUriSchemeRegistrar
import mihon.desktop.platform.DesktopUriSchemeRegistration
import mihon.desktop.startProductionDesktopApplication
import mihon.desktop.test.http.LibraryMangaTestModeBridge
import mihon.desktop.test.http.LibraryMangaTestModeController
import mihon.desktop.test.http.SourceExtensionTestModeBridge
import mihon.desktop.test.http.SourceExtensionTestModeController
import mihon.desktop.ui.extension.ExtensionsScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.JvmDatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.prefs.Preferences

@Isolated
class DesktopTestProfileTest {
    @Test
    fun `production owner applies the explicit test profile to the default DI graph`(
        @TempDir root: File,
    ) = runBlocking {
        val args = arrayOf("--test-mode", "--headless", "--test-profile-dir=${root.resolve("runtime").path}")
        val profile = desktopTestProfile(args)!!
        val previousInjekt = Injekt
        patchInjekt()
        var started = false
        var systemRegistrations = 0
        try {
            val result = startProductionDesktopApplication(
                args = args,
                registrar = DesktopUriSchemeRegistrar {
                    systemRegistrations++
                    DesktopUriSchemeRegistration.Result.Unavailable(
                        DesktopUriSchemeRegistration.UnavailableReason.NON_PACKAGED_RUNTIME,
                    )
                },
                openUriEventPort = DesktopOpenUriEventPort { DesktopOpenUriInstallResult.Unsupported },
                startTestMode = {
                    started = true
                    Injekt.get<PreferenceStore>().getString("acceptance_marker", "").set("isolated")
                    assertEquals("isolated", profile.preferences().getString("acceptance_marker", "").get())
                },
                awaitTestModeTermination = {},
                stopTestMode = {},
            )
            assertTrue(started)
            assertEquals(DesktopInstanceStartResult.Owner, result)
            assertTrue(profile.paths.databaseFile.isFile)
            assertEquals(
                0,
                systemRegistrations,
                "An isolated acceptance instance must not replace the user's URI handler",
            )
        } finally {
            try {
                if (started) {
                    Injekt.get<DesktopAppRuntime>().closeAndJoin()
                    Injekt.get<LibraryUpdateScheduler>().stopAndJoin()
                    Injekt.get<CreatorDiscoveryScheduler>().stopAndJoin()
                    val library = Injekt.get<LibraryMangaTestModeController>()
                    library.closeAndJoin()
                    LibraryMangaTestModeBridge.clear(library)
                    SourceExtensionTestModeBridge.clear(Injekt.get<SourceExtensionTestModeController>())
                    Injekt.get<ExtensionsScreenModel>().closeAndJoin()
                    val network = Injekt.get<DesktopNetworkHelper>()
                    network.client.dispatcher.cancelAll()
                    Injekt.get<DesktopDownloadManager>().stopAndJoin()
                    Injekt.get<DesktopExtensionManager>().close()
                    network.close()
                    (Injekt.get<DatabaseHandler>() as JvmDatabaseHandler).close()
                }
            } finally {
                Injekt = previousInjekt
                Preferences.userRoot().node(profile.preferenceNode).removeNode()
            }
        }
    }

    @Test
    fun `explicit test profile isolates files and native preferences across restarts`(@TempDir root: File) {
        val args = arrayOf("--test-mode", "--test-profile-dir=${root.resolve("one").path}")
        val first = desktopTestProfile(args)
        assertNotNull(first)
        first!!
        try {
            val directory = root.resolve("one").canonicalFile.toPath()
            assertTrue(first.paths.defaultDirectories().all { it.canonicalFile.toPath().startsWith(directory) })
            assertTrue(first.paths.databaseFile.canonicalFile.toPath().startsWith(directory))
            assertTrue(first.paths.instanceStateFile.canonicalFile.toPath().startsWith(directory))
            assertTrue(first.preferenceNode.startsWith("/mihon/test-mode/"))
            first.preferences().getString("device_name", "").set("isolated device")
            val reopened = desktopTestProfile(args)!!
            assertEquals(first.paths, reopened.paths)
            assertEquals(first.preferenceNode, reopened.preferenceNode)
            assertEquals("isolated device", reopened.preferences().getString("device_name", "").get())
            val other = desktopTestProfile(arrayOf("--test-mode", "--test-profile-dir=${root.resolve("two").path}"))!!
            assertNotEquals(first.preferenceNode, other.preferenceNode)
        } finally {
            Preferences.userRoot().node(first.preferenceNode).removeNode()
        }
    }

    @Test
    fun `profile flag requires test mode and an absolute dedicated path`(@TempDir root: File) {
        assertNull(desktopTestProfile(emptyArray()))
        assertNull(desktopTestProfile(arrayOf("--test-mode")))
        assertThrows(IllegalArgumentException::class.java) {
            desktopTestProfile(arrayOf("--test-profile-dir=${root.path}"))
        }
        for (path in listOf("relative-profile", "", File(System.getProperty("user.home")).path)) {
            assertThrows(IllegalArgumentException::class.java) {
                desktopTestProfile(arrayOf("--test-mode", "--test-profile-dir=$path"))
            }
        }
    }
}
