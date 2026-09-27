package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = Injekt.get<SyncRuntime>()
        val recovery = inputData.getBoolean(RECOVERY_KEY, false)
        val resumable = recovery && runtime.hasResumableRun()
        if (recovery && (!resumable || !runtime.isRecoveryDue())) {
            // WorkManager may wake this request before its persisted deadline (for
            // example after a constraint change). Keep the durable wake alive so a
            // premature wake cannot silently discard recovery.
            return if (resumable) Result.retry() else Result.success()
        }
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            // The task remains durable and the exchange reports its state; a foreground
            // promotion can be rejected by a test host or by an Android start restriction.
        }
        val result = if (recovery) {
            if (!runtime.resumeIfNeeded()) {
                mihon.domain.sync.runtime.SyncRunResult(SyncRunStatus.SKIPPED)
            } else {
                runtime.coordinator.activity.value.result
                    ?: mihon.domain.sync.runtime.SyncRunResult(SyncRunStatus.SKIPPED)
            }
        } else {
            runtime.coordinator.synchronize(SyncTrigger.PERIODIC)
        }
        return when {
            result.status == SyncRunStatus.SUCCESS || result.status == SyncRunStatus.SKIPPED ||
                (result.status == SyncRunStatus.PARTIAL && result.pending > 0 && result.problem == null) ->
                Result.success()
            // SyncRuntime persists the four-attempt business budget and schedules the
            // durable recovery wake. WorkManager must not add another retry budget.
            result.problem == SyncRunProblem.NETWORK -> Result.success()
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_COMMON) {
            setContentTitle(applicationContext.stringResource(MR.strings.sync_title))
            setContentText(applicationContext.stringResource(MR.strings.sync_busy))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setOngoing(true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_SYNC_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val RECOVERY_KEY = "sync_recovery"
    }
}
