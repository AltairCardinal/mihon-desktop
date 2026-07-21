package tachiyomi.data.release

import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant

/**
 * Provides platform-specific information needed for release checking.
 */
interface PlatformInfo {
    /**
     * Returns the preferred ABI (architecture) for the current platform.
     * On Android, this is Build.SUPPORTED_ABIS[0].
     * On desktop, this returns a desktop-appropriate identifier.
     */
    val preferredAbi: String?

    val releaseOs: ReleaseOs
        get() = ReleaseOs.UNKNOWN

    val releasePackageType: ReleasePackageType
        get() = ReleasePackageType.UNKNOWN

    fun releaseTarget(isFoss: Boolean) = ReleaseTarget(
        os = releaseOs,
        arch = preferredAbi.takeUnless { isFoss },
        packageType = releasePackageType,
        variant = if (isFoss) ReleaseVariant.FOSS else ReleaseVariant.STANDARD,
    )
}
