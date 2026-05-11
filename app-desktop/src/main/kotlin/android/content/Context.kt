package android.content

/**
 * Desktop stub for android.content.Context.
 * Only the methods commonly used by manga source extensions are implemented.
 */
open class Context {
    private val prefsCache = mutableMapOf<String, SharedPreferences>()

    companion object {
        const val MODE_PRIVATE = 0
        const val MODE_WORLD_READABLE = 1
        const val MODE_WORLD_WRITEABLE = 2
        const val MODE_APPEND = 0x8000
    }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsCache.getOrPut(name) { DesktopSharedPreferences(name) }

    open fun getPackageName(): String = "mihon.desktop"

    open fun getString(resId: Int): String = ""

    open fun getFilesDir(): java.io.File =
        java.io.File(System.getProperty("user.home"), ".mihon/extensions").also { it.mkdirs() }

    open fun getCacheDir(): java.io.File =
        java.io.File(System.getProperty("user.home"), ".mihon/cache").also { it.mkdirs() }

    open fun getDir(name: String, mode: Int): java.io.File =
        java.io.File(System.getProperty("user.home"), ".mihon/$name").also { it.mkdirs() }

    open fun getApplicationContext(): Context = this

    open fun getClassLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

    open fun getSystemService(name: String): Any? = null
}
