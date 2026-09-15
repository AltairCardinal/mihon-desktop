package mihon.desktop.extension

import mihon.domain.extension.model.EXTENSION_LIB_VERSION_MIN
import mihon.domain.extension.model.EXTENSION_LIB_VERSION_MAX
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.extractExtensionLibVersion

/**
 * An extension available for installation from a repository.
 */
data class DesktopAvailableExtension(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double = extractExtensionLibVersion(versionName) ?: 0.0,
    val lang: String,
    val isNsfw: Boolean,
    /** URL to download the JAR file. */
    val jarUrl: String,
    val iconUrl: String,
    val repoUrl: String,
    val repoName: String = "",
    val repoFingerprint: String = "",
    val declaredSha256: String? = null,
    val sources: List<DesktopAvailableSource> = emptyList(),
) {
    val compatibility: ExtensionCompatibility
        get() {
            return if (libVersion in EXTENSION_LIB_VERSION_MIN..EXTENSION_LIB_VERSION_MAX) {
                ExtensionCompatibility.Compatible
            } else {
                ExtensionCompatibility.UnsupportedLib(libVersion, EXTENSION_LIB_VERSION_MIN, EXTENSION_LIB_VERSION_MAX)
            }
        }
}

data class DesktopAvailableSource(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)
