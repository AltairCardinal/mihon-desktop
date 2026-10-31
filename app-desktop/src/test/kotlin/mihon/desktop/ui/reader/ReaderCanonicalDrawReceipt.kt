package mihon.desktop.ui.reader

import mihon.domain.reader.ReaderDualPageFrameId
import mihon.domain.reader.ReaderDualPageUnit

/** Immutable test-side copy of one accepted production draw for one real frame ticket. */
internal data class ReaderSmokeDrawReceipt(
    val runtimeEpoch: Long,
    val generation: Long,
    val locationSequence: Long,
    val ticketToken: Long,
    val frameId: ReaderDualPageFrameId,
    val unitPageIndexes: Set<Int>,
    val drawnPageIndexes: Set<Int>,
    val sourcePageIndex: Int,
    val monotonicNanos: Long,
    val unitLayout: ReaderSmokeUnitLayout? = null,
)

/** Test-side value projection of the complete production unit; no pairing rules are implemented here. */
internal data class ReaderSmokeUnitLayout(
    val slots: List<ReaderSmokeUnitSlot>,
    val certain: Boolean,
    val reason: String?,
)

internal data class ReaderSmokeUnitSlot(val sourcePageIndex: Int, val position: String)

internal fun readerSmokeUnitLayout(unit: ReaderDualPageUnit) = ReaderSmokeUnitLayout(
    slots = unit.slots.map { ReaderSmokeUnitSlot(it.pageId.sourcePageIndex, it.position.name) },
    certain = unit.certain,
    reason = unit.singleReason?.name,
)

internal fun ReaderSmokeUnitLayout.toJson(): String = buildString {
    append("{\"slots\":[")
    slots.forEachIndexed { index, slot ->
        if (index > 0) append(',')
        append("{\"sourcePageIndex\":${slot.sourcePageIndex},\"position\":\"${slot.position}\"}")
    }
    append("],\"certain\":$certain,\"reason\":")
    append(reason?.let { "\"$it\"" } ?: "null")
    append('}')
}

internal data class ReaderSmokeLatency(
    val runtimeEpoch: Long,
    val generation: Long,
    val locationSequence: Long,
    val sourcePageIndex: Int,
    val frameId: ReaderDualPageFrameId,
    val ticketToken: Long,
    val elapsedNanos: Long,
)

internal fun consumeReaderSmokeLatency(
    startedAtNanos: Long,
    runtimeEpoch: Long,
    generation: Long,
    locationSequence: Long,
    sourcePageIndex: Int,
    receipts: List<ReaderSmokeDrawReceipt>,
): ReaderSmokeLatency? {
    val receipt = receipts
        .asSequence()
        .filter {
            it.runtimeEpoch == runtimeEpoch &&
                it.generation == generation &&
                it.locationSequence == locationSequence &&
                it.sourcePageIndex == sourcePageIndex &&
                it.sourcePageIndex in it.drawnPageIndexes &&
                it.monotonicNanos >= startedAtNanos
        }
        .minByOrNull(ReaderSmokeDrawReceipt::monotonicNanos) ?: return null
    return ReaderSmokeLatency(
        runtimeEpoch = receipt.runtimeEpoch,
        generation = receipt.generation,
        locationSequence = receipt.locationSequence,
        sourcePageIndex = receipt.sourcePageIndex,
        frameId = receipt.frameId,
        ticketToken = receipt.ticketToken,
        elapsedNanos = receipt.monotonicNanos - startedAtNanos,
    )
}

/** Selects the first single-frame snapshot containing the complete unit on the target ticket. */
internal fun consumeExpectedCompleteReaderSmokeFrameLatency(
    startedAtNanos: Long,
    runtimeEpoch: Long,
    generation: Long,
    locationSequence: Long,
    expectedUnitPageIndexes: Set<Int>,
    receipts: List<ReaderSmokeDrawReceipt>,
    expectedLayout: ReaderSmokeUnitLayout? = null,
): ReaderSmokeLatency? {
    if (expectedUnitPageIndexes.isEmpty()) return null
    val receipt = receipts.asSequence().filter {
        it.monotonicNanos >= startedAtNanos &&
            it.runtimeEpoch == runtimeEpoch &&
            it.generation == generation &&
            it.locationSequence == locationSequence &&
            it.unitPageIndexes == expectedUnitPageIndexes &&
            (expectedLayout == null || it.unitLayout == expectedLayout) &&
            it.sourcePageIndex in expectedUnitPageIndexes &&
            it.sourcePageIndex in it.drawnPageIndexes &&
            it.drawnPageIndexes.containsAll(expectedUnitPageIndexes)
    }.minByOrNull(ReaderSmokeDrawReceipt::monotonicNanos) ?: return null
    return ReaderSmokeLatency(
        runtimeEpoch = receipt.runtimeEpoch,
        generation = receipt.generation,
        locationSequence = receipt.locationSequence,
        sourcePageIndex = receipt.sourcePageIndex,
        frameId = receipt.frameId,
        ticketToken = receipt.ticketToken,
        elapsedNanos = receipt.monotonicNanos - startedAtNanos,
    )
}
