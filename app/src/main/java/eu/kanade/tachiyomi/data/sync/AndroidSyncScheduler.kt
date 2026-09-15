package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.runtime.SyncPreferences
import mihon.domain.sync.runtime.SyncTrigger
import java.util.concurrent.TimeUnit

class AndroidSyncScheduler(private val context: Context, private val runtime: SyncRuntime) {
    private var running: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        running?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO) {
            launch {
                runtime.preferences.periodMinutes.changes()
                    .map { it.takeIf(SyncPreferences.intervals::contains) ?: 0 }
                    .distinctUntilChanged()
                    .collect(::schedule)
            }
            if (runtime.preferences.startup.get()) {
                launch { runtime.coordinator.synchronize(SyncTrigger.STARTUP) }
            }
        }.also { running = it }
    }

    private fun schedule(minutes: Int) {
        val manager = WorkManager.getInstance(context)
        if (minutes == 0) {
            manager.cancelUniqueWork(WORK_NAME)
        } else {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    companion object {
        const val WORK_NAME = "mihon-sync-periodic"
    }
}
