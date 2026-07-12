package mihon.desktop.extension

fun filterAvailableByQuery(
    extensions: List<DesktopAvailableExtension>,
    query: String,
): List<DesktopAvailableExtension> = extensions.filter { extension ->
    query.isBlank() || listOf(extension.name, extension.pkgName)
        .plus(extension.sources.map { it.name })
        .any { it.contains(query.trim(), ignoreCase = true) }
}

fun filterInstalledByQuery(
    extensions: List<InstalledExtension>,
    query: String,
): List<InstalledExtension> = extensions.filter { extension ->
    query.isBlank() || listOf(extension.name, extension.pkgName)
        .plus(extension.sources.map { it.name })
        .any { it.contains(query.trim(), ignoreCase = true) }
}
