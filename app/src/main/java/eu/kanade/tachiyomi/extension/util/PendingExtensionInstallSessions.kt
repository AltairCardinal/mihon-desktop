package eu.kanade.tachiyomi.extension.util

import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionSystemWindowLease

/** Adopt exclusion only for this process's initial platform snapshot. Never resubmit a session. */
internal class PendingExtensionInstallSessions(
    private val platform: PackageInstaller,
    private val installerPackage: String,
    private val arbiter: ExtensionInstallArbiter,
    private val refreshInventory: () -> Job,
) {
    val hasUnknownSessions: Boolean get() = synchronized(lock) { sessions.containsValue(null) }
    private val lock = Any()
    private val sessions = mutableMapOf<Int, String?>()
    private val owners = mutableMapOf<String, ExtensionSystemWindowLease>()
    private val completed = mutableSetOf<Int>()
    private var takingSnapshot = true
    private var registered = true
    private val callback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) = Unit
        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = Unit
        override fun onFinished(sessionId: Int, success: Boolean) = finish(sessionId)
    }

    fun start() {
        if (platform.mySessions.isEmpty()) return
        platform.registerSessionCallback(callback, Handler(Looper.getMainLooper()))
        try {
            platform.mySessions.filter { it.installerPackageName == installerPackage }.forEach { session ->
                val packageName = session.appPackageName?.takeIf { it.isNotBlank() }
                synchronized(lock) {
                    if (session.sessionId !in completed) {
                        if (packageName == null) {
                            sessions[session.sessionId] = null
                        } else {
                            val owner = owners[packageName] ?: arbiter.reserveSystemWindow(packageName)
                            if (owner != null) {
                                owners[packageName] = owner
                                sessions[session.sessionId] = packageName
                            }
                        }
                    }
                }
                // The terminal callback may be queued behind startup; absence closes that race.
                if (platform.getSessionInfo(session.sessionId) == null) finish(session.sessionId)
            }
        } finally {
            synchronized(lock) { takingSnapshot = false }
            unregisterIfDone()
        }
    }

    private fun finish(id: Int) {
        val completion = synchronized(lock) {
            if (!completed.add(id) || !sessions.containsKey(id)) return
            val packageName = sessions.remove(id)
            val owner = packageName?.takeIf { it !in sessions.values }?.let(owners::remove)
            Triple(owner, packageName == null, takingSnapshot)
        }
        val (owner, unknown, duringSnapshot) = completion
        if (duringSnapshot) {
            owner?.let(arbiter::releaseSystemWindow)
        } else if (owner != null || unknown) {
            refreshInventory().invokeOnCompletion { owner?.let(arbiter::releaseSystemWindow) }
        }
        unregisterIfDone()
    }

    private fun unregisterIfDone() {
        val unregister = synchronized(lock) {
            if (takingSnapshot || sessions.isNotEmpty() || !registered) {
                false
            } else {
                registered = false
                true
            }
        }
        if (unregister) platform.unregisterSessionCallback(callback)
    }
}
