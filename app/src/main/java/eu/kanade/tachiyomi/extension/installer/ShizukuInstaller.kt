package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.shizuku.IShellInterface
import mihon.app.shizuku.ShellInterface
import mihon.app.shizuku.ShizukuSessionState
import mihon.domain.extension.suggestion.SuggestionBatchPause
import rikka.shizuku.Shizuku
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class ShizukuInstaller(private val service: Service) : Installer(service) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extensionManager: ExtensionManager by injectLazy()

    private var shellInterface: IShellInterface? = null
    private val activeSession = AtomicReference<ActiveSession?>(null)

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(service, ShellInterface::class.java),
        )
            .tag("shizuku_service")
            .version(3)
            .processNameSuffix("shizuku_service")
            .debuggable(BuildConfig.DEBUG)
            .daemon(false)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellInterface = IShellInterface.Stub.asInterface(service)
            val active = activeSession.load()
            if (active != null) {
                ready = false
                recover(active)
                return
            }
            ready = true
            checkQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellInterface = null
            interrupt(SuggestionBatchPause.SERVICE)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val transactionId = intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) ?: return
            val active = activeSession.load() ?: return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            if (active.entry.transactionId != transactionId ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != active.sessionId ||
                status == Int.MIN_VALUE ||
                status == PackageInstaller.STATUS_PENDING_USER_ACTION
            ) {
                return
            }
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

            if (status == PackageInstaller.STATUS_SUCCESS) {
                finish(active, InstallStep.Installed)
            } else {
                logcat(LogPriority.ERROR) { "Failed to install extension $packageName: $message" }
                finish(active, InstallStep.Error)
            }
        }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        logcat { "Shizuku was killed prematurely" }
        interrupt(SuggestionBatchPause.SERVICE)
    }

    private val shizukuReceivedListener = Shizuku.OnBinderReceivedListener {
        if (activeSession.load() != null) initShizuku()
    }

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (activeSession.load() != null) initShizuku()
        }
    }

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    checkQueue()
                    Shizuku.bindUserService(shizukuArgs, connection)
                } else {
                    interrupt(SuggestionBatchPause.PERMISSION)
                }
                Shizuku.removeRequestPermissionResultListener(this)
            }
        }
    }

    fun initShizuku() {
        if (ready && activeSession.load() == null) return
        if (!Shizuku.pingBinder()) {
            interrupt(SuggestionBatchPause.SERVICE)
            logcat(LogPriority.ERROR) { "Shizuku is not ready to use" }
            service.toast(MR.strings.ext_installer_shizuku_stopped)
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.bindUserService(shizukuArgs, connection)
        } else if (getActiveEntry() != null) {
            interrupt(SuggestionBatchPause.PERMISSION)
        } else {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    override var ready = false

    private fun interrupt(reason: SuggestionBatchPause) {
        interruption = reason
        ready = false
        // Losing the bridge is not an installation result. The package manager can still complete
        // the submitted session and deliver its original transaction callback to this service.
        val active = getActiveEntry()
        if (active == null) {
            service.stopSelf()
        } else {
            extensionManager.reportPendingInstallPause(active.transactionId, reason)
        }
    }

    private fun recover(active: ActiveSession) {
        if (!Shizuku.pingBinder()) {
            interrupt(SuggestionBatchPause.SERVICE)
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            interrupt(SuggestionBatchPause.PERMISSION)
            return
        }
        val remote = shellInterface ?: return
        scope.launch {
            try {
                when (remote.sessionState(active.sessionId, active.entry.transactionId)) {
                    ShizukuSessionState.PREPARED -> remote.abandon(active.sessionId, active.entry.transactionId)
                    ShizukuSessionState.SUBMITTED ->
                        extensionManager.reportPendingInstallPause(
                            active.entry.transactionId,
                            null,
                        )
                    ShizukuSessionState.SUCCEEDED -> finish(active, InstallStep.Installed)
                    ShizukuSessionState.FAILED -> finish(active, InstallStep.Error)
                    ShizukuSessionState.MISSING -> {
                        if (extensionManager.verifyInstalledTransaction(active.entry.transactionId)) {
                            finish(active, InstallStep.Installed)
                        }
                    }
                }
            } catch (failure: Exception) {
                logcat(LogPriority.WARN, failure) { "Unable to resolve original Shizuku installation session" }
                if (activeSession.load() === active) {
                    interrupt(
                        if (failure is SecurityException) {
                            SuggestionBatchPause.PERMISSION
                        } else {
                            SuggestionBatchPause.SERVICE
                        },
                    )
                }
            }
        }
    }

    private fun finish(active: ActiveSession, step: InstallStep) {
        if (!activeSession.compareAndSet(active, null)) return
        extensionManager.reportPendingInstallPause(active.entry.transactionId, null)
        continueQueue(active.entry.transactionId, step)
        if (!ready) service.stopSelf()
    }

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        try {
            service.contentResolver.openAssetFileDescriptor(entry.uri, "r").use {
                val remote = checkNotNull(shellInterface)
                val sessionId = remote.prepare(checkNotNull(it), entry.transactionId)
                activeSession.store(ActiveSession(entry, sessionId))
                if (!ready) return
                if (!Shizuku.pingBinder()) {
                    interrupt(SuggestionBatchPause.SERVICE)
                    return
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    interrupt(SuggestionBatchPause.PERMISSION)
                    return
                }
                remote.commit(sessionId, entry.transactionId)
            }
            service.contentResolver.delete(entry.uri, null, null)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install extension ${entry.downloadId} ${entry.uri}" }
            if (activeSession.load()?.entry == entry) {
                interrupt(if (e is SecurityException) SuggestionBatchPause.PERMISSION else SuggestionBatchPause.SERVICE)
            } else {
                if (e is SecurityException || e is android.os.RemoteException) {
                    interrupt(
                        if (e is SecurityException) {
                            SuggestionBatchPause.PERMISSION
                        } else {
                            SuggestionBatchPause.SERVICE
                        },
                    )
                }
                continueQueue(entry.transactionId, InstallStep.Error)
                if (!ready) service.stopSelf()
            }
        }
    }

    private data class ActiveSession(val entry: Entry, val sessionId: Int)

    // Don't cancel if entry is already started installing
    override fun cancelEntry(entry: Entry): Boolean = getActiveEntry() != entry

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeBinderReceivedListener(shizukuReceivedListener)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(shizukuArgs, connection, true)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to unbind shizuku service" }
            }
        }
        service.unregisterReceiver(receiver)
        logcat { "ShizukuInstaller destroy" }
        scope.cancel()
        super.onDestroy()
    }

    init {
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addBinderReceivedListener(shizukuReceivedListener)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)

        ContextCompat.registerReceiver(
            service,
            receiver,
            IntentFilter(ACTION_INSTALL_RESULT).apply { addDataScheme("mihon-shizuku-result") },
            ContextCompat.RECEIVER_EXPORTED,
        )

        initShizuku()
    }
}

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
const val ACTION_INSTALL_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_INSTALL_RESULT"
