package tachiyomi.data.release

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
}
