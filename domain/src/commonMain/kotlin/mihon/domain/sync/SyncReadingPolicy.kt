package mihon.domain.sync

import kotlinx.serialization.json.JsonPrimitive

object SyncReadingPolicy {
    fun chooseResume(
        projection: SyncProjection,
        activeSession: SyncReadingSession,
    ): SyncReadingChoice {
        val candidates = projection.heads.mapNotNull { ref ->
            val effect = projection.effectsByRef[ref] ?: return@mapNotNull null
            val chapter = (effect.payload["chapterKey"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val page = (effect.payload["pageIndex"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            if (page < 0) return@mapNotNull null
            val metadata = projection.metadata[ref] ?: return@mapNotNull null
            SyncResumePosition(ref, chapter, page, metadata.occurredAt, metadata.origin)
        }
        val userCandidates = candidates.filter { it.origin == SyncOrigin.USER }
        val considered = if (userCandidates.isNotEmpty()) userCandidates else candidates
        val ordered = considered.sortedWith(
            compareByDescending<SyncResumePosition> { it.occurredAt }
                .thenByDescending { it.effectRef.stableKey },
        )
        val chosen = ordered.firstOrNull()
        val changed = chosen != null &&
            (chosen.chapterKey != activeSession.chapterKey || chosen.pageIndex != activeSession.pageIndex)
        return SyncReadingChoice(
            nextPosition = chosen,
            history = ordered,
            activeSession = activeSession,
            requiresAdoption = changed,
        )
    }
}
