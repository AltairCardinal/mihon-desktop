package android.content.pm

/**
 * Desktop stub for android.content.pm.ApplicationInfo.
 */
open class ApplicationInfo {
    var packageName: String = "mihon.desktop"
    var name: String? = null
    var sourceDir: String? = null
    @JvmField
    var dataDir: String? = null
    var nativeLibraryDir: String? = null
    var flags: Int = 0
    var targetSdkVersion: Int = android.os.Build.VERSION.SDK_INT
    var minSdkVersion: Int = 21
    var enabled: Boolean = true

    companion object {
        const val FLAG_SYSTEM = 1
        const val FLAG_DEBUGGABLE = 2
    }
}
