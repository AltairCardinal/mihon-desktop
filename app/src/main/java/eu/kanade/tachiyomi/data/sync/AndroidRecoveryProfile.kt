package eu.kanade.tachiyomi.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Selection is read before business initialization; control preferences belong to the raw base context. */
object AndroidRecoveryProfile {
    const val CONTROL_PREFERENCES = "mihon-recovery-control"

    fun createAndSelect(raw: Context): String {
        val preceding = selected(raw)
        require(preceding == null || preceding.length <= 128) { "The previous instance cannot be preserved" }
        val id = UUID.randomUUID().toString()
        val root = root(raw, id)
        check(root.mkdirs()) { "The recovery instance could not be created" }
        File(root, ".mihon-recovery-profile").writeText(id, Charsets.UTF_8)
        val original = if (preceding == null) {
            raw.getDatabasePath("tachiyomi.db").canonicalPath
        } else {
            runCatching { File(root(raw, preceding), "databases/tachiyomi.db").canonicalPath }
                .getOrDefault("Unverified preceding instance")
        }
        File(root, "origin.txt").writeText(original, Charsets.UTF_8)
        File(root, "previous-selection.txt").writeText(preceding.orEmpty(), Charsets.UTF_8)
        check(
            raw.getSharedPreferences(CONTROL_PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("selected", id).commit(),
        )
        return id
    }

    fun wrap(raw: Context, application: Application, processName: String? = null): Context {
        if (processName?.endsWith(":error_handler") == true) return raw
        val id = selected(raw) ?: return raw
        val profile = root(raw, id)
        val marker = File(profile, ".mihon-recovery-profile")
        require(
            profile.isDirectory && marker.isFile && marker.length() <= 128 &&
                marker.readText(Charsets.UTF_8) == id,
        ) { "The recovery instance cannot be verified" }
        require(profile.canonicalFile == profile.absoluteFile) { "The recovery directory must not redirect" }
        return AndroidRecoveryContext(raw, application, id)
    }

    internal fun selected(raw: Context): String? = raw.getSharedPreferences(CONTROL_PREFERENCES, Context.MODE_PRIVATE)
        .getString("selected", null)

    internal fun root(raw: Context, id: String): File {
        require(
            runCatching {
                UUID.fromString(id).toString() == id
            }.getOrDefault(false),
        ) { "Invalid recovery instance" }
        return File(raw.filesDir, "recovery-profiles/$id")
    }

    fun raw(context: Context): Context {
        val app = context.applicationContext
        val base = (app as? ContextWrapper)?.baseContext ?: context
        return if (base is AndroidRecoveryContext) base.baseContext else base
    }

    fun origin(context: Context): String? {
        val app = context.applicationContext
        val wrapped = (app as? ContextWrapper)?.baseContext as? AndroidRecoveryContext ?: return null
        val file = File(root(wrapped.baseContext, wrapped.profileId), "origin.txt")
        return if (file.isFile && file.length() <= 16_384) file.readText(Charsets.UTF_8) else null
    }

    fun storageDirectory(context: Context): File? {
        val app = context.applicationContext
        return if ((app as? ContextWrapper)?.baseContext is AndroidRecoveryContext) {
            File(
                app.filesDir,
                "storage",
            )
        } else {
            null
        }
    }

    fun previousSelection(raw: Context, id: String): String? {
        val pointer = File(root(raw, id), "previous-selection.txt")
        require(pointer.isFile && pointer.length() <= 128)
        return pointer.readText(Charsets.UTF_8).takeIf(String::isNotEmpty)
    }

    private fun frozenProfile(context: Context): AndroidRecoveryContext? {
        val app = context.applicationContext
        return (app as? ContextWrapper)?.baseContext as? AndroidRecoveryContext
    }

    fun webViewSuffix(context: Context, processName: String): String? {
        val profile = frozenProfile(context)
        return if (profile != null) {
            "recovery-${profile.profileId}-${processName.substringAfter(':', "main")}"
        } else {
            processName.takeIf { it != context.packageName }
        }
    }

    fun canUseInternalWebView(context: Context, apiLevel: Int = android.os.Build.VERSION.SDK_INT): Boolean =
        frozenProfile(context) == null || apiLevel >= 28

    /** Called before DI creates CookieManager or any WebView. */
    fun configureWebView(
        context: Context,
        processName: String,
        apiLevel: Int = android.os.Build.VERSION.SDK_INT,
        setSuffix: (String) -> Unit = android.webkit.WebView::setDataDirectorySuffix,
    ) {
        eu.kanade.tachiyomi.util.system.WebViewUtil.storageAccessible = canUseInternalWebView(context, apiLevel)
        if (apiLevel >= 28) webViewSuffix(context, processName)?.let(setSuffix)
    }

    fun selectedDatabaseDescription(raw: Context): String {
        val id = selected(raw) ?: return raw.getDatabasePath("tachiyomi.db").path
        return runCatching { File(root(raw, id), "databases/tachiyomi.db").absolutePath }
            .getOrElse { "${raw.filesDir}/recovery-profiles / $id (unverified)" }
    }
}

/** Context roots are immutable for the process. Database opening uses an explicit absolute profile path. */
internal class AndroidRecoveryContext(
    base: Context,
    private val owner: Application,
    val profileId: String,
) : ContextWrapper(base) {
    private val root = AndroidRecoveryProfile.root(base, profileId)
    private fun directory(file: File): File = file.also {
        check(it.isDirectory || it.mkdirs()) { "The recovery directory is not writable" }
    }
    override fun getApplicationContext(): Context = owner
    override fun getFilesDir(): File = directory(File(root, "files"))
    override fun getCacheDir(): File = directory(File(baseContext.cacheDir, "recovery-profiles/$profileId"))
    override fun getNoBackupFilesDir(): File = directory(
        File(baseContext.noBackupFilesDir, "recovery-profiles/$profileId"),
    )
    override fun getCodeCacheDir(): File = directory(File(baseContext.codeCacheDir, "recovery-profiles/$profileId"))
    override fun getDatabasePath(name: String): File {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' } && name != "." && name != "..")
        return File(directory(File(root, "databases")), name)
    }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("recovery-$profileId-$name", mode)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory)
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase =
        baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory, errorHandler)
    override fun deleteDatabase(name: String): Boolean = baseContext.deleteDatabase(getDatabasePath(name).path)
    override fun databaseList(): Array<String> = File(root, "databases").list()?.toList().orEmpty().toTypedArray()
    override fun getDir(name: String, mode: Int): File = directory(File(root, "directories/$name"))
}
