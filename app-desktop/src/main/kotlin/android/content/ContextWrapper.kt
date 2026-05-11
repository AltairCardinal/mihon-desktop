package android.content

/**
 * Desktop stub for android.content.ContextWrapper.
 * Proxies all calls to a base [Context].
 */
open class ContextWrapper(private var mBase: Context?) {

    protected fun attachBaseContext(base: Context) {
        check(mBase == null) { "Base context already set" }
        mBase = base
    }

    fun getBaseContext(): Context? = mBase

    open fun getApplicationContext(): Context? = mBase

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        mBase!!.getSharedPreferences(name, mode)

    open fun getPackageName(): String = mBase!!.getPackageName()

    open fun getString(resId: Int): String = mBase!!.getString(resId)

    open fun getFilesDir(): java.io.File = mBase!!.getFilesDir()

    open fun getCacheDir(): java.io.File = mBase?.getCacheDir()
        ?: java.io.File(System.getProperty("user.home"), ".mihon/cache").also { it.mkdirs() }

    open fun getDir(name: String, mode: Int): java.io.File =
        mBase?.getDir(name, mode)
            ?: java.io.File(System.getProperty("user.home"), ".mihon/$name").also { it.mkdirs() }

    open fun getClassLoader(): ClassLoader = mBase?.getClassLoader()
        ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

    open fun getSystemService(name: String): Any? = null
}
