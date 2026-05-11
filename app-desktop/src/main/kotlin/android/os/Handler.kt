package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Desktop stub for android.os.Handler.
 * Executes Runnables via a ScheduledExecutorService.
 */
open class Handler {
    val looper: Looper

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DesktopHandler").apply { isDaemon = true }
    }

    constructor(looper: Looper) {
        this.looper = looper
    }

    constructor() : this(Looper.getMainLooper())

    @Suppress("UNUSED_PARAMETER")
    constructor(looper: Looper, callback: Any?) : this(looper)

    open fun post(r: Runnable): Boolean {
        scheduler.submit(r)
        return true
    }

    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        scheduler.schedule(r, delayMillis, TimeUnit.MILLISECONDS)
        return true
    }

    open fun removeCallbacks(r: Runnable) { /* best-effort no-op */ }

    open fun removeCallbacksAndMessages(token: Any?) { /* no-op */ }

    open fun sendEmptyMessage(what: Int): Boolean = true

    open fun sendEmptyMessageDelayed(what: Int, delayMillis: Long): Boolean = true
}
