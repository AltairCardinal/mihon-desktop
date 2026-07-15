package mihon.desktop.extension

import mihon.domain.extension.model.extractExtensionLibVersion
import mihon.domain.extension.model.isExtensionUpdateAvailable

/**
 * Returns the subset of [available] extensions that have a newer version than what is installed.
 */
fun findUpdatableExtensions(
    installed: List<InstalledExtension>,
    available: List<DesktopAvailableExtension>,
): List<DesktopAvailableExtension> {
    val installedByPkg = installed.associateBy { it.pkgName }
    return available.filter { avail ->
        if (avail.pkgName in BUNDLED_EXTENSION_PACKAGE_NAMES) return@filter false
        val inst = installedByPkg[avail.pkgName]
        inst != null && isExtensionUpdateAvailable(
            availableVersionCode = avail.versionCode,
            availableLibVersion = avail.libVersion,
            installedVersionCode = inst.versionCode,
            installedLibVersion = extractExtensionLibVersion(inst.versionName) ?: 0.0,
        )
    }
}

/**
 * Returns true if an extension with the given [pkgName] is in the [installed] list.
 */
fun isExtensionInstalled(pkgName: String, installed: List<InstalledExtension>): Boolean =
    installed.any { it.pkgName == pkgName }

/**
 * Returns true when the package is already usable on desktop, either from an installed
 * extension JAR or from a source bundled with the desktop app.
 */
fun isExtensionAvailableOnDesktop(pkgName: String, installed: List<InstalledExtension>): Boolean =
    isExtensionInstalled(pkgName, installed) || pkgName in BUNDLED_EXTENSION_PACKAGE_NAMES

private val BUNDLED_EXTENSION_PACKAGE_NAMES = setOf(
    "eu.kanade.tachiyomi.extension.all.mangadex",
)
