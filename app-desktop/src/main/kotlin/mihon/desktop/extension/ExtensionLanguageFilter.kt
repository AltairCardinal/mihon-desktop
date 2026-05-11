package mihon.desktop.extension

/**
 * Returns [extensions] filtered to those whose lang is in [selectedLangs].
 * If [selectedLangs] is empty, all extensions are returned (no filter applied).
 */
fun filterAvailableByLangs(
    extensions: List<DesktopAvailableExtension>,
    selectedLangs: Set<String>,
): List<DesktopAvailableExtension> {
    if (selectedLangs.isEmpty()) return extensions
    return extensions.filter { it.lang in selectedLangs }
}

/**
 * Returns [extensions] filtered to those where at least one source lang is in [selectedLangs].
 * If [selectedLangs] is empty, all extensions are returned (no filter applied).
 */
fun filterInstalledByLangs(
    extensions: List<InstalledExtension>,
    selectedLangs: Set<String>,
): List<InstalledExtension> {
    if (selectedLangs.isEmpty()) return extensions
    return extensions.filter { ext -> ext.sources.any { it.lang in selectedLangs } }
}

/**
 * Returns [extensions] filtered by NSFW status.
 * When [showNsfw] is false, NSFW extensions are excluded.
 */
fun filterAvailableByNsfw(
    extensions: List<DesktopAvailableExtension>,
    showNsfw: Boolean,
): List<DesktopAvailableExtension> {
    if (showNsfw) return extensions
    return extensions.filter { !it.isNsfw }
}

/** Returns sorted unique language codes from a list of available extensions. */
fun availableLangs(extensions: List<DesktopAvailableExtension>): List<String> =
    extensions.map { it.lang }.toSortedSet().toList()

/** Returns sorted unique language codes from all sources of installed extensions. */
fun installedLangs(extensions: List<InstalledExtension>): List<String> =
    extensions.flatMap { ext -> ext.sources.map { it.lang } }.toSortedSet().toList()
