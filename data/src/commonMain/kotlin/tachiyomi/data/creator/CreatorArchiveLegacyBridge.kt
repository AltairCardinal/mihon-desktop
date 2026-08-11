package tachiyomi.data.creator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.model.CanonicalWorkPortableKey
import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag
import tachiyomi.domain.creator.model.CreatorArchiveSubjectKey
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CreatorArchiveLegacyBridgeResult(
    val scanned: Int,
    val imported: Int,
    val unchanged: Int,
)

class CreatorArchiveLegacyBootstrap(
    private val bridge: CreatorArchiveLegacyBridge,
) : CreatorArchiveBootstrap {
    private val mutex = Mutex()
    private var ready = false

    override suspend fun awaitReady() {
        mutex.withLock {
            if (ready) return
            bridge.importIncremental()
            ready = true
        }
    }
}

/**
 * Imports only v1 changes made by a temporarily restored old binary.
 *
 * The v1 tables are read-only from the current application. A canonical fingerprint is recorded
 * in the same transaction as every v2 mutation so unchanged legacy rows cannot recreate a user
 * split or manual binding.
 */
@OptIn(ExperimentalUuidApi::class)
class CreatorArchiveLegacyBridge(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val portableKeyFactory: () -> String = { Uuid.random().toHexDashString() },
) {
    suspend fun importIncremental(): CreatorArchiveLegacyBridgeResult {
        return handler.await(inTransaction = true) {
            val legacyCreators = creatorsQueries.legacyCreatorsForArchiveBridge(::LegacyCreator).executeAsList()
            val creatorIds = mutableMapOf<Long, Long>()
            var imported = 0
            var unchanged = 0

            legacyCreators.forEach { legacy ->
                val key = "creator:${legacy.id}"
                val fingerprint = fingerprint(
                    legacy.displayName,
                    legacy.normalizedName,
                    legacy.sortName,
                    legacy.aliases,
                    legacy.createdAt,
                    legacy.lastModifiedAt,
                )
                val previous = author_archiveQueries
                    .getLegacyImportFingerprint(CREATOR_ENTITY, key)
                    .executeAsOneOrNull()
                var archiveId = author_archiveQueries.getArchiveCreatorIdByLegacyId(legacy.id).executeAsOneOrNull()
                val needsApply = previous != fingerprint
                if (archiveId == null) {
                    archiveId = insertLegacyCreator(legacy)
                    imported += 1
                } else if (previous != null && needsApply) {
                    addLegacyAliases(archiveId, legacy, clock())
                    author_archiveQueries.markArchiveCreatorNeedsReview(legacy.lastModifiedAt, archiveId)
                    imported += 1
                } else {
                    unchanged += 1
                }
                creatorIds[legacy.id] = archiveId
                author_archiveQueries.upsertLegacyImportFingerprint(CREATOR_ENTITY, key, fingerprint, clock())
            }

            val legacyRelations = creatorsQueries
                .legacyMangaCreatorsForArchiveBridge(::LegacyMangaRelation)
                .executeAsList()
                .groupBy { it.mangaId to it.creatorId }
                .map { (identity, rows) -> rows.foldRelation(identity.first, identity.second) }
            legacyRelations.forEach { relation ->
                val archiveCreatorId = creatorIds[relation.legacyCreatorId]
                    ?: author_archiveQueries
                        .getArchiveCreatorIdByLegacyId(relation.legacyCreatorId)
                        .executeAsOneOrNull()
                    ?: return@forEach
                val key = "manga:${relation.mangaId}:creator:${relation.legacyCreatorId}"
                val relationFingerprint = fingerprint(
                    relation.role.name,
                    relation.sourceText,
                    relation.confidence,
                    relation.evidence,
                )
                val previous = author_archiveQueries
                    .getLegacyImportFingerprint(MANGA_RELATION_ENTITY, key)
                    .executeAsOneOrNull()
                val existing = author_archiveQueries
                    .getArchiveMangaLink(relation.mangaId, archiveCreatorId)
                    .executeAsOneOrNull()
                if (previous == relationFingerprint) {
                    unchanged += 1
                } else if (previous == null && existing != null) {
                    unchanged += 1
                } else {
                    val now = clock()
                    author_archiveQueries.upsertArchiveMangaLink(
                        mangaId = relation.mangaId,
                        creatorId = archiveCreatorId,
                        role = relation.role.name,
                        creatorOrder = 0,
                        origin = "MIGRATION",
                        sourceText = relation.sourceText,
                        confidence = relation.confidence.coerceIn(0.0, 1.0),
                        evidence = relation.evidence,
                        createdAt = now,
                        lastModifiedAt = now,
                    )
                    imported += 1
                }
                author_archiveQueries.upsertLegacyImportFingerprint(
                    MANGA_RELATION_ENTITY,
                    key,
                    relationFingerprint,
                    clock(),
                )
            }

            val legacyCandidates = creatorsQueries
                .legacyDiscoveryCandidatesForArchiveBridge(::LegacyCandidate)
                .executeAsList()
            val sourceWorkIds = mutableMapOf<Long, Long>()
            legacyCandidates.forEach { candidate ->
                val key = "candidate:${candidate.id}"
                val metadataFingerprint = fingerprint(
                    candidate.source,
                    candidate.url,
                    candidate.title,
                    candidate.normalizedTitle,
                    candidate.authorText,
                    candidate.artistText,
                    candidate.languageTag,
                    candidate.languageConfidence,
                    candidate.languageEvidence,
                    candidate.thumbnailUrl,
                    candidate.firstSeenAt,
                    candidate.lastSeenAt,
                    candidate.detailsFetchedAt,
                )
                val reviewFingerprint = fingerprint(candidate.state.toLegacyReviewSnapshot())
                val previousMetadata = author_archiveQueries
                    .getLegacyImportFingerprint(CANDIDATE_ENTITY, key)
                    .executeAsOneOrNull()
                val previousReview = author_archiveQueries
                    .getLegacyImportFingerprint(CANDIDATE_REVIEW_ENTITY, key)
                    .executeAsOneOrNull()
                var sourceWorkId = author_archiveQueries
                    .getArchiveSourceWorkIdByLegacyId(candidate.id)
                    .executeAsOneOrNull()
                val existedFromMigration = sourceWorkId != null
                var reviewAlreadyAuthoritative = existedFromMigration
                if (sourceWorkId == null) {
                    val naturalMatch = author_archiveQueries
                        .getArchiveSourceWorkByKey(candidate.source, candidate.stableLegacyUrl())
                        .executeAsOneOrNull()
                    if (naturalMatch == null) {
                        sourceWorkId = insertLegacyCandidate(candidate)
                    } else {
                        sourceWorkId = naturalMatch._id
                        reviewAlreadyAuthoritative = true
                        author_archiveQueries.attachArchiveLegacyCandidateId(candidate.id, sourceWorkId)
                    }
                }
                val metadataNeedsApply = when {
                    previousMetadata == metadataFingerprint -> false
                    previousMetadata == null && existedFromMigration -> false
                    else -> true
                }
                val reviewNeedsApply = when {
                    previousReview == reviewFingerprint -> false
                    previousReview == null && reviewAlreadyAuthoritative -> false
                    else -> true
                }
                if (metadataNeedsApply) {
                    updateLegacyCandidateMetadata(sourceWorkId, candidate)
                    appendLegacyCandidateLanguage(candidate, metadataFingerprint)
                }
                if (reviewNeedsApply) {
                    updateLegacyCandidateReview(sourceWorkId, candidate)
                }
                if (metadataNeedsApply || reviewNeedsApply) {
                    imported += 1
                } else {
                    unchanged += 1
                }
                sourceWorkIds[candidate.id] = sourceWorkId
                author_archiveQueries.upsertLegacyImportFingerprint(
                    CANDIDATE_ENTITY,
                    key,
                    metadataFingerprint,
                    clock(),
                )
                author_archiveQueries.upsertLegacyImportFingerprint(
                    CANDIDATE_REVIEW_ENTITY,
                    key,
                    reviewFingerprint,
                    clock(),
                )
            }

            val legacyCandidateRelations = creatorsQueries
                .legacyDiscoveryCandidateCreatorsForArchiveBridge(::LegacyCandidateRelation)
                .executeAsList()
                .groupBy { it.candidateId to it.creatorId }
                .map { (identity, rows) -> rows.foldCandidateRelation(identity.first, identity.second) }
            legacyCandidateRelations.forEach { relation ->
                val sourceWorkId = sourceWorkIds[relation.legacyCandidateId]
                    ?: author_archiveQueries
                        .getArchiveSourceWorkIdByLegacyId(relation.legacyCandidateId)
                        .executeAsOneOrNull()
                    ?: return@forEach
                val archiveCreatorId = creatorIds[relation.legacyCreatorId]
                    ?: author_archiveQueries
                        .getArchiveCreatorIdByLegacyId(relation.legacyCreatorId)
                        .executeAsOneOrNull()
                    ?: return@forEach
                val key = "candidate:${relation.legacyCandidateId}:creator:${relation.legacyCreatorId}"
                val relationFingerprint = fingerprint(
                    relation.role.name,
                    relation.sourceText,
                    relation.confidence,
                    relation.evidence,
                )
                val previous = author_archiveQueries
                    .getLegacyImportFingerprint(CANDIDATE_RELATION_ENTITY, key)
                    .executeAsOneOrNull()
                val existing = author_archiveQueries
                    .getArchiveSourceWorkCreator(sourceWorkId, archiveCreatorId)
                    .executeAsOneOrNull()
                if (previous == relationFingerprint || (previous == null && existing != null)) {
                    unchanged += 1
                } else {
                    val now = clock()
                    author_archiveQueries.upsertArchiveSourceWorkCreator(
                        sourceWorkId = sourceWorkId,
                        creatorId = archiveCreatorId,
                        role = relation.role.name,
                        creatorOrder = 0,
                        origin = "MIGRATION",
                        verification = if (relation.role == CreatorRole.UNKNOWN) "POSSIBLE" else "VERIFIED",
                        sourceText = relation.sourceText,
                        confidence = relation.confidence.coerceIn(0.0, 1.0),
                        evidence = relation.evidence,
                        createdAt = now,
                        lastModifiedAt = now,
                    )
                    imported += 1
                }
                author_archiveQueries.upsertLegacyImportFingerprint(
                    CANDIDATE_RELATION_ENTITY,
                    key,
                    relationFingerprint,
                    clock(),
                )
            }

            val legacyWatches = creatorsQueries
                .legacyCreatorWatchesForArchiveBridge(::LegacyWatch)
                .executeAsList()
            legacyWatches.forEach { watch ->
                val archiveCreatorId = creatorIds[watch.creatorId]
                    ?: author_archiveQueries
                        .getArchiveCreatorIdByLegacyId(watch.creatorId)
                        .executeAsOneOrNull()
                    ?: return@forEach
                val sourceIds = watch.sourceIds.parseLegacyLongList()
                val languages = watch.languageTags
                    .parseLegacyStringList()
                    .map(CreatorArchiveLanguageTag::normalize)
                    .filter { it != "und" }
                    .distinct()
                    .sorted()
                val key = "watch:${watch.creatorId}"
                val watchFingerprint = fingerprint(
                    watch.enabled,
                    sourceIds.sorted().joinToString(","),
                    languages.joinToString(","),
                    watch.lastCheckedAt,
                    watch.lastSuccessAt,
                    watch.lastError,
                    watch.createdAt,
                )
                val previous = author_archiveQueries
                    .getLegacyImportFingerprint(WATCH_ENTITY, key)
                    .executeAsOneOrNull()
                val existingWatchId = author_archiveQueries
                    .getArchiveWatchIdByCreator(archiveCreatorId)
                    .executeAsOneOrNull()
                if (previous == watchFingerprint || (previous == null && existingWatchId != null)) {
                    unchanged += 1
                } else {
                    applyLegacyWatch(archiveCreatorId, watch, sourceIds, languages)
                    imported += 1
                }
                author_archiveQueries.upsertLegacyImportFingerprint(WATCH_ENTITY, key, watchFingerprint, clock())
            }

            val legacyWorks = creatorsQueries
                .legacyCanonicalWorksForArchiveBridge(::LegacyCanonicalWork)
                .executeAsList()
            val workIds = mutableMapOf<Long, Long>()
            legacyWorks.forEach { work ->
                val key = "work:${work.id}"
                val workFingerprint = fingerprint(
                    work.primaryTitle,
                    work.normalizedTitle,
                    work.primaryCreatorId,
                    work.originalLanguage,
                    work.createdAt,
                    work.lastModifiedAt,
                )
                val previous = author_archiveQueries
                    .getLegacyImportFingerprint(CANONICAL_WORK_ENTITY, key)
                    .executeAsOneOrNull()
                var archiveWorkId = author_archiveQueries
                    .getArchiveCanonicalWorkIdByLegacyId(work.id)
                    .executeAsOneOrNull()
                val existedFromMigration = archiveWorkId != null
                if (archiveWorkId == null) {
                    archiveWorkId = insertLegacyCanonicalWork(work)
                }
                val needsApply = when {
                    previous == workFingerprint -> false
                    previous == null && existedFromMigration -> false
                    else -> true
                }
                if (needsApply) {
                    applyLegacyCanonicalWork(archiveWorkId, work, creatorIds, workFingerprint)
                    imported += 1
                } else {
                    unchanged += 1
                }
                workIds[work.id] = archiveWorkId
                author_archiveQueries.upsertLegacyImportFingerprint(
                    CANONICAL_WORK_ENTITY,
                    key,
                    workFingerprint,
                    clock(),
                )
            }

            val legacyMatches = creatorsQueries
                .legacyMangaWorkMatchesForArchiveBridge(::LegacyMangaWorkMatch)
                .executeAsList()
            legacyMatches.forEach { match ->
                val archiveWorkId = workIds[match.workId]
                    ?: author_archiveQueries
                        .getArchiveCanonicalWorkIdByLegacyId(match.workId)
                        .executeAsOneOrNull()
                    ?: return@forEach
                val sourceWorkId = ensureSourceWorkForManga(match)
                val key = "match:${match.mangaId}"
                val matchFingerprint = fingerprint(
                    match.workId,
                    match.confidence,
                    match.matchReason,
                    match.state,
                    match.manuallyConfirmed,
                    match.createdAt,
                    match.lastModifiedAt,
                )
                val previous = author_archiveQueries
                    .getLegacyImportFingerprint(WORK_MATCH_ENTITY, key)
                    .executeAsOneOrNull()
                val existing = author_archiveQueries
                    .getLatestArchiveWorkDecision(sourceWorkId, archiveWorkId)
                    .executeAsOneOrNull()
                if (previous == matchFingerprint || (previous == null && existing != null)) {
                    unchanged += 1
                } else {
                    applyLegacyWorkMatch(sourceWorkId, archiveWorkId, match, matchFingerprint)
                    imported += 1
                }
                author_archiveQueries.upsertLegacyImportFingerprint(
                    WORK_MATCH_ENTITY,
                    key,
                    matchFingerprint,
                    clock(),
                )
            }

            CreatorArchiveLegacyBridgeResult(
                scanned = legacyCreators.size +
                    legacyRelations.size +
                    legacyCandidates.size +
                    legacyCandidateRelations.size +
                    legacyWatches.size +
                    legacyWorks.size +
                    legacyMatches.size,
                imported = imported,
                unchanged = unchanged,
            )
        }
    }

    private fun Database.insertLegacyCreator(legacy: LegacyCreator): Long {
        val now = clock()
        author_archiveQueries.insertArchiveCreatorFromLegacy(
            portableKey = portableKeyFactory(),
            displayName = legacy.displayName,
            normalizedName = legacy.normalizedName,
            sortName = legacy.sortName,
            legacyCreatorId = legacy.id,
            createdAt = legacy.createdAt,
            lastModifiedAt = maxOf(legacy.lastModifiedAt, now),
        )
        val id = author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
        addLegacyAliases(id, legacy, now)
        return id
    }

    private fun Database.addLegacyAliases(creatorId: Long, legacy: LegacyCreator, now: Long) {
        (listOf(legacy.displayName) + legacy.aliases.split('|'))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(CreatorNameNormalizer::normalize)
            .forEachIndexed { index, alias ->
                author_archiveQueries.upsertArchiveAlias(
                    creatorId = creatorId,
                    rawAlias = alias,
                    normalizedAlias = CreatorNameNormalizer.normalize(alias),
                    source = if (index == 0) "LEGACY_PRIMARY" else "LEGACY_ALIAS",
                    evidence = "legacy rollback bridge",
                    confidence = 1.0,
                    isManual = false,
                    createdAt = legacy.createdAt,
                    lastModifiedAt = now,
                )
            }
    }

    private fun Database.insertLegacyCandidate(candidate: LegacyCandidate): Long {
        author_archiveQueries.insertArchiveSourceWorkFromLegacy(
            sourceId = candidate.source,
            stableSourceUrl = candidate.stableLegacyUrl(),
            title = candidate.title,
            normalizedTitle = candidate.normalizedTitle,
            authorText = candidate.authorText,
            artistText = candidate.artistText,
            thumbnailUrl = candidate.thumbnailUrl,
            firstSeenAt = candidate.firstSeenAt,
            lastSeenAt = candidate.lastSeenAt,
            detailsFetchedAt = candidate.detailsFetchedAt,
            legacyCandidateId = candidate.id,
            legacyReviewSnapshot = candidate.state.toLegacyReviewSnapshot(),
        )
        return author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
    }

    private fun Database.updateLegacyCandidateMetadata(sourceWorkId: Long, candidate: LegacyCandidate) {
        author_archiveQueries.updateArchiveSourceWorkFromLegacyBridge(
            title = candidate.title,
            normalizedTitle = candidate.normalizedTitle,
            authorText = candidate.authorText,
            artistText = candidate.artistText,
            thumbnailUrl = candidate.thumbnailUrl,
            firstSeenAt = candidate.firstSeenAt,
            lastSeenAt = candidate.lastSeenAt,
            detailsFetchedAt = candidate.detailsFetchedAt,
            legacyCandidateId = candidate.id,
            id = sourceWorkId,
        )
    }

    private fun Database.updateLegacyCandidateReview(sourceWorkId: Long, candidate: LegacyCandidate) {
        author_archiveQueries.updateArchiveSourceWorkReviewFromLegacyBridge(
            legacyReviewSnapshot = candidate.state.toLegacyReviewSnapshot(),
            id = sourceWorkId,
        )
    }

    private fun Database.appendLegacyCandidateLanguage(
        candidate: LegacyCandidate,
        candidateFingerprint: String,
    ) {
        val languageTag = CreatorArchiveLanguageTag.normalize(candidate.languageTag)
        val evidenceKind = candidate.languageEvidence.toEvidenceKind(languageTag)
        author_archiveQueries.upsertArchiveLanguageAssertion(
            subjectType = "SOURCE_WORK",
            subjectKey = CreatorArchiveSubjectKey.sourceWork(
                SourceWorkNaturalKey(candidate.source, candidate.stableLegacyUrl()),
            ),
            dimension = "READING",
            languageTag = languageTag,
            confidence = if (languageTag == "und") 0.0 else candidate.languageConfidence.coerceIn(0.0, 1.0),
            evidenceKind = evidenceKind,
            evidencePayload = candidate.languageEvidence,
            actor = "ALGORITHM",
            algorithmVersion = LEGACY_BRIDGE_ALGORITHM_VERSION,
            withdrawn = false,
            assertedAt = candidate.lastSeenAt,
            idempotencyKey = "legacy-candidate-language:${candidate.id}:$candidateFingerprint",
        )
    }

    private fun Database.applyLegacyWatch(
        archiveCreatorId: Long,
        watch: LegacyWatch,
        sourceIds: List<Long>,
        languages: List<String>,
    ) {
        val now = clock()
        author_archiveQueries.upsertArchiveWatchFromLegacy(
            creatorId = archiveCreatorId,
            enabled = watch.enabled,
            periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
            lastCheckedAt = watch.lastCheckedAt,
            lastSuccessAt = watch.lastSuccessAt,
            lastError = watch.lastError,
            createdAt = watch.createdAt,
            lastModifiedAt = now,
        )
        val watchId = author_archiveQueries.getArchiveWatchIdByCreator(archiveCreatorId).executeAsOne()
        val requestedSources = sourceIds.distinct()
        val existingSources = author_archiveQueries.getArchiveWatchSourceIds(watchId).executeAsList()
        (existingSources - requestedSources.toSet()).forEach { sourceId ->
            author_archiveQueries.deleteArchiveWatchSource(watchId, sourceId)
        }
        requestedSources.forEach { sourceId ->
            author_archiveQueries.insertArchiveWatchSource(watchId, sourceId, watch.createdAt, now)
        }
        author_archiveQueries.upsertArchiveWatchPolicy(watchId, watch.createdAt, now)
        val policyId = author_archiveQueries.getArchiveWatchPolicyId(watchId).executeAsOne()
        val existingLanguages = author_archiveQueries.getArchiveWatchLanguages(policyId).executeAsList()
        (existingLanguages - languages.toSet()).forEach { language ->
            author_archiveQueries.deleteArchiveWatchLanguage(policyId, language)
        }
        languages.forEach { language -> author_archiveQueries.insertArchiveWatchLanguage(policyId, language) }
    }

    private fun Database.insertLegacyCanonicalWork(work: LegacyCanonicalWork): Long {
        author_archiveQueries.insertArchiveCanonicalWorkFromLegacy(
            portableKey = portableKeyFactory(),
            primaryTitle = work.primaryTitle,
            normalizedTitle = work.normalizedTitle,
            legacyWorkId = work.id,
            createdAt = work.createdAt,
            lastModifiedAt = work.lastModifiedAt,
        )
        return author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
    }

    private fun Database.applyLegacyCanonicalWork(
        archiveWorkId: Long,
        work: LegacyCanonicalWork,
        creatorIds: Map<Long, Long>,
        workFingerprint: String,
    ) {
        author_archiveQueries.updateArchiveCanonicalWorkFromLegacyBridge(
            primaryTitle = work.primaryTitle,
            normalizedTitle = work.normalizedTitle,
            lastModifiedAt = work.lastModifiedAt,
            id = archiveWorkId,
        )
        work.primaryCreatorId?.let { legacyCreatorId ->
            val archiveCreatorId = creatorIds[legacyCreatorId]
                ?: author_archiveQueries
                    .getArchiveCreatorIdByLegacyId(legacyCreatorId)
                    .executeAsOneOrNull()
                ?: return@let
            author_archiveQueries.upsertArchiveCanonicalCreator(
                workId = archiveWorkId,
                creatorId = archiveCreatorId,
                role = "UNKNOWN",
                creatorOrder = 0,
                origin = "MIGRATION",
                evidence = "legacy rollback primary creator",
            )
        }
        work.originalLanguage?.takeIf(String::isNotBlank)?.let { language ->
            val normalized = CreatorArchiveLanguageTag.normalize(language)
            val portableKey = author_archiveQueries.getArchiveCanonicalWorkPortableKey(archiveWorkId).executeAsOne()
            author_archiveQueries.upsertArchiveLanguageAssertion(
                subjectType = "CANONICAL_WORK",
                subjectKey = CreatorArchiveSubjectKey.canonicalWork(CanonicalWorkPortableKey(portableKey)),
                dimension = "ORIGINAL",
                languageTag = normalized,
                confidence = if (normalized == "und") 0.0 else 1.0,
                evidenceKind = if (normalized == "und") "UNKNOWN" else "MANUAL",
                evidencePayload = "legacy canonical original language",
                actor = "RESTORE",
                algorithmVersion = null,
                withdrawn = false,
                assertedAt = work.lastModifiedAt,
                idempotencyKey = "legacy-canonical-language:${work.id}:$workFingerprint",
            )
        }
    }

    private fun Database.ensureSourceWorkForManga(match: LegacyMangaWorkMatch): Long {
        author_archiveQueries.getArchiveSourceWorkByManga(match.mangaId).executeAsOneOrNull()?.let { return it._id }
        val manga = author_archiveQueries.getMangaSourceWorkSeed(match.mangaId).executeAsOne()
        val stableUrl = manga.url.trim().ifBlank { "legacy-manga:${match.mangaId}" }
        author_archiveQueries.getArchiveSourceWorkByKey(manga.source, stableUrl).executeAsOneOrNull()?.let { existing ->
            return existing._id
        }
        author_archiveQueries.insertArchiveSourceWork(
            sourceId = manga.source,
            stableSourceUrl = stableUrl,
            mangaId = match.mangaId,
            title = manga.title,
            normalizedTitle = CreatorNameNormalizer.normalize(manga.title),
            authorText = manga.author,
            artistText = manga.artist,
            thumbnailUrl = manga.thumbnail_url,
            firstSeenAt = match.createdAt,
            lastSeenAt = match.lastModifiedAt,
            detailsFetchedAt = null,
            legacyReviewSnapshot = null,
        )
        return author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
    }

    private fun Database.applyLegacyWorkMatch(
        sourceWorkId: Long,
        archiveWorkId: Long,
        match: LegacyMangaWorkMatch,
        matchFingerprint: String,
    ) {
        val normalizedState = match.state.uppercase()
        val state = when (normalizedState) {
            "CONFIRMED" -> "CONFIRMED"
            "REJECTED" -> "REJECTED"
            else -> "SUGGESTED"
        }
        val hasExplicitDecision = state != "SUGGESTED"
        val actor = if (hasExplicitDecision) "RESTORE" else "ALGORITHM"
        author_archiveQueries.upsertArchiveWorkDecision(
            sourceWorkId = sourceWorkId,
            workId = archiveWorkId,
            state = state,
            actor = actor,
            explicit = hasExplicitDecision,
            algorithmVersion = if (actor == "ALGORITHM") LEGACY_BRIDGE_ALGORITHM_VERSION else null,
            score = match.confidence.coerceIn(0.0, 1.0),
            evidence = match.matchReason,
            decidedAt = match.lastModifiedAt,
            idempotencyKey = "legacy-work-decision:${match.mangaId}:$matchFingerprint",
        )
        reconcileArchiveCanonicalVersion(sourceWorkId)
    }

    private fun fingerprint(vararg values: Any?): String {
        return buildString {
            values.forEach { value ->
                val encoded = value?.toString() ?: NULL_VALUE
                append(encoded.length)
                append(':')
                append(encoded)
            }
        }
    }

    private fun List<LegacyMangaRelation>.foldRelation(
        mangaId: Long,
        legacyCreatorId: Long,
    ): FoldedMangaRelation {
        val roles = map { it.role.toCreatorRole() }.toSet()
        val role = when {
            CreatorRole.BOTH in roles ||
                (CreatorRole.AUTHOR in roles && CreatorRole.ARTIST in roles) -> CreatorRole.BOTH
            CreatorRole.AUTHOR in roles -> CreatorRole.AUTHOR
            CreatorRole.ARTIST in roles -> CreatorRole.ARTIST
            else -> CreatorRole.UNKNOWN
        }
        val strongest = maxWith(
            compareBy<LegacyMangaRelation> { it.role.toCreatorRole().rank }
                .thenBy { it.confidence },
        )
        return FoldedMangaRelation(
            mangaId = mangaId,
            legacyCreatorId = legacyCreatorId,
            role = role,
            sourceText = strongest.sourceText,
            confidence = maxOf { it.confidence },
            evidence = joinToString(" | ") { it.evidence }.distinctEvidence(),
        )
    }

    private fun List<LegacyCandidateRelation>.foldCandidateRelation(
        legacyCandidateId: Long,
        legacyCreatorId: Long,
    ): FoldedCandidateRelation {
        val roles = map { it.role.toCreatorRole() }.toSet()
        val role = when {
            CreatorRole.BOTH in roles ||
                (CreatorRole.AUTHOR in roles && CreatorRole.ARTIST in roles) -> CreatorRole.BOTH
            CreatorRole.AUTHOR in roles -> CreatorRole.AUTHOR
            CreatorRole.ARTIST in roles -> CreatorRole.ARTIST
            else -> CreatorRole.UNKNOWN
        }
        val strongest = maxWith(
            compareBy<LegacyCandidateRelation> { it.role.toCreatorRole().rank }
                .thenBy { it.confidence },
        )
        return FoldedCandidateRelation(
            legacyCandidateId = legacyCandidateId,
            legacyCreatorId = legacyCreatorId,
            role = role,
            sourceText = strongest.sourceText,
            confidence = maxOf { it.confidence },
            evidence = joinToString(" | ") { it.evidence }.distinctEvidence(),
        )
    }

    private fun String.toCreatorRole(): CreatorRole = runCatching {
        CreatorRole.valueOf(trim().uppercase())
    }.getOrDefault(CreatorRole.UNKNOWN)

    private val CreatorRole.rank: Int
        get() = when (this) {
            CreatorRole.BOTH -> 4
            CreatorRole.AUTHOR -> 3
            CreatorRole.ARTIST -> 2
            CreatorRole.UNKNOWN -> 1
        }

    private fun String.distinctEvidence(): String {
        return split(" | ").distinct().joinToString(" | ")
    }

    private fun String.parseLegacyStringList(): List<String> {
        return split('|').map(String::trim).filter(String::isNotBlank).distinct()
    }

    private fun String.parseLegacyLongList(): List<Long> {
        return parseLegacyStringList().mapNotNull(String::toLongOrNull).distinct()
    }

    private fun LegacyCandidate.stableLegacyUrl(): String {
        return url.trim().ifBlank { "legacy-candidate:$id" }
    }

    private fun String.toLegacyReviewSnapshot(): String = when (uppercase()) {
        "ACCEPTED" -> "ACCEPTED"
        "IGNORED" -> "IGNORED"
        "MERGED" -> "MERGED"
        else -> "PENDING"
    }

    private fun String.toEvidenceKind(normalizedLanguage: String): String {
        if (normalizedLanguage == "und") return "UNKNOWN"
        return when (uppercase()) {
            "EXPLICIT_METADATA", "STRUCTURED_METADATA" -> "STRUCTURED_METADATA"
            "GENRE_TAG", "SOURCE_FILTER_OR_TAG" -> "SOURCE_FILTER_OR_TAG"
            "SOURCE_LANGUAGE", "SINGLE_LANGUAGE_SOURCE" -> "SINGLE_LANGUAGE_SOURCE"
            "TEXT_DETECTED", "TEXT_DETECTION" -> "TEXT_DETECTION"
            else -> "UNKNOWN"
        }
    }

    private data class LegacyCreator(
        val id: Long,
        val displayName: String,
        val normalizedName: String,
        val sortName: String?,
        val aliases: String,
        val createdAt: Long,
        val lastModifiedAt: Long,
    )

    private data class LegacyMangaRelation(
        val mangaId: Long,
        val creatorId: Long,
        val role: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class LegacyCandidate(
        val id: Long,
        val source: Long,
        val url: String,
        val title: String,
        val normalizedTitle: String,
        val authorText: String?,
        val artistText: String?,
        val languageTag: String,
        val languageConfidence: Double,
        val languageEvidence: String,
        val thumbnailUrl: String?,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val detailsFetchedAt: Long?,
        val state: String,
    )

    private data class LegacyCandidateRelation(
        val candidateId: Long,
        val creatorId: Long,
        val role: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class LegacyWatch(
        val creatorId: Long,
        val enabled: Boolean,
        val sourceIds: String,
        val languageTags: String,
        val lastCheckedAt: Long?,
        val lastSuccessAt: Long?,
        val lastError: String?,
        val createdAt: Long,
    )

    private data class LegacyCanonicalWork(
        val id: Long,
        val primaryTitle: String,
        val normalizedTitle: String,
        val primaryCreatorId: Long?,
        val originalLanguage: String?,
        val createdAt: Long,
        val lastModifiedAt: Long,
    )

    private data class LegacyMangaWorkMatch(
        val mangaId: Long,
        val workId: Long,
        val confidence: Double,
        val matchReason: String,
        val state: String,
        val manuallyConfirmed: Boolean,
        val createdAt: Long,
        val lastModifiedAt: Long,
    )

    private data class FoldedMangaRelation(
        val mangaId: Long,
        val legacyCreatorId: Long,
        val role: CreatorRole,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class FoldedCandidateRelation(
        val legacyCandidateId: Long,
        val legacyCreatorId: Long,
        val role: CreatorRole,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private companion object {
        const val CREATOR_ENTITY = "creator"
        const val MANGA_RELATION_ENTITY = "manga_creator"
        const val CANDIDATE_ENTITY = "candidate"
        const val CANDIDATE_REVIEW_ENTITY = "candidate_review"
        const val CANDIDATE_RELATION_ENTITY = "candidate_creator"
        const val WATCH_ENTITY = "watch"
        const val CANONICAL_WORK_ENTITY = "canonical_work"
        const val WORK_MATCH_ENTITY = "work_match"
        const val NULL_VALUE = "<null>"
        const val DEFAULT_WATCH_PERIOD_MILLIS = 86_400_000L
        const val LEGACY_BRIDGE_ALGORITHM_VERSION = "legacy-bridge-v1"
    }
}
