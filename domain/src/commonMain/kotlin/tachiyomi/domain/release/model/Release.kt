package tachiyomi.domain.release.model

import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release.
 */
@Serializable
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    val downloadLink: String,
    val asset: ReleaseAsset = ReleaseAsset(
        name = "",
        target = ReleaseTarget(
            ReleaseOs.UNKNOWN,
            null,
            ReleasePackageType.UNKNOWN,
            ReleaseVariant.STANDARD,
        ),
        checksum = null,
    ),
    val versionCode: Long? = null,
    val sizeBytes: Long? = null,
    val applicationId: String? = null,
    val certificateSha256: String? = null,
    val infoTruncated: Boolean = false,
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val target: ReleaseTarget,
    val checksum: ReleaseChecksum?,
)

@Serializable
data class ReleaseTarget(
    val os: ReleaseOs,
    val arch: String?,
    val packageType: ReleasePackageType,
    val variant: ReleaseVariant,
)

@Serializable
data class ReleaseChecksum(
    val algorithm: String,
    val value: String,
)

@Serializable
enum class ReleaseOs { ANDROID, WINDOWS, MACOS, LINUX, UNKNOWN }

@Serializable
enum class ReleasePackageType { APK, MSI, DMG, APPIMAGE, ARCHIVE, UNKNOWN }

@Serializable
enum class ReleaseVariant { STANDARD, FOSS }
