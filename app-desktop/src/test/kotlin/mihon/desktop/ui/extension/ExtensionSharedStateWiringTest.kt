package mihon.desktop.ui.extension

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionSharedStateWiringTest {
    @Test
    fun `real port refresh reducer and authoritative flow preserve partial failure identity`() = runTest {
        val installedFlow = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val port = DesktopExtensionPresentationPort(api, manager, installedFlow)
        val failure = RepositoryCatalogFailure(
            RepositoryIdentity("https://failed", "Failed", "key"),
            AppError.Network(),
        )
        val catalog = ExtensionCatalogResult(emptyList(), listOf(failure))
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        coEvery { api.refreshCatalog() } coAnswers {
            refreshEntered.complete(Unit)
            releaseRefresh.await()
            catalog
        }
        every { api.availableExtensions(catalog) } returns emptyList()
        val initial = ExtensionPresentationOptions(false, setOf("en"))
        val changed = ExtensionPresentationOptions(true, setOf("en"))
        val model = ExtensionsScreenModel(port, backgroundScope, initial)

        model.setOptions(changed)
        assertEquals(changed, model.state.value.options)
        val refresh = model.refresh()
        refreshEntered.await()
        assertTrue(model.state.value.actions.isRefreshing)
        releaseRefresh.complete(Unit)
        refresh.join()
        assertFalse(model.state.value.actions.isRefreshing)
        assertSame(failure, model.state.value.projection?.failures?.single())
        val installed = installed("pkg.authoritative", "https://failed")
        installedFlow.value = listOf(installed)
        testScheduler.runCurrent()
        val projected = model.state.value.projection?.installed?.single()
        assertSame(installed, projected?.installed)
        assertEquals("pkg.authoritative", model.state.value.presentation?.installed?.single()?.operationPackageName)
        assertSame(failure, model.state.value.projection?.failures?.single())
        val refreshError = IllegalStateException("refresh failed")
        coEvery { api.refreshCatalog() } throws refreshError
        model.refresh().join()
        assertSame(refreshError, model.state.value.refreshError)
        assertFalse(model.state.value.actions.isRefreshing)
    }

    @Test
    fun `real shared update projection selects raw catalog candidate and typed uninstall exact instance`() = runTest {
        val installed = installed("pkg.update", "https://repo")
        val installedFlow = MutableStateFlow(listOf(installed))
        val candidate = available("pkg.update")
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        coEvery { api.refreshCatalog() } returns catalog
        every { api.availableExtensions(catalog) } returns listOf(candidate, available("pkg.not-update"))
        every { manager.removeExtensionWithMeta(any()) } returns true
        val port = DesktopExtensionPresentationPort(api, manager, installedFlow)
        val model = ExtensionsScreenModel(
            port,
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
        )

        model.refresh().join()
        val projected = model.state.value.projection?.installed?.single()
        assertTrue(projected?.presentation?.hasUpdate == true)
        assertSame(installed, projected?.installed)
        assertEquals("pkg.update", model.state.value.presentation?.updates?.single()?.operationPackageName)
        assertSame(candidate, model.updateAllCandidates().single())
        assertTrue(model.uninstall(checkNotNull(projected)))
        verify(exactly = 1) { manager.removeExtensionWithMeta(match { it === installed }) }
    }

    private fun installed(pkg: String, repo: String) = InstalledExtension(
        File("$pkg.jar"),
        emptyList(),
        versionCode = 1,
        versionName = "1.4.1",
        repoUrl = repo,
        displayName = pkg,
        language = "en",
    )

    private fun available(pkg: String) = DesktopAvailableExtension(
        pkg,
        pkg,
        "1.4.2",
        2,
        1.4,
        "en",
        false,
        "https://repo/$pkg.jar",
        "",
        "https://repo",
    )
}
