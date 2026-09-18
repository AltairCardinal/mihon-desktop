package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryDeliveryResult
import tachiyomi.domain.creator.service.CreatorDiscoveryOutboxWorker
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.RepositoryCreatorDiscoveryOutboxStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/** Android lifecycle adapter for the shared author-discovery executor. */
class CreatorDiscoveryJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val discovery: CreatorDiscoveryService = Injekt.get()
    private val archive: CreatorArchiveRepository = Injekt.get()

    override suspend fun doWork(): Result = runCatching {
        val result = discovery.discoverDueWatches()
        val delivered = CreatorDiscoveryOutboxWorker(
            RepositoryCreatorDiscoveryOutboxStore(archive),
            AndroidCreatorDiscoveryNotifier(applicationContext),
        ).deliverPending()
        when (result.runState) {
            DiscoveryRunState.FAILED -> Result.retry()
            else -> Result.success(
                workDataOf(
                    NEW_ITEMS to result.newCandidateCount,
                    ERROR_COUNT to result.errorCount,
                    DELIVERED to delivered.delivered,
                ),
            )
        }
    }.getOrElse { Result.retry() }

    companion object {
        private const val WORK_NAME = "creator-discovery"
        const val NEW_ITEMS = "new_items"
        const val ERROR_COUNT = "error_count"
        const val DELIVERED = "delivered"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CreatorDiscoveryJob>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CreatorDiscoveryJob>(12, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            context.workManager.enqueueUniquePeriodicWork(
                "$WORK_NAME-periodic",
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
