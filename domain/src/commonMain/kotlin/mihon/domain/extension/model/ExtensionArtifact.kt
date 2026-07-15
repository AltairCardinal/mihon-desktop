package mihon.domain.extension.model

import mihon.domain.extensionrepo.model.ExtensionRepo

const val EXTENSION_LIB_VERSION_MIN = 1.4
const val EXTENSION_LIB_VERSION_MAX = 1.5

data class RepositoryIdentity(
    val baseUrl: String,
    val name: String,
    val signingKeyFingerprint: String,
)

fun ExtensionRepo.toIdentity(): RepositoryIdentity = RepositoryIdentity(
    baseUrl = baseUrl,
    name = name,
    signingKeyFingerprint = signingKeyFingerprint,
)

data class ExtensionSourceDescriptor(
    val id: Long,
    val language: String,
    val name: String,
    val baseUrl: String,
)

data class ExtensionArtifact(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val language: String,
    val isNsfw: Boolean,
    val sources: List<ExtensionSourceDescriptor>,
    val repository: RepositoryIdentity,
    val downloadUrl: String,
    val iconUrl: String,
    val declaredSha256: String?,
) {
    val libVersion: Double = extractExtensionLibVersion(versionName) ?: 0.0

    fun compatibility(): ExtensionCompatibility = when (libVersion) {
        in EXTENSION_LIB_VERSION_MIN..EXTENSION_LIB_VERSION_MAX -> ExtensionCompatibility.Compatible
        else -> ExtensionCompatibility.UnsupportedLib(
            libVersion = libVersion,
            minimum = EXTENSION_LIB_VERSION_MIN,
            maximum = EXTENSION_LIB_VERSION_MAX,
        )
    }

    fun isUpdateAvailable(installedVersionCode: Long, installedLibVersion: Double): Boolean {
        return isExtensionUpdateAvailable(versionCode, libVersion, installedVersionCode, installedLibVersion)
    }
}

data class InstalledExtensionTrustRecord(
    val repository: RepositoryIdentity?,
    val artifactSha256: String?,
)

fun extractExtensionLibVersion(versionName: String): Double? =
    versionName.substringBeforeLast('.', missingDelimiterValue = "").toDoubleOrNull()

fun isExtensionUpdateAvailable(
    availableVersionCode: Long,
    availableLibVersion: Double,
    installedVersionCode: Long,
    installedLibVersion: Double,
): Boolean = availableVersionCode > installedVersionCode || availableLibVersion > installedLibVersion
