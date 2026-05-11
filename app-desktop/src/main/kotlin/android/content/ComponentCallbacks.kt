package android.content

/**
 * Desktop stub for android.content.ComponentCallbacks.
 */
interface ComponentCallbacks {
    fun onConfigurationChanged(newConfig: Any?)
    fun onLowMemory()
}

/**
 * Desktop stub for android.content.ComponentCallbacks2.
 */
interface ComponentCallbacks2 : ComponentCallbacks {
    fun onTrimMemory(level: Int)

    companion object {
        const val TRIM_MEMORY_COMPLETE = 80
        const val TRIM_MEMORY_MODERATE = 60
        const val TRIM_MEMORY_BACKGROUND = 40
        const val TRIM_MEMORY_UI_HIDDEN = 20
        const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        const val TRIM_MEMORY_RUNNING_LOW = 10
        const val TRIM_MEMORY_RUNNING_MODERATE = 5
    }
}
