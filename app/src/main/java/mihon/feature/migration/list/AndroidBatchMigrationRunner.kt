package mihon.feature.migration.list

import kotlinx.coroutines.flow.Flow
import mihon.domain.migration.BatchMigrationEvent
import mihon.domain.migration.BatchMigrationOrchestrator

class AndroidBatchMigrationRunner<T>(
    private val orchestrator: BatchMigrationOrchestrator<T> = BatchMigrationOrchestrator(),
) {
    fun run(
        items: List<T>,
        startIndex: Int = 0,
        migrate: suspend (T) -> Any?,
    ): Flow<BatchMigrationEvent<T>> = orchestrator.run(items, startIndex, migrate)
}
