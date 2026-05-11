package android.app

import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper

/**
 * Desktop stub for android.app.Application.
 * Mirrors the Android Application class that some extensions subclass.
 */
open class Application : ContextWrapper(null), ComponentCallbacks2 {

    private val componentCallbacks = mutableListOf<ComponentCallbacks>()

    open fun onCreate() {}

    open fun onTerminate() {}

    override fun onConfigurationChanged(newConfig: Any?) {
        synchronized(componentCallbacks) {
            componentCallbacks.toList()
        }.forEach { it.onConfigurationChanged(newConfig) }
    }

    override fun onLowMemory() {
        synchronized(componentCallbacks) {
            componentCallbacks.toList()
        }.forEach { it.onLowMemory() }
    }

    override fun onTrimMemory(level: Int) {
        synchronized(componentCallbacks) {
            componentCallbacks.toList()
        }.filterIsInstance<ComponentCallbacks2>().forEach { it.onTrimMemory(level) }
    }

    fun registerComponentCallbacks(callback: ComponentCallbacks) {
        synchronized(componentCallbacks) { componentCallbacks.add(callback) }
    }

    fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
        synchronized(componentCallbacks) { componentCallbacks.remove(callback) }
    }

    fun attach(context: Context) {
        attachBaseContext(context)
    }
}
