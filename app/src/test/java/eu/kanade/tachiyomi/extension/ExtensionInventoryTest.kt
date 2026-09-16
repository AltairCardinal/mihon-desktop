package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.suggestion.ExtensionInventoryLocation
import mihon.domain.extension.suggestion.ExtensionPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExtensionInventoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `system and private packages exist regardless of runtime loading or trust`() {
        val manager = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.filesDir } returns temporaryFolder.root
        every { context.packageManager } returns manager
        every { manager.getInstalledPackages(any<PackageManager.PackageInfoFlags>()) } returns
            listOf(extension("pkg.shared"))
        val privateDirectory = File(temporaryFolder.root, "exts").apply { mkdir() }
        val privateFile = File(privateDirectory, "pkg.private.ext").apply { writeText("fixture") }
        every { manager.getPackageArchiveInfo(privateFile.absolutePath, any<Int>()) } returns extension("pkg.private")
        val metadataDirectory = File(temporaryFolder.root, "extension-install-metadata").apply { mkdir() }
        File(metadataDirectory, "system-pkg.shared.properties").writeText(
            "repository.baseUrl=https://installed.example\nrepository.name=Installed\n" +
                "repository.fingerprint=key\nartifact.sha256=digest\n",
        )
        var inventory = ExtensionLoader.scanInventory(context)
        assertEquals(setOf("pkg.shared", "pkg.private"), inventory.packages.keys)
        assertTrue(inventory.packages.values.all { it == ExtensionPresence.PRESENT })
        assertFalse(inventory.hasUnknownArtifacts)
        assertEquals(
            setOf(ExtensionInventoryLocation.ANDROID_SHARED),
            inventory.records.getValue("pkg.shared").locations,
        )
        assertEquals(
            setOf(ExtensionInventoryLocation.ANDROID_PRIVATE),
            inventory.records.getValue("pkg.private").locations,
        )
        assertEquals("https://installed.example", inventory.records.getValue("pkg.shared").repository?.baseUrl)
        assertEquals(null, inventory.records.getValue("pkg.private").repository)
        assertEquals(null, inventory.records.getValue("pkg.shared").runtimeLoaded)
        val samePackage = File(privateDirectory, "pkg.shared.ext").apply { writeText("shared-private") }
        every { manager.getPackageArchiveInfo(samePackage.absolutePath, any<Int>()) } returns extension("pkg.shared")
        assertEquals(
            setOf(ExtensionInventoryLocation.ANDROID_SHARED, ExtensionInventoryLocation.ANDROID_PRIVATE),
            ExtensionLoader.scanInventory(context).records.getValue("pkg.shared").locations,
        )
        every { manager.getPackageArchiveInfo(privateFile.absolutePath, any<Int>()) } returns null
        inventory = ExtensionLoader.scanInventory(context)
        assertTrue(inventory.hasUnknownArtifacts)
        assertEquals(ExtensionPresence.UNKNOWN, inventory.packages["pkg.private"])
    }

    @Test
    fun `failed extension load broadcast still notifies package inventory`() = runBlocking {
        val notified = CompletableDeferred<String>()
        val listener = mockk<ExtensionInstallReceiver.Listener>(relaxed = true)
        every { listener.onPackageChanged(any()) } answers { notified.complete(firstArg()) }
        val receiver = ExtensionInstallReceiver(listener)
        mockkObject(ExtensionLoader)
        try {
            coEvery { ExtensionLoader.loadExtensionFromPkgName(any(), "pkg.failed") } returns LoadResult.Error
            receiver.onReceive(
                mockk(relaxed = true),
                Intent(
                    Intent.ACTION_PACKAGE_ADDED,
                    android.net.Uri.parse("package:pkg.failed"),
                ),
            )
            assertEquals("pkg.failed", withTimeout(5_000) { notified.await() })
        } finally {
            receiver.scope.cancel()
            unmockkObject(ExtensionLoader)
        }
    }

    private fun extension(name: String) = PackageInfo().apply {
        packageName = name
        reqFeatures = arrayOf(FeatureInfo().apply { this.name = "tachiyomi.extension" })
    }
}
