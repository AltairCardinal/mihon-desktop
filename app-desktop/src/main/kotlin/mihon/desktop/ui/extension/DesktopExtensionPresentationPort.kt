package mihon.desktop.ui.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.service.ExtensionInstallState

data class DesktopExtensionCatalogState(
    val catalog: ExtensionCatalogResult,
    val available: List<DesktopAvailableExtension>,
)

class DesktopExtensionPresentationPort(
    private val api: DesktopExtensionApi,
    private val manager: DesktopExtensionManager,
) {
    val installedExtensions: StateFlow<List<InstalledExtension>> = manager.installedExtensions

    suspend fun refresh(): DesktopExtensionCatalogState {
        val catalog = api.refreshCatalog()
        return DesktopExtensionCatalogState(catalog, api.availableExtensions(catalog))
    }

    suspend fun beginInstall(extension: DesktopAvailableExtension): DesktopExtensionInstallStart =
        api.beginInstall(extension, manager)

    fun confirmTrust(requestId: String): Flow<ExtensionInstallState>? = api.confirmTrust(requestId, manager)

    fun discardTrust(requestId: String): Boolean = api.discardTrust(requestId)
}
