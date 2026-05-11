package android.content.pm

/**
 * Desktop stub for android.content.pm.PackageManager.
 * Returns placeholder PackageInfo/ApplicationInfo for any package name.
 */
open class PackageManager {

    open fun getPackageInfo(packageName: String, flags: Int): PackageInfo =
        PackageInfo().apply {
            this.packageName = packageName
            versionName = "1.0.0"
            versionCode = 1
            applicationInfo = getApplicationInfo(packageName, flags)
        }

    open fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo =
        ApplicationInfo().apply {
            this.packageName = packageName
        }

    companion object {
        const val GET_META_DATA = 128
        const val GET_SIGNATURES = 64
    }
}
