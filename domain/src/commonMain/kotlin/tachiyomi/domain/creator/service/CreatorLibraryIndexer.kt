package tachiyomi.domain.creator.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource

sealed interface CreatorLibraryIndexState {
    data object Idle : CreatorLibraryIndexState

    data class Indexing(
        val processedManga: Int,
        val totalManga: Int,
    ) : CreatorLibraryIndexState

    data class Ready(val indexedManga: Int) : CreatorLibraryIndexState

    data object Empty : CreatorLibraryIndexState

    data class Failed(
        val processedManga: Int,
        val totalManga: Int,
        val message: String,
    ) : CreatorLibraryIndexState
}

/**
 * Performs one idempotent, keyset-paged library backfill. Subsequent manga membership and
 * bibliography writes are reconciled in the MangaRepository transaction rather than inferred from
 * an in-memory Flow history. Platform code owns the lifecycle [CoroutineScope].
 */
class CreatorLibraryIndexer(
    private val mangaSource: CreatorLibraryMangaSource,
    private val indexWriter: CreatorLibraryIndexWriter,
    private val extractCreators: ExtractCreatorsFromManga,
    private val batchSize: Long = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0L) { "Creator index batch size must be positive" }
    }

    private val mutableState = MutableStateFlow<CreatorLibraryIndexState>(CreatorLibraryIndexState.Idle)
    val state: StateFlow<CreatorLibraryIndexState> = mutableState.asStateFlow()

    private var lifecycleScope: CoroutineScope? = null
    private var collectionJob: Job? = null
    private var processedManga = 0
    private var totalManga = 0

    @Synchronized
    fun start(scope: CoroutineScope) {
        lifecycleScope = scope
        if (collectionJob?.isActive == true) return
        collectionJob = launchCollection(scope)
    }

    @Synchronized
    fun retry() {
        val scope = lifecycleScope ?: return
        collectionJob?.cancel()
        collectionJob = launchCollection(scope)
    }

    @Synchronized
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    private fun launchCollection(scope: CoroutineScope): Job = scope.launch {
        try {
            backfill()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = CreatorLibraryIndexState.Failed(
                processedManga = processedManga,
                totalManga = totalManga,
                message = failure.message ?: failure::class.simpleName.orEmpty(),
            )
        }
    }

    private suspend fun backfill() {
        processedManga = 0
        totalManga = mangaSource.countLibraryMangaForCreatorIndex().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        mutableState.value = CreatorLibraryIndexState.Indexing(0, totalManga)
        var afterId = Long.MIN_VALUE
        while (true) {
            val page = mangaSource.getLibraryMangaForCreatorIndex(afterId, batchSize)
            if (page.isEmpty()) break
            check(page.zipWithNext().all { (left, right) -> left.id < right.id }) {
                "Creator index keyset page must be strictly ordered by manga id"
            }
            check(page.first().id > afterId) { "Creator index keyset page did not advance" }
            indexWriter.indexLibraryMangaBatch(
                page.map { manga -> CreatorLibraryIndexEntry(manga, extractCreators.await(manga)) },
            )
            processedManga += page.size
            mutableState.value = CreatorLibraryIndexState.Indexing(processedManga, totalManga)
            afterId = page.last().id
            if (page.size < batchSize) break
        }
        indexWriter.removeStaleLibraryMangaIndexes()
        mutableState.value = if (totalManga == 0) {
            CreatorLibraryIndexState.Empty
        } else {
            CreatorLibraryIndexState.Ready(processedManga)
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 250L
    }
}
