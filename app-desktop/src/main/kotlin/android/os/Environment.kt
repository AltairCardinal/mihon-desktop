package android.os

import java.io.File

/**
 * Desktop stub for android.os.Environment.
 * Maps Android storage paths to ~/.mihon/ directories.
 */
object Environment {
    const val MEDIA_MOUNTED = "mounted"
    const val MEDIA_UNMOUNTED = "unmounted"
    const val MEDIA_REMOVED = "removed"

    private val home = File(System.getProperty("user.home"), ".mihon")

    @JvmStatic
    fun getExternalStorageDirectory(): File =
        File(home, "storage").also { it.mkdirs() }

    @JvmStatic
    fun getDataDirectory(): File =
        File(home, "data").also { it.mkdirs() }

    @JvmStatic
    fun getDownloadCacheDirectory(): File =
        File(home, "cache").also { it.mkdirs() }

    @JvmStatic
    fun getRootDirectory(): File = home.also { it.mkdirs() }

    @JvmStatic
    fun getExternalStorageState(): String = MEDIA_MOUNTED

    @JvmStatic
    fun isExternalStorageEmulated(): Boolean = true
}
