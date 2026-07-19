package android.content

/**
 * Desktop stub for android.content.ContextWrapper.
 * Proxies all calls to a base [Context].
 */
open class ContextWrapper(private var mBase: Context?) : Context() {

    protected fun attachBaseContext(base: Context) {
        check(mBase == null) { "Base context already set" }
        mBase = base
    }

    fun getBaseContext(): Context? = mBase

    override fun getApplicationContext(): Context = mBase ?: this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        mBase!!.getSharedPreferences(name, mode)

    override fun getPackageName(): String = mBase!!.getPackageName()

    override fun getString(resId: Int): String = mBase!!.getString(resId)

    override fun getFilesDir(): java.io.File = mBase!!.getFilesDir()

    override fun getCacheDir(): java.io.File = mBase?.getCacheDir()
        ?: java.io.File(System.getProperty("user.home"), ".mihon/cache").also { it.mkdirs() }

    override fun getDir(name: String, mode: Int): java.io.File =
        mBase?.getDir(name, mode)
            ?: java.io.File(System.getProperty("user.home"), ".mihon/$name").also { it.mkdirs() }

    override fun getClassLoader(): ClassLoader = mBase?.getClassLoader()
        ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

    override fun getSystemService(name: String): Any? = null
}
