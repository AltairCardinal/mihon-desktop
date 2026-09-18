package tachiyomi.data.creator

import tachiyomi.data.Database

/** The only transaction-local implementation for collapsing one creator graph into another. */
internal fun Database.mergeCreatorIdentityGraph(sourceCreatorId: Long, targetCreatorId: Long, now: Long) {
    if (sourceCreatorId == targetCreatorId) return
    val source = author_archiveQueries.getArchiveCreatorIdentityRecord(sourceCreatorId).executeAsOneOrNull()
        ?: error("Creator identity does not exist: $sourceCreatorId")
    val target = author_archiveQueries.getArchiveCreatorIdentityRecord(targetCreatorId).executeAsOneOrNull()
        ?: error("Creator identity does not exist: $targetCreatorId")
    check(source.status == "ACTIVE") { "Creator identity is not active: $sourceCreatorId" }
    check(target.status == "ACTIVE") { "Creator identity is not active: $targetCreatorId" }

    author_archiveQueries.moveArchiveIdentityNames(targetCreatorId, now, sourceCreatorId)
    author_archiveQueries.getArchiveAliasesForCreatorIdentity(sourceCreatorId).executeAsList().forEach { alias ->
        author_archiveQueries.upsertArchiveAlias(
            targetCreatorId,
            alias.raw_alias,
            alias.normalized_alias,
            alias.source,
            alias.evidence,
            alias.confidence,
            alias.is_manual,
            alias.created_at,
            maxOf(alias.last_modified_at, now),
        )
    }
    author_archiveQueries.getArchiveMangaRelationsForIdentityMerge(
        sourceCreatorId,
    ).executeAsList().forEach { relation ->
        val previousRole = author_archiveQueries.getArchiveMangaLink(relation.manga_id, targetCreatorId)
            .executeAsOneOrNull()?.role
        author_archiveQueries.upsertArchiveMangaLink(
            relation.manga_id,
            targetCreatorId,
            relation.role,
            relation.creator_order,
            relation.origin,
            relation.source_text,
            relation.confidence,
            relation.evidence,
            relation.created_at,
            maxOf(relation.last_modified_at, now),
        )
        author_archiveQueries.unionArchiveMangaLinkRole(relation.role, relation.manga_id, targetCreatorId)
        previousRole?.let { author_archiveQueries.unionArchiveMangaLinkRole(it, relation.manga_id, targetCreatorId) }
        author_archiveQueries.deleteArchiveMangaLink(relation.manga_id, sourceCreatorId)
    }
    author_archiveQueries.getArchiveSourceWorkRelationsForIdentityMerge(
        sourceCreatorId,
    ).executeAsList().forEach { relation ->
        val previousRole = author_archiveQueries.getArchiveSourceWorkCreator(relation.source_work_id, targetCreatorId)
            .executeAsOneOrNull()?.role
        author_archiveQueries.upsertArchiveSourceWorkCreator(
            relation.source_work_id,
            targetCreatorId,
            relation.role,
            relation.creator_order,
            relation.origin,
            relation.verification,
            relation.source_text,
            relation.confidence,
            relation.evidence,
            relation.created_at,
            maxOf(relation.last_modified_at, now),
        )
        author_archiveQueries.unionArchiveSourceWorkCreatorRole(relation.role, relation.source_work_id, targetCreatorId)
        previousRole?.let {
            author_archiveQueries.unionArchiveSourceWorkCreatorRole(it, relation.source_work_id, targetCreatorId)
        }
        author_archiveQueries.deleteArchiveSourceWorkCreator(relation.source_work_id, sourceCreatorId)
    }
    val targetCanonicalRoles = author_archiveQueries.getArchiveCanonicalCreatorsForCreator(targetCreatorId)
        .executeAsList().associate { it.work_id to it.role }
    author_archiveQueries.getArchiveCanonicalCreatorsForCreator(sourceCreatorId).executeAsList().forEach { relation ->
        val previousRole = targetCanonicalRoles[relation.work_id]
        author_archiveQueries.upsertArchiveCanonicalCreator(
            relation.work_id,
            targetCreatorId,
            relation.role,
            relation.creator_order,
            relation.origin,
            relation.evidence,
        )
        author_archiveQueries.unionArchiveCanonicalCreatorRole(relation.role, relation.work_id, targetCreatorId)
        previousRole?.let {
            author_archiveQueries.unionArchiveCanonicalCreatorRole(it, relation.work_id, targetCreatorId)
        }
        author_archiveQueries.deleteArchiveCanonicalCreator(relation.work_id, sourceCreatorId)
    }
    mergeCreatorWatchGraph(sourceCreatorId, targetCreatorId, now)
    author_archiveQueries.remapArchiveCreatorLanguageAssertions(
        "creator:${source.portable_key}",
        "creator:${target.portable_key}",
    )
    author_archiveQueries.markArchiveCreatorMerged(targetCreatorId, now, sourceCreatorId)
    author_archiveQueries.bumpArchiveCreatorIdentityRevision(now, targetCreatorId)
}

private fun Database.mergeCreatorWatchGraph(sourceCreatorId: Long, targetCreatorId: Long, now: Long) {
    val sourceWatchId = author_archiveQueries.getArchiveWatchIdByCreator(sourceCreatorId).executeAsOneOrNull() ?: return
    val targetWatchId = author_archiveQueries.getArchiveWatchIdByCreator(targetCreatorId).executeAsOneOrNull()
    if (targetWatchId == null) {
        author_archiveQueries.reassignArchiveWatchCreator(targetCreatorId, now, sourceWatchId)
        return
    }
    author_archiveQueries.mergeArchiveWatchRoot(sourceWatchId, now, targetWatchId)
    author_archiveQueries.mergeArchiveWatchSources(targetWatchId, sourceWatchId)
    author_archiveQueries.mergeArchiveWatchPolicy(targetWatchId, sourceWatchId)
    author_archiveQueries.getArchiveWatchPolicyId(targetWatchId).executeAsOneOrNull()?.let { targetPolicyId ->
        author_archiveQueries.mergeArchiveWatchLanguages(targetPolicyId, sourceWatchId)
    }
    author_archiveQueries.moveArchiveWatchRuns(targetWatchId, sourceWatchId)
    author_archiveQueries.mergeArchiveSourceCheckpoints(targetWatchId, sourceWatchId)
    author_archiveQueries.mergeArchiveDuplicateDiscoveries(sourceWatchId, targetWatchId)
    author_archiveQueries.repointArchiveDuplicateDiscoveryOutbox(sourceWatchId, targetWatchId)
    author_archiveQueries.deleteArchiveDuplicateDiscoveries(sourceWatchId, targetWatchId)
    author_archiveQueries.moveArchiveWatchDiscoveries(targetWatchId, sourceWatchId)
    author_archiveQueries.deleteArchiveWatchById(sourceWatchId)
}
