package tachiyomi.data.release

class DesktopPlatformInfo : PlatformInfo {
    override val preferredAbi: String?
        get() {
            val arch = System.getProperty("os.arch") ?: return null
            return when {
                arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
                arch.contains("aarch64") || arch.contains("arm64") -> "arm64-v8a"
                arch.contains("x86") -> "x86"
                else -> null
            }
        }
}
