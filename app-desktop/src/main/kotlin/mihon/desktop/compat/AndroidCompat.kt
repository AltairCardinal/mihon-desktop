package mihon.desktop.compat

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager

/**
 * Entry point for the Android API compat layer.
 *
 * Provides a singleton [Context] that APK-sourced extensions can use to access
 * SharedPreferences, file paths, etc. Modeled after Suwayomi's AndroidCompat
 * but simplified for Mihon Desktop's needs.
 *
 * Call [initialize] once at app startup before loading any extensions.
 */
object AndroidCompat {

    val context: Context = Context()

    val packageManager: PackageManager = PackageManager()

    @Volatile
    private var initialized = false

    /**
     * Initializes the Android compat layer. Safe to call multiple times.
     * Must be called before loading extensions.
     */
    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
    }

    /**
     * Attaches the compat [context] to [application] and calls [Application.onCreate].
     * Mirrors Suwayomi's `AndroidCompat.startApp(application)` pattern.
     */
    fun startApp(application: Application) {
        application.attach(context)
        application.onCreate()
    }
}
