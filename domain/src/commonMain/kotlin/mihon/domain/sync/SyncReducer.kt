package mihon.domain.sync

private data class SyncEffectRecord(
    val event: SyncEventEnvelope,
    val effect: SyncEffect,
    val ref: SyncEffectRef,
)

object SyncReducer {
    fun reduce(
        events: Iterable<SyncEventEnvelope>,
        spaceId: String,
        generation: Long,
    ): SyncReduction {
        val accepted = linkedMapOf<SyncEventId, SyncEventEnvelope>()
        val conflictedIds = mutableSetOf<SyncEventId>()
        val rejections = mutableListOf<SyncRejection>()
        val pending = mutableSetOf<SyncEffectRef>()
        var expectedSpace: String? = spaceId
        var expectedGeneration: Long? = generation

        events.forEach { event ->
            val intrinsicErrors = SyncValidator.validate(event)
            if (intrinsicErrors.isNotEmpty()) {
                rejections += intrinsicErrors
                return@forEach
            }
            if (expectedSpace != null && event.spaceId != expectedSpace) {
                rejections += SyncRejection(
                    SyncRejectionReason.WRONG_SPACE,
                    "event belongs to another space",
                    event.eventId,
                )
                return@forEach
            }
            if (expectedGeneration != null && event.generation != expectedGeneration) {
                rejections += SyncRejection(
                    SyncRejectionReason.WRONG_GENERATION,
                    "event belongs to another generation",
                    event.eventId,
                )
                return@forEach
            }
            if (expectedSpace == null) expectedSpace = event.spaceId
            if (expectedGeneration == null) expectedGeneration = event.generation

            if (event.eventId in conflictedIds) return@forEach
            val previous = accepted[event.eventId]
            if (previous != null) {
                if (SyncCodec.canonical(previous) != SyncCodec.canonical(event)) {
                    rejections += SyncRejection(
                        SyncRejectionReason.SAME_ID_DIFFERENT_CONTENT,
                        "event id was received with different content",
                        event.eventId,
                    )
                    accepted.remove(event.eventId)
                    conflictedIds += event.eventId
                }
                return@forEach
            }
            accepted[event.eventId] = event
        }

        val records = records(accepted)
        val invalidEvents = mutableSetOf<SyncEventId>()
        records.values.forEach { record ->
            record.effect.parents.forEach { parent ->
                if (parent.eventId in conflictedIds) {
                    rejections += SyncRejection(
                        SyncRejectionReason.SAME_ID_DIFFERENT_CONTENT,
                        "parent event id has conflicting content",
                        record.event.eventId,
                        record.ref,
                    )
                    invalidEvents += record.event.eventId
                    return@forEach
                }
                val parentRecord = records[parent.stableKey]
                if (parentRecord == null) {
                    if (accepted[parent.eventId] != null) {
                        rejections += SyncRejection(
                            SyncRejectionReason.MISSING_PARENT_EFFECT,
                            "parent event does not contain the referenced effect",
                            record.event.eventId,
                            record.ref,
                        )
                        invalidEvents += record.event.eventId
                    } else {
                        pending += parent
                    }
                } else if (parentRecord.effect.objectKey != record.effect.objectKey ||
                    parentRecord.effect.field != record.effect.field
                ) {
                    rejections += SyncRejection(
                        SyncRejectionReason.CROSS_FIELD_PARENT,
                        "parent must reference the same object field",
                        record.event.eventId,
                        record.ref,
                    )
                    invalidEvents += record.event.eventId
                } else if (record.event.origin != SyncOrigin.USER &&
                    parentRecord.event.origin == SyncOrigin.USER
                ) {
                    rejections += SyncRejection(
                        SyncRejectionReason.INVALID_EFFECT_COMBINATION,
                        "baseline event cannot descend from a user operation",
                        record.event.eventId,
                        record.ref,
                    )
                    invalidEvents += record.event.eventId
                }
            }
        }

        val graphRecords = records.filterValues { it.event.eventId !in invalidEvents }
        val cycleRefs = findCycleRefs(graphRecords)
        cycleRefs.forEach { refKey ->
            val record = graphRecords.getValue(refKey)
            rejections += SyncRejection(
                SyncRejectionReason.CAUSAL_CYCLE,
                "causal graph contains a cycle",
                record.event.eventId,
                record.ref,
            )
            invalidEvents += record.event.eventId
        }
        invalidEvents.forEach { accepted.remove(it) }
        val validRecords = records(accepted)
        val completeRefs = completeRefs(validRecords)

        val groups = linkedMapOf<SyncFieldKey, MutableList<SyncEffectRecord>>()
        validRecords.values.forEach { record ->
            groups.getOrPut(SyncFieldKey(record.effect.objectKey, record.effect.field)) { mutableListOf() }.add(record)
        }
        val projections = linkedMapOf<SyncFieldKey, SyncProjection>()
        groups.forEach { (key, group) ->
            val complete = group.filter { it.ref.stableKey in completeRefs }
            val completeKeys = complete.mapTo(mutableSetOf()) { it.ref.stableKey }
            val hasCompleteChild = mutableSetOf<String>()
            complete.forEach { record ->
                record.effect.parents.forEach { parent ->
                    if (parent.stableKey in completeKeys) hasCompleteChild += parent.stableKey
                }
            }
            val heads = complete
                .filter { it.ref.stableKey !in hasCompleteChild }
                .sortedBy { it.ref.stableKey }
            val headRefs = heads.map { it.ref }
            val metadata = heads.associate { it.ref to SyncEffectMetadata(it.event.occurredAt, it.event.origin) }
            val effectsByRef = heads.associate { it.ref to it.effect }
            val userHeads = heads.filter { it.event.origin == SyncOrigin.USER }
            val considered = if (userHeads.isNotEmpty()) userHeads else heads
            val membership = if (key.field == SyncField.FAVORITE || key.field == SyncField.FOLLOWING) {
                when {
                    considered.any { it.effect.kind == SyncEffectKind.ADD } -> true
                    considered.any { it.effect.kind == SyncEffectKind.REMOVE } -> false
                    else -> null
                }
            } else {
                null
            }
            val status = if (key.field == SyncField.READ_STATUS) {
                when {
                    considered.any { it.effect.kind == SyncEffectKind.MARK_UNREAD } -> SyncReadStatus.UNREAD
                    considered.any { it.effect.kind == SyncEffectKind.MARK_READ } -> SyncReadStatus.READ
                    else -> null
                }
            } else {
                null
            }
            projections[key] = SyncProjection(
                key = key,
                heads = headRefs,
                effects = heads.map { it.effect },
                metadata = metadata,
                effectsByRef = effectsByRef,
                value = membership,
                readStatus = status,
                conflict = considered.map { it.effect.kind }.distinct().size > 1,
                pending = complete.size != group.size,
            )
            group.filter { it.ref.stableKey !in completeRefs }.forEach { record ->
                pending += record.effect.parents
            }
        }
        return SyncReduction(accepted, rejections, pending.toList().sortedBy { it.stableKey }, projections)
    }

    private fun records(events: Map<SyncEventId, SyncEventEnvelope>): Map<String, SyncEffectRecord> =
        buildMap {
            events.values.forEach { event ->
                event.effects.forEach { effect ->
                    val ref = event.ref(effect.effectId)
                    put(ref.stableKey, SyncEffectRecord(event, effect, ref))
                }
            }
        }

    /** Kahn's algorithm returns every node left in a directed parent graph. */
    private fun findCycleRefs(records: Map<String, SyncEffectRecord>): Set<String> {
        val indegree = records.keys.associateWithTo(mutableMapOf()) { 0 }
        val children = mutableMapOf<String, MutableList<String>>()
        records.forEach { (key, record) ->
            record.effect.parents.map { it.stableKey }.distinct().forEach { parentKey ->
                if (parentKey in records) {
                    indegree[key] = indegree.getValue(key) + 1
                    children.getOrPut(parentKey) { mutableListOf() }.add(key)
                }
            }
        }
        val queue = ArrayDeque<String>()
        indegree.filterValues { it == 0 }.keys.forEach(queue::addLast)
        var visited = 0
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            visited++
            children[key].orEmpty().forEach { child ->
                val next = indegree.getValue(child) - 1
                indegree[child] = next
                if (next == 0) queue.addLast(child)
            }
        }
        return if (visited == records.size) emptySet() else indegree.filterValues { it > 0 }.keys
    }

    /** Topological completion keeps missing-parent and cyclic branches out of projections. */
    private fun completeRefs(records: Map<String, SyncEffectRecord>): Set<String> {
        val unresolved = records.keys.associateWithTo(mutableMapOf()) { key ->
            val parents = records.getValue(key).effect.parents.map { it.stableKey }.distinct()
            if (parents.all { it in records }) parents.size else -1
        }
        val children = mutableMapOf<String, MutableList<String>>()
        records.forEach { (key, record) ->
            record.effect.parents.map { it.stableKey }.distinct().forEach { parentKey ->
                if (parentKey in records) children.getOrPut(parentKey) { mutableListOf() }.add(key)
            }
        }
        val queue = ArrayDeque<String>()
        unresolved.filterValues { it == 0 }.keys.forEach(queue::addLast)
        val complete = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (!complete.add(key)) continue
            children[key].orEmpty().forEach { child ->
                val next = unresolved.getValue(child) - 1
                unresolved[child] = next
                if (next == 0) queue.addLast(child)
            }
        }
        return complete
    }
}
