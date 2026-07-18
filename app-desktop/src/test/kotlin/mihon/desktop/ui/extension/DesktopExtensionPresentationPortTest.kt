package mihon.desktop.ui.extension

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.ExtensionMeta
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.extension.readExtensionMeta
import mihon.desktop.extension.writeExtensionMeta
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopExtensionPresentationPortTest {
    @Test
    fun `old sidecar defaults and new presentation metadata round trip`(@TempDir directory: File) {
        val jar = directory.resolve("reader.jar").also { it.writeText("jar") }
        directory.resolve("reader.meta.json").writeText(
            """{"pkgName":"reader","versionCode":1,"versionName":"1.4.1"}""",
        )
        val legacy = checkNotNull(readExtensionMeta(jar))
        assertEquals(listOf("reader", "", false), listOf(legacy.name, legacy.language, legacy.isNsfw))

        val current = ExtensionMeta(
            "reader", 2, "1.4.2", name = "Reader", language = "en", isNsfw = true,
            source = ExtensionOrigin.CONVERTED_APK,
        )
        writeExtensionMeta(jar, current)
        assertEquals(current, readExtensionMeta(jar))
    }

    @Test
    fun `typed port preserves manager authority and repository failures`() = runTest {
        val failure = RepositoryCatalogFailure(
            RepositoryIdentity("https://failed.example", "Failed", "key"),
            AppError.Network(),
        )
        val repository = RepositoryIdentity("https://ok.example", "OK", "key")
        val artifact = ExtensionArtifact(
            "Reader", "pkg.reader", "1.4.2", 2, "en", false, emptyList(), repository,
            "https://ok.example/reader.jar", "", null,
        )
        val catalog = ExtensionCatalogResult(
            listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)),
            listOf(failure),
        )
        val installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val available = DesktopAvailableExtension(
            "Reader", "pkg.reader", "1.4.2", 2, lang = "en", isNsfw = false,
            jarUrl = artifact.downloadUrl, iconUrl = "", repoUrl = repository.baseUrl,
        )
        val api = mockk<DesktopExtensionApi> { coEvery { refreshCatalog() } returns catalog }
        val manager = mockk<DesktopExtensionManager> {
            every { installedExtensions } returns installed
        }
        every { api.availableExtensions(catalog) } returns listOf(available)
        every { api.discardTrust("request") } returns true
        val states = flowOf(mihon.domain.extension.service.ExtensionInstallState.Preparing)
        val start = DesktopExtensionInstallStart.Started(states)
        coEvery { api.beginInstall(available, manager) } returns start
        every { api.confirmTrust("request", manager) } returns states
        val port = DesktopExtensionPresentationPort(api, manager)

        val refreshed = port.refresh()

        assertEquals(installed, port.installedExtensions)
        assertEquals(listOf(failure), refreshed.catalog.failures)
        assertEquals(listOf("pkg.reader"), refreshed.available.map(DesktopAvailableExtension::pkgName))
        assertSame(start, port.beginInstall(available))
        assertSame(states, port.confirmTrust("request"))
        assertEquals(true, port.discardTrust("request"))
    }
}
