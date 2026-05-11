package android.os

import java.util.concurrent.Executors

/**
 * Desktop stub for android.os.Looper.
 * Uses a shared executor service instead of Android's MessageQueue.
 */
class Looper private constructor() {

    internal val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DesktopMainLooper").apply { isDaemon = true }
    }

    companion object {
        private val mainLooper = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = mainLooper

        @JvmStatic
        fun myLooper(): Looper = mainLooper

        @JvmStatic
        fun prepare() { /* no-op on desktop */ }

        @JvmStatic
        fun loop() { /* no-op on desktop */ }
    }
}
