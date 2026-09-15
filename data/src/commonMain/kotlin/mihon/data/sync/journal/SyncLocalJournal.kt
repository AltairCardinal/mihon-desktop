package mihon.data.sync.journal

import mihon.domain.sync.SyncCodec
import mihon.domain.sync.SyncDecodeResult
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.transport.SyncRepository
import tachiyomi.data.DatabaseHandler

/** The active space records operations independently of scheduling and GitHub credential availability. */
class SyncLocalJournal(private val handler: DatabaseHandler) {
    suspend fun connect(
        spaceId: String,
        generation: Long,
        repository: SyncRepository,
        actorId: String,
        epoch: Long,
    ) {
        require(spaceId.isNotBlank() && spaceId.length <= 128 && generation >= 0)
        require(actorId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && epoch > 0)
        handler.await(inTransaction = true) {
            val existing = sync_journalQueries.getSpace(spaceId, generation).executeAsOneOrNull()
            val actor = sync_journalQueries.getCurrentActor(spaceId, generation).executeAsOneOrNull()
            if (existing != null) {
                require(
                    existing.repository_owner == repository.owner && existing.repository_name == repository.name &&
                        existing.repository_branch == repository.branch,
                ) { "space repository cannot be replaced" }
                require(actor?.actor_id == actorId && actor.epoch == epoch) { "existing actor requires recovery" }
            }
            sync_journalQueries.deactivateSpaces()
            if (existing == null) {
                sync_journalQueries.insertSpace(
                    spaceId,
                    generation,
                    repository.owner,
                    repository.name,
                    repository.branch,
                )
                sync_journalQueries.insertActor(spaceId, generation, actorId, epoch)
            } else {
                sync_journalQueries.activateSpace(spaceId, generation)
            }
        }
    }

    suspend fun pendingEvents(
        spaceId: String,
        generation: Long,
        limit: Int = 256,
        offset: Long = 0,
    ): List<SyncEventEnvelope> {
        require(limit in 1..256 && offset >= 0)
        return handler.await {
            sync_journalQueries.getPendingEvents(spaceId, generation, limit.toLong(), offset).executeAsList().map {
                (SyncCodec.decode(it) as? SyncDecodeResult.Accepted)?.event
                    ?: error("stored sync event cannot be decoded")
            }
        }
    }
}
