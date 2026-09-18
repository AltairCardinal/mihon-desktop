package mihon.desktop.extension

import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.ExtensionInventoryRecord
import mihon.domain.extension.suggestion.ExtensionInventoryLocation
import mihon.domain.extension.model.RepositoryIdentity
import java.io.File

/** Only final top-level JARs are inventory; transaction directories and sidecars alone are not. */
internal fun scanDesktopExtensionInventory(directory: File, loaded: List<InstalledExtension>): ExtensionInventory {
    return try {
        val files = if (directory.exists()) directory.listFiles() else emptyArray()
        val packages = loaded.associateTo(mutableMapOf()) { extension ->
            extension.pkgName to ExtensionInventoryRecord(ExtensionPresence.PRESENT,
                setOf(ExtensionInventoryLocation.DESKTOP), runtimeLoaded = true)
        }
        var unknown = files == null
        files.orEmpty().filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }.forEach { jar ->
            val packageName = jar.nameWithoutExtension
            val metadata = readExtensionMeta(jar)?.takeIf { it.pkgName == packageName }
            val repository = metadata?.takeIf { it.repoUrl.isNotBlank() }?.let {
                RepositoryIdentity(it.repoUrl, it.repoName, it.repoFingerprint)
            }
            val previous = packages[packageName]
            val presence = previous?.presence ?: if (metadata != null) ExtensionPresence.LOAD_FAILED else ExtensionPresence.UNKNOWN
            packages[packageName] = ExtensionInventoryRecord(
                presence, setOf(ExtensionInventoryLocation.DESKTOP),
                repository?.let { mapOf(ExtensionInventoryLocation.DESKTOP to it) }.orEmpty(),
                previous?.runtimeLoaded ?: false,
            )
            if (presence == ExtensionPresence.UNKNOWN) {
                unknown = true
            }
        }
        ExtensionInventory(true, unknown, packages)
    } catch (_: Exception) {
        ExtensionInventory(initialized = true, hasUnknownArtifacts = true)
    }
}
