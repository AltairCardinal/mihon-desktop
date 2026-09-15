package tachiyomi.domain.reader.model

import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncFieldKey

data class ReadingSyncScope(val spaceId: String, val generation: Long, val actorId: String, val epoch: Long)

/** A reader keeps its observed causal baseline while background synchronization continues. */
data class ReadingSyncSnapshot(
    val scope: ReadingSyncScope? = null,
    val heads: Map<SyncFieldKey, List<SyncEffectRef>> = emptyMap(),
)
