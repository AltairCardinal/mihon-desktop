package android.app

import android.content.Context
import android.content.ContextWrapper

/**
 * Desktop stub for android.app.Application.
 * Mirrors the Android Application class that some extensions subclass.
 */
open class Application : ContextWrapper(null) {

    open fun onCreate() {}

    open fun onTerminate() {}

    fun attach(context: Context) {
        attachBaseContext(context)
    }
}
