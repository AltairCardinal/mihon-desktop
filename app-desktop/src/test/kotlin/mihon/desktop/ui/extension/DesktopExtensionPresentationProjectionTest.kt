package mihon.desktop.ui.extension

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.extension.InstalledExtension
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionUpdatePolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopExtensionPresentationProjectionTest {
    @Test
    fun `projection uses shared policy and classifier while preserving conservative desktop boundaries`() = runTest {
        val update = installed("pkg.update", repo = "https://ok.example")
        val noUpdate = installed("pkg.current", repo = "https://ok.example")
        val staleConversion = installed("pkg.stale", repo = "https://ok.example").copy(
            origin = ExtensionOrigin.CONVERTED_APK,
            apkConversionVersion = 0,
        )
        val obsolete = installed("pkg.obsolete", repo = "https://incompatible.example")
        val httpSource = mockk<HttpSource> {
            every { id } returns 42; every { lang } returns "en"; every { name } returns "Installed"; every { baseUrl } returns "https://installed.example"
        }
        val custom = installed("pkg.custom", repo = "", sources = listOf(httpSource))
        val failed = installed("pkg.failed", repo = "https://failed.example")
        val bundled = installed(BUNDLED, repo = "https://ok.example")
        val installed = listOf(update, noUpdate, staleConversion, obsolete, custom, failed, bundled)
        val updateCandidate = available("pkg.update", sources = listOf(source(7, "en", "Manga Hub")))
        val currentCandidate = available("pkg.current", code = 1)
        val staleCandidate = available("pkg.stale", code = 1)
        val multi = available(
            "pkg.multi",
            sources = listOf(source(8, "en", "English"), source(9, "fr", "Français")),
        )
        val bundledCandidate = available(BUNDLED)
        val catalog = DesktopExtensionCatalogState(
            ExtensionCatalogResult(
                listOf(
                    ExtensionCatalogEntry(
                        ExtensionArtifact("Incompatible", "pkg.other", "1.3.0", 1, "en", false, emptyList(), RepositoryIdentity("https://incompatible.example", "Other", "key"), "", "", null),
                        ExtensionCompatibility.UnsupportedLib(1.3, 1.4, 1.5),
                    ),
                ),
                listOf(
                    RepositoryCatalogFailure(
                        RepositoryIdentity("https://failed.example", "Failed", "key"),
                        AppError.Network(),
                    ),
                ),
            ),
            listOf(updateCandidate, currentCandidate, staleCandidate, multi, bundledCandidate),
        )
        val policy = mockk<ExtensionUpdatePolicy> {
            every { isUpdateAvailable(2, 1.4, 1, 1.4) } returns true
            every { isUpdateAvailable(1, 1.4, 1, 1.4) } returns false
        }
        val port = port(installed, policy)
        val projected = port.project(catalog)
        val classified = desktopExtensionPresentationStore.classify(
            projected.installed,
            emptyList(),
            projected.available,
            ExtensionPresentationOptions(false, setOf("en", "fr")),
        )

        assertEquals(setOf("pkg.update", "pkg.stale"), classified.updates.map { it.operationPackageName }.toSet())
        assertFalse(projected.installed.single { it.operationPackageName == "pkg.current" }.presentation.hasUpdate)
        assertTrue(projected.installed.single { it.operationPackageName == "pkg.stale" }.presentation.hasUpdate)
        assertTrue(projected.installed.single { it.operationPackageName == "pkg.obsolete" }.presentation.isObsolete)
        assertFalse(projected.installed.single { it.operationPackageName == "pkg.custom" }.presentation.isObsolete)
        assertFalse(projected.installed.single { it.operationPackageName == "pkg.failed" }.presentation.isObsolete)
        assertFalse(projected.installed.single { it.operationPackageName == BUNDLED }.presentation.hasUpdate)
        assertFalse(projected.available.any { it.operationPackageName == BUNDLED })
        assertEquals(listOf("pkg.multi", "pkg.multi"), classified.available.map { it.operationPackageName })
        assertEquals(listOf("pkg.multi-8", "pkg.multi-9"), classified.available.map { it.presentation.packageName })
        assertTrue(port.searchPredicate("pkg.current")(currentCandidate.copy(name = "Reader").item()))
        assertTrue(port.searchPredicate("missing, manga hub")(updateCandidate.item()))
        assertTrue(port.searchPredicate("reader.example")(updateCandidate.item()))
        assertTrue(port.searchPredicate("7")(updateCandidate.item()))
        assertTrue(port.searchPredicate("installed.example")(projected.installed.single { it.operationPackageName == "pkg.custom" }))
        verify { policy.isUpdateAvailable(2, 1.4, 1, 1.4) }
        verify(exactly = 1) { policy.isUpdateAvailable(1, 1.4, 1, 1.4) }
        assertSame(catalog.catalog.failures.single(), projected.failures.single())
        assertSame(catalog.catalog.failures.single().error, projected.failures.single().error)
    }

    @Test
    fun `raw install mapping preserves every state and exact success failure identity`() = runTest {
        val artifact = mockk<ExtensionArtifact>()
        val error = AppError.Network()
        val raw = listOf(
            ExtensionInstallState.Preparing, ExtensionInstallState.Validating, ExtensionInstallState.Committing,
            ExtensionInstallState.Reloading, ExtensionInstallState.RollingBack, ExtensionInstallState.RestoringRuntime,
            ExtensionInstallState.Installed(artifact), ExtensionInstallState.Failed(error), ExtensionInstallState.Failed(AppError.Cancelled),
        )
        val extension = available("pkg.install")
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val installedExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
        coEvery { api.beginInstall(extension, manager) } returns DesktopExtensionInstallStart.Started(flowOf(*raw.toTypedArray()))
        val port = DesktopExtensionPresentationPort(api, manager, installedExtensions)
        val start = port.beginPresentationInstall(extension) as DesktopPresentationInstallStart.Started
        val events = start.events.toList()

        assertEquals(ExtensionPresentationInstallStep.Pending, events.first().step)
        assertEquals(
            listOf(ExtensionPresentationInstallStep.Pending, ExtensionPresentationInstallStep.Downloading) +
                List(5) { ExtensionPresentationInstallStep.Installing } +
                listOf(ExtensionPresentationInstallStep.Installed, ExtensionPresentationInstallStep.Error, ExtensionPresentationInstallStep.Idle),
            events.map { it.step },
        )
        assertEquals(raw, events.drop(1).map { it.raw })
        assertSame(artifact, (events.single { it.raw is ExtensionInstallState.Installed }.raw as ExtensionInstallState.Installed).artifact)
        assertSame(error, (events.single { it.raw is ExtensionInstallState.Failed && it.raw.error === error }.raw as ExtensionInstallState.Failed).error)
        assertSame(AppError.Cancelled, (events.last().raw as ExtensionInstallState.Failed).error)
        val rejected = DesktopExtensionInstallStart.Rejected(error)
        coEvery { api.beginInstall(extension, manager) } returns rejected
        assertSame(error, (port.beginPresentationInstall(extension) as DesktopPresentationInstallStart.Rejected).error)
        val trust = DesktopExtensionInstallStart.TrustRequired("id", "old", "new", emptySet(), mockk())
        coEvery { api.beginInstall(extension, manager) } returns trust
        assertSame(trust, (port.beginPresentationInstall(extension) as DesktopPresentationInstallStart.TrustRequired).request)
    }

    private fun port(installed: List<InstalledExtension>, policy: ExtensionUpdatePolicy): DesktopExtensionPresentationPort {
        val manager = mockk<DesktopExtensionManager>()
        return DesktopExtensionPresentationPort(mockk(), manager, MutableStateFlow(installed), policy)
    }

    private fun installed(pkg: String, repo: String, sources: List<eu.kanade.tachiyomi.source.Source> = emptyList()) = InstalledExtension(
        File("$pkg.jar"), sources, versionCode = 1, versionName = "1.4.1",
        repoUrl = repo, displayName = pkg, language = "en",
    )

    private fun available(pkg: String, code: Long = 2, sources: List<DesktopAvailableSource> = emptyList()) = DesktopAvailableExtension(
        pkg, pkg, "1.4.2", code, libVersion = 1.4, lang = "en", isNsfw = false,
        jarUrl = "https://repo.example/$pkg.jar", iconUrl = "", repoUrl = "https://ok.example", sources = sources,
    )

    private fun source(id: Long, lang: String, name: String) =
        DesktopAvailableSource(id, lang, name, "https://reader.example/$id")

    private companion object {
        const val BUNDLED = "eu.kanade.tachiyomi.extension.all.mangadex"
    }
}
