package mihon.data.sync.inbox

import kotlinx.serialization.json.Json
import mihon.data.sync.projection.resolveSyncAuthorIdentity
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncObjectType
import tachiyomi.data.Database

/** Normalized lookup only; the immutable envelope remains the protocol authority. */
internal fun Database.indexSyncEvent(event: SyncEventEnvelope, local: Boolean = false) {
    val query = sync_inboxQueries
    val eventKey = event.eventId.stableKey
    event.effects.forEach { effect ->
        val key = effect.objectKey.stableKey
        val objectJson = Json.encodeToString(effect.objectKey)
        query.insertEventField(event.spaceId, event.generation, eventKey, key, effect.field.name, objectJson)
        query.markFieldDirty(event.spaceId, event.generation, key, effect.field.name, objectJson)
        if (local) {
            if (effect.objectKey.type == SyncObjectType.AUTHOR) {
                resolveSyncAuthorIdentity(requireNotNull(effect.objectKey.portableKey))?.let { identity ->
                    query.invalidateAuthorDecisions(event.spaceId, event.generation, identity)
                    query.clearPendingAuthorDecisions(event.spaceId, event.generation, identity)
                }
            }
            query.clearPendingField(event.spaceId, event.generation, key, effect.field.name)
        }
        effect.parents.forEach { parent ->
            query.insertDependency(event.spaceId, event.generation, parent.eventId.stableKey, eventKey)
        }
    }
    query.markEventIndexed(event.spaceId, event.generation, eventKey)
}

internal fun Database.dirtySyncDependents(spaceId: String, generation: Long, eventKeys: List<String>) {
    eventKeys.chunked(256).forEach { keys ->
        sync_inboxQueries.getAffectedFields(spaceId, generation, keys).executeAsList().forEach {
            sync_inboxQueries.markFieldDirty(spaceId, generation, it.object_key, it.field_, it.object_json)
        }
    }
}
