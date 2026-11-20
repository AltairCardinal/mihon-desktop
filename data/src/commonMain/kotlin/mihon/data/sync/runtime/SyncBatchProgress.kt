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
    val workflowFraction: Double? = null,
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
        val direction: SyncProgressDirection,
        var confirmed: Boolean,
        var weighted: Double,
    )
    private val batches = linkedMapOf<Key, Work>()
    private var active: Key? = null
    private var uploadGroup = emptyList<Key>()
    private var work = SyncBatchWork.PREPARING
    private var processedRunItems = 0L
    private var plannedRunItems = 0L
    private var workflowItems = 0.0

    fun plan(plan: SyncRunPlan) {
        if (batches.isNotEmpty()) return
        plan.batches.forEach {
            val done = if (it.confirmed) it.itemCount else 0L
            batches[Key(it.direction, it.batchId)] = Work(
                batches.size + 1, it.itemCount, done, done, done, done,
                it.direction, it.confirmed, done.toDouble(),
            )
            processedRunItems += done
            workflowItems += done
            plannedRunItems += it.itemCount
        }
    }

    fun start(direction: SyncProgressDirection, batchId: String, work: SyncBatchWork) {
        val key = Key(direction, batchId)
        uploadGroup = emptyList()
        active = key.takeIf { it in batches }
        this.work = work
    }

    fun startUploadTransfer(batchIds: Collection<String>) {
        uploadGroup = batchIds.map { Key(SyncProgressDirection.UPLOAD, it) }.filter { it in batches }
        uploadGroup.forEach { finish(batches.getValue(it), successful = true) }
        active = uploadGroup.firstOrNull()
        work = SyncBatchWork.TRANSFERRING
    }

    fun items(count: Long) {
        val batch = active?.let(batches::get) ?: return
        require(count in 0..batch.total)
        val next = maxOf(batch.processed, count)
        batch.processed = next
        if (active?.direction == SyncProgressDirection.DOWNLOAD) advance(batch, next)
        updateWorkflow(batch)
    }

    fun transfer(workKey: String?, offset: Long, total: Long?) {
        val key = active ?: return
        if (key.direction != SyncProgressDirection.UPLOAD || total == null || total <= 0 || workKey == null) return
        val fraction = offset.toDouble().div(total).coerceIn(0.0, 1.0)
        if (uploadGroup.size <= 1) {
            if (workKey.startsWith("${key.id}:")) {
                val batch = batches.getValue(key)
                advance(batch, (fraction * batch.total).toLong())
            }
        } else if (workKey.startsWith("${key.id}:tree:")) {
            // A single tree body can carry the encrypted contents of every member.
            var processed = (fraction * uploadGroup.sumOf { batches.getValue(it).total }).toLong()
            for (member in uploadGroup) {
                val batch = batches.getValue(member)
                val count = minOf(processed, batch.total)
                advance(batch, count)
                processed -= count
            }
        } else {
            val member = uploadGroup.firstOrNull { workKey.startsWith("${it.id}:batch-payload:") } ?: return
            val batch = batches.getValue(member)
            advance(batch, (fraction * batch.total).toLong())
        }
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
        updateWorkflow(batch)
    }

    private fun updateWorkflow(batch: Work) {
        val weighted = when {
            batch.confirmed -> batch.total.toDouble()
            batch.direction == SyncProgressDirection.DOWNLOAD -> batch.effective * 0.55
            else -> batch.processed * 0.15 + batch.effective * 0.60
        }
        workflowItems += weighted - batch.weighted
        batch.weighted = weighted
    }

    fun finish(successful: Boolean) {
        val key = active ?: return
        if (!successful && uploadGroup.size > 1) {
            uploadGroup.forEach { finish(batches.getValue(it), successful = false) }
        } else {
            finish(batches.getValue(key), successful)
        }
    }

    private fun finish(batch: Work, successful: Boolean) {
        if (successful) {
            batch.committed = batch.processed
            batch.committedEffective = batch.effective
        } else {
            processedRunItems += batch.committedEffective - batch.effective
            batch.processed = batch.committed
            batch.effective = batch.committedEffective
        }
        updateWorkflow(batch)
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
        batch.confirmed = true
        updateWorkflow(batch)
    }

    fun snapshot(): SyncBatchProgress? {
        val key = active ?: return null
        val batch = batches.getValue(key)
        return SyncBatchProgress(
            key.id, key.direction, batch.ordinal, batches.size,
            batch.processed, batch.total, processedRunItems, plannedRunItems, work,
            if (plannedRunItems > 0) (workflowItems / plannedRunItems).coerceIn(0.0, 1.0) else 1.0,
        )
    }
}
