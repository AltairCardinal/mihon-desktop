package mihon.desktop.ui.extension

import mihon.domain.extension.model.RepositoryCatalogFailure
import tachiyomi.i18n.MR
import java.util.Locale

internal data class ExtensionListUiProjection(
    val installed: List<DesktopExtensionItem>,
    val available: List<DesktopExtensionItem>,
    val updates: List<DesktopExtensionItem>,
    val failures: List<RepositoryCatalogFailure>,
)

internal fun DesktopExtensionsState.toExtensionListUiProjection(query: String): ExtensionListUiProjection {
    val classified = presentation
    val matches = desktopExtensionPresentationStore.searchPredicate(query, includePackageName = true)
    val updatePackages = classified?.updates.orEmpty().mapTo(mutableSetOf(), DesktopExtensionItem::operationPackageName)
    val updateCandidates = projection?.available.orEmpty().filter { it.operationPackageName in updatePackages }
    return ExtensionListUiProjection(
        installed = (classified?.updates.orEmpty() + classified?.installed.orEmpty()).filter(matches),
        available = classified?.available.orEmpty().filter(matches),
        updates = updateCandidates.filter(matches),
        failures = projection?.failures.orEmpty(),
    )
}

internal data class ExtensionListCopy(
    val title: String,
    val available: String,
    val search: String,
    val loading: String,
    val emptyInstalled: String,
    val emptyInstalledHint: String,
    val emptyAvailable: String,
    val noRepositories: String,
    val refreshingCached: String,
    val repositoryFailure: String,
    val retry: String,
)

internal fun extensionListCopy(locale: Locale = Locale.getDefault()) = ExtensionListCopy(
    title = MR.strings.label_extensions.localized(locale),
    available = MR.strings.desktop_extension_available.localized(locale),
    search = MR.strings.desktop_extension_search.localized(locale),
    loading = MR.strings.desktop_extension_loading_available.localized(locale),
    emptyInstalled = MR.strings.desktop_extension_empty_installed.localized(locale),
    emptyInstalledHint = MR.strings.desktop_extension_empty_installed_hint.localized(locale),
    emptyAvailable = MR.strings.desktop_extension_empty_available.localized(locale),
    noRepositories = MR.strings.desktop_extension_no_repositories.localized(locale),
    refreshingCached = MR.strings.desktop_extension_refreshing_cached.localized(locale),
    repositoryFailure = MR.strings.extension_api_error.localized(locale),
    retry = MR.strings.action_retry.localized(locale),
)
