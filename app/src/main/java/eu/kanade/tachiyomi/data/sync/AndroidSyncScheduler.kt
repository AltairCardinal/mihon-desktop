package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.runtime.SyncPreferences
import mihon.domain.sync.runtime.SyncTrigger
import java.util.concurrent.TimeUnit

class AndroidSyncScheduler(private val context: Context, private val runtime: SyncRuntime) {
    private var running: Job? = null
    private var foregroundRecovery: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        running?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO) {
            launch {
                var observedCompletion = runtime.coordinator.activity.value.completion
                runtime.coordinator.activity
                    .map { it.completion to it.result }
                    .distinctUntilChanged()
                    .collect { (completion, result) ->
                        if (completion != observedCompletion) {
                            observedCompletion = completion
                            if (result?.problem == mihon.domain.sync.runtime.SyncRunProblem.NETWORK) {
                                enqueueRecovery(runtime.recoveryDelayMillis())
                            }
                        }
                    }
            }
            launch {
                val preferences = runtime.preferences
                combine(
                    preferences.periodMinutes.changes(),
                    preferences.scheduleAnchor.changes(),
                    preferences.lastAttempt.changes(),
                    preferences.lastSuccess.changes(),
                ) { minutes, anchor, attempt, success ->
                    val period = minutes.takeIf(SyncPreferences.intervals::contains) ?: 0
                    period to if (period == 0) {
                        0L
                    } else {
                        maxOf(anchor, attempt, success) +
                            TimeUnit.MINUTES.toMillis(period.toLong())
                    }
                }
                    .distinctUntilChanged()
                    .collect { (minutes, deadline) -> schedule(minutes, deadline) }
            }
            launch {
                val resumed = runtime.resumeIfNeeded()
                if (!resumed && runtime.preferences.startup.get()) {
                    runtime.coordinator.synchronize(SyncTrigger.STARTUP)
                }
                enqueueRecovery(runtime.recoveryDelayMillis())
            }
        }.also { running = it }
    }

    /** Called from ProcessLifecycleOwner when the app becomes interactive again. */
    @Synchronized
    fun resumeIfNeeded(scope: CoroutineScope): Job {
        foregroundRecovery?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO) {
            runtime.resumeIfNeeded()
        }.also { foregroundRecovery = it }
    }

    private fun enqueueRecovery(delayMillis: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECOVERY_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(androidx.work.workDataOf(SyncWorker.RECOVERY_KEY to true))
                .build(),
        )
    }

    private fun schedule(minutes: Int, deadline: Long) {
        val manager = WorkManager.getInstance(context)
        if (minutes == 0) {
            manager.cancelUniqueWork(WORK_NAME)
        } else {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setNextScheduleTimeOverride(deadline)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    companion object {
        const val WORK_NAME = "mihon-sync-periodic"
        const val RECOVERY_WORK_NAME = "mihon-sync-recovery"
    }
}
