package eu.kanade.tachiyomi.extension.installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentSanitizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.lang.use
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.getUriSize
import logcat.LogPriority
import mihon.domain.extension.suggestion.SuggestionBatchPause
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class PackageInstallerInstaller(
    private val service: Service,
    private val isForeground: () -> Boolean = {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    },
) : Installer(service) {

    private val packageInstaller = service.packageManager.packageInstaller

    private val packageActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val transactionId = intent.getStringExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID) ?: return
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val active = activeSession.load()
            if (active?.entry?.transactionId != transactionId || active.sessionId != sessionId) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (active.awaitingForeground.get()) return
                    val userAction = try {
                        intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)?.let { candidate ->
                            candidate.action?.let { action ->
                                IntentSanitizer.Builder()
                                    .allowAction(action)
                                    .allowExtra(PackageInstaller.EXTRA_SESSION_ID) { id -> id == active.sessionId }
                                    .allowAnyComponent()
                                    // The platform installer package varies across devices.
                                    .allowPackage { true }
                                    .build()
                                    .sanitizeByFiltering(candidate)
                            }
                        }
                    } catch (failure: Exception) {
                        logcat(LogPriority.ERROR, failure) { "Invalid extension installation confirmation" }
                        null
                    }
                    if (userAction == null) {
                        logcat(LogPriority.ERROR) { "Fatal error for $intent" }
                        awaitForeground(active)
                        return
                    }
                    if (!isForeground()) {
                        awaitForeground(active)
                        return
                    }
                    try {
                        userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(userAction)
                    } catch (failure: Exception) {
                        logcat(LogPriority.ERROR, failure) { "Unable to show extension installation confirmation" }
                        awaitForeground(active)
                    }
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    if (!active.awaitingForeground.get()) finishSession(active, InstallStep.Idle)
                }
                PackageInstaller.STATUS_SUCCESS -> finishSession(active, InstallStep.Installed)
                else -> if (!active.awaitingForeground.get()) finishSession(active, InstallStep.Error)
            }
        }
    }

    private val activeSession = AtomicReference<ActiveSession?>(null)
    private val receiverRegistered = AtomicBoolean(true)
    private val sessionCallbackRegistered = AtomicBoolean(true)
    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) = Unit
        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = Unit

        override fun onFinished(sessionId: Int, success: Boolean) {
            val active = activeSession.load()?.takeIf { it.sessionId == sessionId } ?: return
            active.finished.compareAndSet(null, success)
            if (active.awaitingForeground.get()) finishForegroundRecovery(active)
        }
    }

    private fun awaitForeground(active: ActiveSession) {
        if (!active.awaitingForeground.compareAndSet(false, true)) return
        interruption = SuggestionBatchPause.APP_FOREGROUND
        ready = false
        if (active.finished.load() != null) {
            finishForegroundRecovery(active)
            return
        }
        try {
            packageInstaller.abandonSession(active.sessionId)
        } catch (failure: Exception) {
            // Abandon failure is not proof of completion. Keep ownership until an actual terminal callback.
            logcat(LogPriority.ERROR, failure) { "Unable to abandon extension installation session" }
        }
    }

    private fun finishForegroundRecovery(active: ActiveSession) {
        val success = active.finished.load() ?: return
        finishSession(active, if (success) InstallStep.Installed else InstallStep.Error)
    }

    // Always ready
    override var ready = true

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        activeSession.store(null)
        try {
            val installParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val active = ActiveSession(entry, packageInstaller.createSession(installParams))
            activeSession.store(active)
            val fileSize = service.getUriSize(entry.uri) ?: throw IllegalStateException()
            installParams.setSize(fileSize)

            val inputStream = service.contentResolver.openInputStream(entry.uri) ?: throw IllegalStateException()
            val session = packageInstaller.openSession(active.sessionId)
            val outputStream = session.openWrite(entry.downloadId.toString(), 0, fileSize)
            session.use {
                arrayOf(inputStream, outputStream).use {
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
                service.contentResolver.delete(entry.uri, null, null)

                val intentSender = PendingIntent.getBroadcast(
                    service,
                    active.sessionId,
                    Intent(INSTALL_ACTION)
                        .setPackage(service.packageName)
                        .putExtra(ExtensionInstaller.EXTRA_TRANSACTION_ID, entry.transactionId)
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, active.sessionId),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
                ).intentSender
                @SuppressLint("RequestInstallPackagesPolicy")
                session.commit(intentSender)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install extension ${entry.downloadId} ${entry.uri}" }
            activeSession.exchange(null)?.let { packageInstaller.abandonSession(it.sessionId) }
            continueQueue(entry.transactionId, InstallStep.Error)
        }
    }

    override fun cancelEntry(entry: Entry): Boolean {
        val active = activeSession.load()
        if (active?.entry == entry && active.awaitingForeground.get()) {
            // The existing cancellation acknowledgement completes after onFinished and service cleanup.
            return false
        }
        if (active?.entry == entry && activeSession.compareAndSet(active, null)) {
            packageInstaller.abandonSession(active.sessionId)
        }
        return true
    }

    override fun onDestroy() {
        activeSession.exchange(null)?.let { packageInstaller.abandonSession(it.sessionId) }
        unregisterPackageReceiver()
        super.onDestroy()
    }

    override fun onCancellationCleanup() {
        unregisterPackageReceiver()
    }

    private fun finishSession(active: ActiveSession, step: InstallStep) {
        if (activeSession.compareAndSet(active, null)) {
            continueQueue(active.entry.transactionId, step)
            if (active.awaitingForeground.get()) service.stopSelf()
        }
    }

    private fun unregisterPackageReceiver() {
        if (receiverRegistered.compareAndSet(true, false)) {
            service.unregisterReceiver(packageActionReceiver)
        }
        if (sessionCallbackRegistered.compareAndSet(true, false)) {
            packageInstaller.unregisterSessionCallback(sessionCallback)
        }
    }

    private data class ActiveSession(
        val entry: Entry,
        val sessionId: Int,
        val awaitingForeground: AtomicBoolean = AtomicBoolean(false),
        val finished: AtomicReference<Boolean?> = AtomicReference(null),
    )

    init {
        packageInstaller.registerSessionCallback(sessionCallback)
        ContextCompat.registerReceiver(
            service,
            packageActionReceiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

private const val INSTALL_ACTION = "PackageInstallerInstaller.INSTALL_ACTION"
