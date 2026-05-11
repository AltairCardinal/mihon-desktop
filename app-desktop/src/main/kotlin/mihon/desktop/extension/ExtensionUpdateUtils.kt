package mihon.desktop.extension

/**
 * Returns the subset of [available] extensions that have a newer version than what is installed.
 */
fun findUpdatableExtensions(
    installed: List<InstalledExtension>,
    available: List<DesktopAvailableExtension>,
): List<DesktopAvailableExtension> {
    val installedByPkg = installed.associateBy { it.pkgName }
    return available.filter { avail ->
        val inst = installedByPkg[avail.pkgName]
        inst != null && avail.versionCode > inst.versionCode
    }
}

/**
 * Returns true if an extension with the given [pkgName] is in the [installed] list.
 */
fun isExtensionInstalled(pkgName: String, installed: List<InstalledExtension>): Boolean =
    installed.any { it.pkgName == pkgName }
