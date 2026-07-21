package tachiyomi.domain.release.model

/**
 * Contains information about the latest release.
 */
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
)

data class ReleaseAsset(
    val name: String,
    val target: ReleaseTarget,
    val checksum: ReleaseChecksum?,
)

data class ReleaseTarget(
    val os: ReleaseOs,
    val arch: String?,
    val packageType: ReleasePackageType,
    val variant: ReleaseVariant,
)

data class ReleaseChecksum(
    val algorithm: String,
    val value: String,
)

enum class ReleaseOs { ANDROID, WINDOWS, MACOS, LINUX, UNKNOWN }

enum class ReleasePackageType { APK, MSI, DMG, APPIMAGE, ARCHIVE, UNKNOWN }

enum class ReleaseVariant { STANDARD, FOSS }
