package mihon.domain.sync

object SyncReceiver {
    fun pendingCancellation(
        reduction: SyncReduction,
        objectKey: SyncObjectKey,
        field: SyncField,
        localValue: Boolean,
    ): List<SyncCancellationRequest> {
        if (!localValue) return emptyList()
        val projection = reduction.projection(objectKey, field)
        if (projection.value != false || projection.pending) return emptyList()
        val cancellation =
            projection.heads
                .firstOrNull { projection.effectsByRef[it]?.kind == SyncEffectKind.REMOVE }
                ?: return emptyList()
        val binding = binding(projection)
        return listOf(
            SyncCancellationRequest(
                projection = projection,
                effectRef = cancellation,
                objectKey = objectKey,
                field = field,
                expectedHeads = projection.heads,
                binding = binding,
            ),
        )
    }

    fun decide(
        request: SyncCancellationRequest,
        decision: SyncCancellationDecision,
        current: SyncReduction,
    ): SyncReceiverDecision {
        val projection = current.projection(request.objectKey, request.field)
        if (binding(projection) != request.binding || projection.value != false || projection.pending) {
            return SyncReceiverDecision(
                result = SyncReceiverDecisionResult.INVALIDATED,
                decision = decision,
                localValue = true,
                binding = request.binding,
            )
        }
        return when (decision) {
            SyncCancellationDecision.CONFIRM -> {
                SyncReceiverDecision(
                    result = SyncReceiverDecisionResult.APPLIED,
                    decision = decision,
                    localValue = false,
                    binding = request.binding,
                )
            }

            SyncCancellationDecision.KEEP_LOCAL -> {
                SyncReceiverDecision(
                    result = SyncReceiverDecisionResult.KEPT_LOCAL,
                    decision = decision,
                    localValue = true,
                    binding = request.binding,
                )
            }
        }
    }

    private fun binding(projection: SyncProjection): String =
        buildString {
            appendPart(projection.key.objectKey.stableKey)
            appendPart(projection.key.field.name)
            projection.heads.sortedBy { it.stableKey }.forEach { ref ->
                appendPart(ref.stableKey)
                appendPart(ref.spaceId)
                appendPart(ref.generation?.toString())
            }
        }

    private fun StringBuilder.appendPart(value: String?) {
        if (value == null) {
            append("-1:")
        } else {
            append(value.length).append(':').append(value)
        }
        append(';')
    }
}

/**
 * In production this state is persisted with the inbox/decision tables. The domain form keeps the
 * binding semantics in one place so a replay of an ignored cancellation cannot prompt again.
 */
class SyncReceiverMemory {
    private val decisions = mutableMapOf<String, SyncCancellationDecision>()

    fun pendingCancellation(
        reduction: SyncReduction,
        objectKey: SyncObjectKey,
        field: SyncField,
        localValue: Boolean,
    ): List<SyncCancellationRequest> =
        SyncReceiver
            .pendingCancellation(reduction, objectKey, field, localValue)
            .filterNot { decisions.containsKey(it.binding) }

    fun decide(
        request: SyncCancellationRequest,
        decision: SyncCancellationDecision,
        current: SyncReduction,
    ): SyncReceiverDecision {
        val previous = decisions[request.binding]
        val result = SyncReceiver.decide(request, previous ?: decision, current)
        if (result.result == SyncReceiverDecisionResult.INVALIDATED) return result
        if (previous == null &&
            (
                result.result == SyncReceiverDecisionResult.APPLIED ||
                    result.result == SyncReceiverDecisionResult.KEPT_LOCAL
                )
        ) {
            decisions[request.binding] = decision
        }
        return result
    }
}
