package mihon.data.sync.runtime

enum class SyncBatchWork { PREPARING, WRITING, TRANSFERRING, CONFIRMING, MERGING }

/** Volatile work observations. Completed run items remain owned by the durable confirmation ledger. */
data class SyncBatchProgress(
    val batchId: String,
    val direction: SyncProgressDirection,
    val ordinal: Int,
    val batchCount: Int,
    val processedItems: Long,
    val batchItems: Long,
    val processedRunItems: Long,
    val plannedRunItems: Long,
    val work: SyncBatchWork,
)

internal class SyncBatchProgressTracker {
    private data class Key(val direction: SyncProgressDirection, val id: String)
    private data class Work(
        val ordinal: Int,
        val total: Long,
        var processed: Long,
        var committed: Long,
        var effective: Long,
        var committedEffective: Long,
    )
    private val batches = linkedMapOf<Key, Work>()
    private var active: Key? = null
    private var work = SyncBatchWork.PREPARING
    private var processedRunItems = 0L
    private var plannedRunItems = 0L

    fun plan(plan: SyncRunPlan) {
        if (batches.isNotEmpty()) return
        plan.batches.forEach {
            val done = if (it.confirmed) it.itemCount else 0L
            batches[Key(it.direction, it.batchId)] = Work(batches.size + 1, it.itemCount, done, done, done, done)
            processedRunItems += done
            plannedRunItems += it.itemCount
        }
    }

    fun start(direction: SyncProgressDirection, batchId: String, work: SyncBatchWork) {
        val key = Key(direction, batchId)
        active = key.takeIf { it in batches }
        this.work = work
    }

    fun items(count: Long) {
        val batch = active?.let(batches::get) ?: return
        require(count in 0..batch.total)
        val next = maxOf(batch.processed, count)
        batch.processed = next
        if (active?.direction == SyncProgressDirection.DOWNLOAD) advance(batch, next)
    }

    fun transfer(workKey: String?, offset: Long, total: Long?) {
        val key = active ?: return
        if (key.direction != SyncProgressDirection.UPLOAD || total == null || total <= 0 ||
            workKey?.startsWith("${key.id}:") != true
        ) {
            return
        }
        val batch = batches.getValue(key)
        advance(batch, (offset.toDouble() / total * batch.total).toLong().coerceIn(0, batch.total))
    }

    fun transferred(direction: SyncProgressDirection, batchId: String) {
        if (direction != SyncProgressDirection.UPLOAD) return
        val batch = batches[Key(direction, batchId)] ?: return
        advance(batch, batch.total)
    }

    private fun advance(batch: Work, count: Long) {
        val next = maxOf(batch.effective, count)
        processedRunItems += next - batch.effective
        batch.effective = next
    }

    fun finish(successful: Boolean) {
        val batch = active?.let(batches::get) ?: return
        if (successful) {
            batch.committed = batch.processed
            batch.committedEffective = batch.effective
        } else {
            processedRunItems += batch.committedEffective - batch.effective
            batch.processed = batch.committed
            batch.effective = batch.committedEffective
        }
    }

    fun phase(work: SyncBatchWork) {
        this.work = work
    }

    fun confirmed(direction: SyncProgressDirection, batchId: String) {
        val batch = batches[Key(direction, batchId)] ?: return
        processedRunItems += batch.total - batch.effective
        batch.processed = batch.total
        batch.committed = batch.total
        batch.effective = batch.total
        batch.committedEffective = batch.total
    }

    fun snapshot(): SyncBatchProgress? {
        val key = active ?: return null
        val batch = batches.getValue(key)
        return SyncBatchProgress(
            key.id, key.direction, batch.ordinal, batches.size,
            batch.processed, batch.total, processedRunItems, plannedRunItems, work,
        )
    }
}
