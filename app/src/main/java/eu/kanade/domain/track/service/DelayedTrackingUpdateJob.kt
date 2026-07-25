package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.service.DelayedTrackerSyncQueue
import tachiyomi.domain.track.service.DelayedTrackerSyncReport
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val trackChapter = Injekt.get<TrackChapter>()
        return DelayedTrackingWorkerRunner(
            drain = { withIOContext { trackChapter.drainDelayed() } },
            markRetryExhausted = { withIOContext { trackChapter.markRetryExhausted() } },
        ).run(runAttemptCount)
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            setupTask { name, policy, request ->
                context.workManager.enqueueUniqueWork(name, policy, request)
                request
            }
        }

        internal fun setupTask(
            enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> OneTimeWorkRequest,
        ): OneTimeWorkRequest {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            return enqueue(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        internal fun isRetryExhausted(runAttemptCount: Int) =
            runAttemptCount > DelayedTrackerSyncQueue.MAX_RUN_ATTEMPT_COUNT
    }
}

internal class DelayedTrackingWorkerRunner(
    private val drain: suspend () -> DelayedTrackerSyncReport,
    private val markRetryExhausted: suspend () -> Unit,
) {
    suspend fun run(runAttemptCount: Int): ListenableWorker.Result {
        if (DelayedTrackingUpdateJob.isRetryExhausted(runAttemptCount)) {
            markRetryExhausted()
            return ListenableWorker.Result.failure()
        }
        return if (drain().remaining == 0) {
            ListenableWorker.Result.success()
        } else {
            ListenableWorker.Result.retry()
        }
    }
}
