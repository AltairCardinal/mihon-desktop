package eu.kanade.tachiyomi.ui.reader

import android.os.Handler
import android.os.Looper
import android.view.View
import mihon.domain.reader.AdaptiveReaderLayout

/** Android lifecycle adapter; the shared policy sees only the laid-out content viewport. */
internal class AdaptiveReaderViewport(
    private val view: View,
    restoredDualPage: Boolean? = null,
    private val onLayoutChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var initialized = restoredDualPage != null
    var dualPage = restoredDualPage ?: false
        private set
    private var pending: Boolean? = null
    private val applyPending = Runnable {
        val target = pending ?: return@Runnable
        pending = null
        dualPage = target
        onLayoutChanged(target)
    }
    private val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> detect() }

    init {
        view.addOnLayoutChangeListener(listener)
    }

    fun configure(automatic: Boolean): Boolean {
        if (enabled == automatic) return dualPage
        enabled = automatic
        cancelPending()
        if (automatic) {
            policy(if (initialized) dualPage else null)?.let {
                dualPage = it
                initialized = true
            }
        } else {
            initialized = false
        }
        return dualPage
    }

    private fun policy(current: Boolean?) = AdaptiveReaderLayout.dualPage(
        view.width - view.paddingLeft - view.paddingRight,
        view.height - view.paddingTop - view.paddingBottom,
        current,
    )

    private fun detect() {
        if (!enabled) return
        val target = policy(if (initialized) dualPage else null)
        if (target == null || target == dualPage) {
            cancelPending()
            if (target != null) initialized = true
            return
        }
        if (!initialized) {
            initialized = true
            dualPage = target
            onLayoutChanged(target)
        } else if (pending != target) {
            cancelPending()
            pending = target
            handler.postDelayed(applyPending, AdaptiveReaderLayout.STABILITY_MILLIS)
        }
    }

    private fun cancelPending() {
        handler.removeCallbacks(applyPending)
        pending = null
    }

    override fun close() {
        enabled = false
        cancelPending()
        view.removeOnLayoutChangeListener(listener)
    }
}
