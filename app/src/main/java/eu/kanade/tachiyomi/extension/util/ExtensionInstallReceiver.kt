package eu.kanade.tachiyomi.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Broadcast receiver that listens for the system's packages installed, updated or removed, and only
 * notifies the given [listener] when the package is an extension.
 *
 * @param listener The listener that should be notified of extension installation events.
 */
internal class ExtensionInstallReceiver(private val listener: Listener) : BroadcastReceiver() {

    val scope = CoroutineScope(SupervisorJob())
    private val pendingLoads = mutableMapOf<String, Any>()

    fun register(context: Context) {
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(ACTION_EXTENSION_ADDED)
        addAction(ACTION_EXTENSION_REPLACED)
        addAction(ACTION_EXTENSION_REMOVED)
        addDataScheme("package")
    }

    /**
     * Called when one of the events of the [filter] is received. When the package is an extension,
     * it's loaded in background and it notifies the [listener] when finished.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED, ACTION_EXTENSION_ADDED -> {
                if (isReplacing(intent)) return

                loadCurrentExtension(context, intent, replacing = false)
            }
            Intent.ACTION_PACKAGE_REPLACED, ACTION_EXTENSION_REPLACED -> {
                loadCurrentExtension(context, intent, replacing = true)
            }
            Intent.ACTION_PACKAGE_REMOVED, ACTION_EXTENSION_REMOVED -> {
                if (isReplacing(intent)) return

                val pkgName = getPackageNameFromIntent(intent)
                if (pkgName != null) {
                    synchronized(pendingLoads) {
                        pendingLoads.remove(pkgName)
                        listener.onPackageUninstalled(pkgName)
                    }
                }
            }
        }
    }

    private fun loadCurrentExtension(context: Context, intent: Intent, replacing: Boolean) {
        val pkgName = getPackageNameFromIntent(intent) ?: return
        val token = Any()
        synchronized(pendingLoads) { pendingLoads[pkgName] = token }
        scope.launch {
            try {
                val result = getExtensionFromIntent(context, intent)
                synchronized(pendingLoads) {
                    // A later removal or replacement supersedes this asynchronous loader.
                    // Keep validation and notification atomic with the removal callback.
                    if (pendingLoads[pkgName] !== token) return@synchronized
                    when (result) {
                        is LoadResult.Success -> if (replacing) {
                            listener.onExtensionUpdated(result.extension)
                        } else {
                            listener.onExtensionInstalled(result.extension)
                        }
                        is LoadResult.Untrusted -> listener.onExtensionUntrusted(result.extension)
                        else -> {}
                    }
                    listener.onPackageChanged(pkgName)
                }
            } finally {
                synchronized(pendingLoads) {
                    if (pendingLoads[pkgName] === token) pendingLoads.remove(pkgName)
                }
            }
        }
    }

    /**
     * Returns true if this package is performing an update.
     *
     * @param intent The intent that triggered the event.
     */
    private fun isReplacing(intent: Intent): Boolean {
        return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
    }

    /**
     * Returns the extension triggered by the given intent.
     *
     * @param context The application context.
     * @param intent The intent containing the package name of the extension.
     */
    private suspend fun getExtensionFromIntent(context: Context, intent: Intent?): LoadResult {
        val pkgName = getPackageNameFromIntent(intent)
        if (pkgName == null) {
            logcat(LogPriority.WARN) { "Package name not found" }
            return LoadResult.Error
        }
        return ExtensionLoader.loadExtensionFromPkgName(context, pkgName)
    }

    /**
     * Returns the package name of the installed, updated or removed application.
     */
    private fun getPackageNameFromIntent(intent: Intent?): String? {
        return intent?.data?.encodedSchemeSpecificPart ?: return null
    }

    /**
     * Listener that receives extension installation events.
     */
    interface Listener {
        fun onPackageChanged(pkgName: String) {}
        fun onExtensionInstalled(extension: Extension.Installed)
        fun onExtensionUpdated(extension: Extension.Installed)
        fun onExtensionUntrusted(extension: Extension.Untrusted)
        fun onPackageUninstalled(pkgName: String)
    }

    companion object {
        private const val ACTION_EXTENSION_ADDED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_ADDED"
        private const val ACTION_EXTENSION_REPLACED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REPLACED"
        private const val ACTION_EXTENSION_REMOVED = "${BuildConfig.APPLICATION_ID}.ACTION_EXTENSION_REMOVED"

        fun notifyAdded(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_ADDED)
        }

        fun notifyReplaced(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REPLACED)
        }

        fun notifyRemoved(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REMOVED)
        }

        private fun notify(context: Context, pkgName: String, action: String) {
            Intent(action).apply {
                data = "package:$pkgName".toUri()
                `package` = context.packageName
                context.sendBroadcast(this)
            }
        }
    }
}
