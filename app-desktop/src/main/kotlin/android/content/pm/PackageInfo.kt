package android.content.pm

/**
 * Desktop stub for android.content.pm.PackageInfo.
 */
open class PackageInfo {
    var packageName: String = "mihon.desktop"
    var versionName: String? = null
    var versionCode: Int = 0

    @Suppress("PropertyName")
    var longVersionCode: Long
        get() = versionCode.toLong()
        set(value) { versionCode = value.toInt() }

    var applicationInfo: ApplicationInfo? = null
    var firstInstallTime: Long = System.currentTimeMillis()
    var lastUpdateTime: Long = System.currentTimeMillis()
}
