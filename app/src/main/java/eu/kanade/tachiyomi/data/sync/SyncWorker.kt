package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val result = Injekt.get<SyncRuntime>().coordinator.synchronize(SyncTrigger.PERIODIC)
        return when {
            result.status != SyncRunStatus.FAILED -> Result.success()
            result.problem == SyncRunProblem.NETWORK && runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
