package tachiyomi.data.creator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag
import tachiyomi.domain.creator.model.CreatorArchiveSubjectKey
import tachiyomi.domain.creator.model.CreatorArchiveV2Policy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateCreator
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.MangaWorkMatch
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.repository.ReadyCreatorArchiveBootstrap
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CreatorRepositoryImpl(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val portableKeyFactory: () -> String = { Uuid.random().toHexDashString() },
    private val bootstrap: CreatorArchiveBootstrap = ReadyCreatorArchiveBootstrap,
) : CreatorRepository, CreatorArchiveRepository {

    override suspend fun upsertCreator(displayName: String, aliases: List<String>): Creator {
        bootstrap.awaitReady()
        val trimmedName = displayName.trim()
        val normalizedName = CreatorNameNormalizer.normalize(trimmedName)
        require(normalizedName.isNotBlank()) { "Creator name must not be blank" }
        val now = clock()
        return handler.await(inTransaction = true) {
            val matches = author_archiveQueries
                .getArchiveCreatorsByAlias(normalizedName, ::mapCreator)
                .executeAsList()
            check(matches.size <= 1) {
                "Ambiguous creator identity for normalized alias '$normalizedName'; " +
                    "explicit identity selection is required"
            }
            val creatorId = matches.singleOrNull()?.id ?: run {
                author_archiveQueries.insertArchiveCreator(
                    portableKey = portableKeyFactory(),
                    displayName = trimmedName,
                    normalizedName = normalizedName,
                    sortName = trimmedName,
                    createdAt = now,
                    lastModifiedAt = now,
                )
                author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
            }
            author_archiveQueries.updateArchiveCreator(
                displayName = trimmedName,
                normalizedName = normalizedName,
                sortName = trimmedName,
                lastModifiedAt = now,
                id = creatorId,
            )
            (listOf(trimmedName) + aliases)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(CreatorNameNormalizer::normalize)
                .forEachIndexed { index, alias ->
                    author_archiveQueries.upsertArchiveAlias(
                        creatorId = creatorId,
                        rawAlias = alias,
                        normalizedAlias = CreatorNameNormalizer.normalize(alias),
                        source = if (index == 0) "PRIMARY" else "LEGACY_COMPAT",
                        evidence = if (index == 0) "creator display name" else "CreatorRepository.upsertCreator alias",
                        confidence = 1.0,
                        isManual = false,
                        createdAt = now,
                        lastModifiedAt = now,
                    )
                }
            author_archiveQueries.getArchiveCreator(creatorId, ::mapCreator).executeAsOne()
        }
    }

    override suspend fun getCreator(id: Long): Creator? {
        bootstrap.awaitReady()
        return handler.awaitOneOrNull { author_archiveQueries.getArchiveCreator(id, ::mapCreator) }
    }

    override fun getCreatorsAsFlow(): Flow<List<Creator>> {
        return handler.subscribeToList { author_archiveQueries.getArchiveCreators(::mapCreator) }
            .onStart { bootstrap.awaitReady() }
    }

    override suspend fun linkMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) {
        bootstrap.awaitReady()
        val now = clock()
        handler.await {
            author_archiveQueries.upsertArchiveMangaLink(
                mangaId = mangaId,
                creatorId = creatorId,
                role = role.name,
                creatorOrder = 0,
                origin = "USER",
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
        }
    }

    override suspend fun linkDiscoveryCandidateCreator(
        candidateId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) {
        bootstrap.awaitReady()
        val now = clock()
        handler.await {
            author_archiveQueries.upsertArchiveSourceWorkCreator(
                sourceWorkId = candidateId,
                creatorId = creatorId,
                role = role.name,
                creatorOrder = 0,
                origin = "AUTOMATIC",
                verification = if (role == CreatorRole.UNKNOWN) "POSSIBLE" else "VERIFIED",
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
        }
    }

    override suspend fun followCreator(
        creatorId: Long,
        sourceIds: List<Long>,
        languageTags: List<String>,
    ): CreatorWatch {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            author_archiveQueries.upsertArchiveWatch(
                creatorId = creatorId,
                periodMillis = DEFAULT_WATCH_PERIOD_MILLIS,
                createdAt = now,
                lastModifiedAt = now,
            )
            val watchId = author_archiveQueries.getArchiveWatchIdByCreator(creatorId).executeAsOne()
            val requestedSourceIds = sourceIds.distinct()
            val existingSourceIds = author_archiveQueries.getArchiveWatchSourceIds(watchId).executeAsList()
            (existingSourceIds - requestedSourceIds.toSet()).forEach { sourceId ->
                author_archiveQueries.deleteArchiveWatchSource(watchId, sourceId)
            }
            requestedSourceIds.forEach { sourceId ->
                author_archiveQueries.insertArchiveWatchSource(
                    watchId = watchId,
                    sourceId = sourceId,
                    createdAt = now,
                    lastModifiedAt = now,
                )
            }
            author_archiveQueries.upsertArchiveWatchPolicy(
                watchId = watchId,
                createdAt = now,
                lastModifiedAt = now,
            )
            val policyId = author_archiveQueries.getArchiveWatchPolicyId(watchId).executeAsOne()
            val requestedLanguageTags = languageTags
                .map(CreatorArchiveLanguageTag::normalize)
                .filter { it != "und" }
                .distinct()
            val existingLanguageTags = author_archiveQueries.getArchiveWatchLanguages(policyId).executeAsList()
            (existingLanguageTags - requestedLanguageTags.toSet()).forEach { languageTag ->
                author_archiveQueries.deleteArchiveWatchLanguage(policyId, languageTag)
            }
            requestedLanguageTags.forEach { languageTag ->
                author_archiveQueries.insertArchiveWatchLanguage(policyId, languageTag)
            }
            author_archiveQueries.getArchiveFollowedCreators(::mapCreatorWatch)
                .executeAsList()
                .first { it.creatorId == creatorId }
        }
    }

    override suspend fun unfollowCreator(creatorId: Long) {
        bootstrap.awaitReady()
        val now = clock()
        handler.await { author_archiveQueries.unfollowArchiveCreator(now, creatorId) }
    }

    override suspend fun getFollowedCreators(): List<CreatorWatch> {
        bootstrap.awaitReady()
        return handler.awaitList { author_archiveQueries.getArchiveFollowedCreators(::mapCreatorWatch) }
    }

    override fun getFollowedCreatorsAsFlow(): Flow<List<CreatorWatch>> {
        return handler.subscribeToList { author_archiveQueries.getArchiveFollowedCreators(::mapCreatorWatch) }
            .onStart { bootstrap.awaitReady() }
    }

    override suspend fun updateWatchCheckResult(creatorId: Long, checkedAt: Long, success: Boolean, error: String?) {
        bootstrap.awaitReady()
        handler.await {
            author_archiveQueries.updateArchiveWatchCheckResult(
                checkedAt = checkedAt,
                success = success,
                error = error,
                creatorId = creatorId,
            )
        }
    }

    override suspend fun upsertSourceWork(
        sourceId: Long,
        stableSourceUrl: String,
        mangaId: Long?,
        title: String,
        authorText: String?,
        artistText: String?,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey> {
        bootstrap.awaitReady()
        return handler.await(inTransaction = true) {
            upsertSourceWorkRecord(
                sourceId = sourceId,
                stableSourceUrl = stableSourceUrl,
                mangaId = mangaId,
                title = title,
                authorText = authorText,
                artistText = artistText,
                thumbnailUrl = thumbnailUrl,
                detailsFetchedAt = detailsFetchedAt,
                reviewState = null,
                now = clock(),
            ).outcome
        }
    }

    override suspend fun upsertSourceWorkCreator(
        sourceWork: SourceWorkNaturalKey,
        creatorId: Long,
        role: CreatorRole,
        order: Long,
        origin: CreatorRelationOrigin,
        verification: CreatorRelationVerification,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey> {
        bootstrap.awaitReady()
        require(order >= 0) { "Creator order must not be negative" }
        require(confidence in 0.0..1.0) { "Creator relation confidence must be between 0 and 1" }
        return handler.await(inTransaction = true) {
            val work = author_archiveQueries
                .getArchiveSourceWorkByKey(sourceWork.sourceId, sourceWork.stableSourceUrl.trim())
                .executeAsOneOrNull()
                ?: error("Source work does not exist: $sourceWork")
            val before = author_archiveQueries
                .getArchiveSourceWorkCreator(work._id, creatorId, ::mapRelationSnapshot)
                .executeAsOneOrNull()
            val requested = RelationSnapshot(
                role = role.name,
                order = order,
                origin = origin.name,
                verification = verification.name,
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
            )
            if (before == requested) return@await ArchiveUpsertOutcome.Unchanged(sourceWork)
            val now = clock()
            author_archiveQueries.upsertArchiveSourceWorkCreator(
                sourceWorkId = work._id,
                creatorId = creatorId,
                role = requested.role,
                creatorOrder = requested.order,
                origin = requested.origin,
                verification = requested.verification,
                sourceText = requested.sourceText,
                confidence = requested.confidence,
                evidence = requested.evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
            val after = author_archiveQueries
                .getArchiveSourceWorkCreator(work._id, creatorId, ::mapRelationSnapshot)
                .executeAsOne()
            when {
                before == null -> ArchiveUpsertOutcome.Inserted(sourceWork)
                before == after -> ArchiveUpsertOutcome.Unchanged(sourceWork)
                else -> ArchiveUpsertOutcome.Updated(sourceWork)
            }
        }
    }

    override suspend fun appendWorkDecision(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        decision: WorkDecisionContract,
        algorithmVersion: String?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<WorkDecisionContract> {
        bootstrap.awaitReady()
        require(idempotencyKey.isNotBlank()) { "Work decision idempotency key must not be blank" }
        require(score == null || score in 0.0..1.0) { "Work decision score must be between 0 and 1" }
        CreatorArchiveV2Policy.resolveWorkDecision(current = null, proposed = decision)
        if (decision.actor == DecisionActor.ALGORITHM) {
            require(!algorithmVersion.isNullOrBlank()) { "Algorithm decisions require an algorithm version" }
        }
        return handler.await(inTransaction = true) {
            val sourceWorkId = author_archiveQueries
                .getArchiveSourceWorkByKey(sourceWork.sourceId, sourceWork.stableSourceUrl.trim())
                .executeAsOneOrNull()
                ?._id
                ?: error("Source work does not exist: $sourceWork")
            val attempted = WorkDecisionEvent(
                sourceWorkId = sourceWorkId,
                workId = workId,
                decision = decision,
                algorithmVersion = algorithmVersion,
                score = score,
                evidence = evidence,
                decidedAt = decidedAt,
            )
            val existing = author_archiveQueries
                .getArchiveWorkDecisionByIdempotencyKey(idempotencyKey, ::mapWorkDecisionEvent)
                .executeAsOneOrNull()
            if (existing != null) {
                return@await if (existing == attempted) {
                    ArchiveAppendOutcome.Unchanged(existing.decision)
                } else {
                    ArchiveAppendOutcome.Conflict(existing.decision, attempted.decision)
                }
            }
            author_archiveQueries.upsertArchiveWorkDecision(
                sourceWorkId = sourceWorkId,
                workId = workId,
                state = decision.state.name,
                actor = decision.actor.name,
                explicit = decision.explicit,
                algorithmVersion = algorithmVersion,
                score = score,
                evidence = evidence,
                decidedAt = decidedAt,
                idempotencyKey = idempotencyKey,
            )
            reconcileArchiveCanonicalVersion(sourceWorkId)
            ArchiveAppendOutcome.Inserted(decision)
        }
    }

    override suspend fun appendLanguageAssertion(
        subject: ArchiveLanguageSubject,
        assertion: LanguageAssertionContract,
        actor: DecisionActor,
        evidencePayload: String,
        algorithmVersion: String?,
        assertedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<LanguageAssertionContract> {
        bootstrap.awaitReady()
        require(idempotencyKey.isNotBlank()) { "Language assertion idempotency key must not be blank" }
        if (assertion.evidenceKind == LanguageEvidenceKind.MANUAL) {
            require(actor != DecisionActor.ALGORITHM) { "Manual language evidence requires USER or RESTORE actor" }
        }
        if (actor == DecisionActor.ALGORITHM) {
            require(!algorithmVersion.isNullOrBlank()) { "Algorithm language assertions require an algorithm version" }
        }
        return handler.await(inTransaction = true) {
            val resolvedSubject = resolveLanguageSubject(subject)
            val normalizedAssertion = assertion.copy(tag = CreatorArchiveLanguageTag.normalize(assertion.tag))
            val attempted = LanguageAssertionEvent(
                subjectType = resolvedSubject.first,
                subjectKey = resolvedSubject.second,
                assertion = normalizedAssertion,
                actor = actor,
                evidencePayload = evidencePayload,
                algorithmVersion = algorithmVersion,
                assertedAt = assertedAt,
            )
            val existing = author_archiveQueries
                .getArchiveLanguageAssertionByIdempotencyKey(idempotencyKey, ::mapLanguageAssertionEvent)
                .executeAsOneOrNull()
            if (existing != null) {
                return@await if (existing == attempted) {
                    ArchiveAppendOutcome.Unchanged(existing.assertion)
                } else {
                    ArchiveAppendOutcome.Conflict(existing.assertion, attempted.assertion)
                }
            }
            author_archiveQueries.upsertArchiveLanguageAssertion(
                subjectType = attempted.subjectType,
                subjectKey = attempted.subjectKey,
                dimension = attempted.assertion.dimension.name,
                languageTag = attempted.assertion.tag,
                confidence = attempted.assertion.confidence,
                evidenceKind = attempted.assertion.evidenceKind.name,
                evidencePayload = attempted.evidencePayload,
                actor = attempted.actor.name,
                algorithmVersion = attempted.algorithmVersion,
                withdrawn = attempted.assertion.withdrawn,
                assertedAt = attempted.assertedAt,
                idempotencyKey = idempotencyKey,
            )
            ArchiveAppendOutcome.Inserted(normalizedAssertion)
        }
    }

    override suspend fun upsertDiscoveryCandidate(
        source: Long,
        url: String,
        title: String,
        authorText: String?,
        artistText: String?,
        languageTag: String,
        languageConfidence: Double,
        languageEvidence: String,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
        state: DiscoveryCandidateState,
    ): DiscoveryCandidate {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            val mutation = upsertSourceWorkRecord(
                sourceId = source,
                stableSourceUrl = url,
                mangaId = null,
                title = title,
                authorText = authorText,
                artistText = artistText,
                thumbnailUrl = thumbnailUrl,
                detailsFetchedAt = detailsFetchedAt,
                reviewState = state.toArchiveReviewState(),
                now = now,
            )
            val evidenceKind = languageEvidence.toArchiveEvidenceKind()
            author_archiveQueries.upsertArchiveLanguageAssertion(
                subjectType = "SOURCE_WORK",
                subjectKey = sourceWorkSubjectKey(source, url.trim()),
                dimension = "READING",
                languageTag = CreatorArchiveLanguageTag.normalize(languageTag),
                confidence = languageConfidence.coerceIn(0.0, 1.0),
                evidenceKind = evidenceKind,
                evidencePayload = languageEvidence,
                actor = "ALGORITHM",
                algorithmVersion = LEGACY_COMPAT_ALGORITHM_VERSION,
                withdrawn = false,
                assertedAt = now,
                idempotencyKey = languageAssertionIdempotencyKey(
                    sourceWorkId = mutation.sourceWorkId,
                    evidenceKind = evidenceKind,
                    languageTag = CreatorArchiveLanguageTag.normalize(languageTag),
                    confidence = languageConfidence,
                    evidencePayload = languageEvidence,
                    assertedAt = now,
                ),
            )
            getArchiveCandidate(sourceWorkId = mutation.sourceWorkId)
        }
    }

    override suspend fun getDiscoveryCandidatesForCreator(creatorId: Long): List<DiscoveryCandidate> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveCandidatesForCreator(
                sourceWorkId = -1,
                creatorId = creatorId,
                limit = Long.MAX_VALUE,
                mapper = ::mapDiscoveryCandidate,
            )
        }
    }

    override suspend fun getDiscoveryCandidate(id: Long): DiscoveryCandidate? {
        bootstrap.awaitReady()
        return handler.awaitOneOrNull {
            author_archiveQueries.getArchiveCandidatesForCreator(
                sourceWorkId = id,
                creatorId = null,
                limit = 1,
                mapper = ::mapDiscoveryCandidate,
            )
        }
    }

    override suspend fun getMangaCreatorsForCreator(creatorId: Long): List<MangaCreator> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveMangaCreatorsForCreator(creatorId, ::mapMangaCreator)
        }
    }

    override suspend fun getDiscoveryCandidateCreatorsForCreator(creatorId: Long): List<DiscoveryCandidateCreator> {
        bootstrap.awaitReady()
        return handler.awaitList {
            author_archiveQueries.getArchiveSourceWorkCreatorsForCreator(creatorId, ::mapDiscoveryCandidateCreator)
        }
    }

    override suspend fun createCanonicalWork(
        primaryTitle: String,
        primaryCreatorId: Long?,
        originalLanguage: String?,
    ): CanonicalWork {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            val portableKey = portableKeyFactory()
            author_archiveQueries.insertArchiveCanonicalWork(
                portableKey = portableKey,
                primaryTitle = primaryTitle.trim(),
                normalizedTitle = CreatorNameNormalizer.normalize(primaryTitle),
                createdAt = now,
                lastModifiedAt = now,
            )
            val workId = author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
            if (primaryCreatorId != null) {
                author_archiveQueries.upsertArchiveCanonicalCreator(
                    workId = workId,
                    creatorId = primaryCreatorId,
                    role = "AUTHOR",
                    creatorOrder = 0,
                    origin = "USER",
                    evidence = "CreatorRepository.createCanonicalWork",
                )
            }
            if (!originalLanguage.isNullOrBlank()) {
                author_archiveQueries.upsertArchiveLanguageAssertion(
                    subjectType = "CANONICAL_WORK",
                    subjectKey = canonicalWorkSubjectKey(portableKey),
                    dimension = "ORIGINAL",
                    languageTag = CreatorArchiveLanguageTag.normalize(originalLanguage),
                    confidence = 1.0,
                    evidenceKind = "MANUAL",
                    evidencePayload = "legacy canonical-work original language",
                    actor = "USER",
                    algorithmVersion = null,
                    withdrawn = false,
                    assertedAt = now,
                    idempotencyKey = "legacy-canonical-work:$workId:original",
                )
            }
            author_archiveQueries.getArchiveCanonicalWork(workId, ::mapCanonicalWork).executeAsOne()
        }
    }

    override suspend fun upsertMangaWorkMatch(
        mangaId: Long,
        workId: Long,
        confidence: Double,
        matchReason: String,
        state: WorkMatchState,
        manuallyConfirmed: Boolean,
    ): MangaWorkMatch {
        bootstrap.awaitReady()
        val now = clock()
        return handler.await(inTransaction = true) {
            val sourceWork = author_archiveQueries.getArchiveSourceWorkByManga(mangaId).executeAsOneOrNull()
                ?: author_archiveQueries.getMangaSourceWorkSeed(mangaId).executeAsOne().let { seed ->
                    val mutation = upsertSourceWorkRecord(
                        sourceId = seed.source,
                        stableSourceUrl = seed.url,
                        mangaId = mangaId,
                        title = seed.title,
                        authorText = seed.author,
                        artistText = seed.artist,
                        thumbnailUrl = seed.thumbnail_url,
                        detailsFetchedAt = null,
                        reviewState = "PENDING",
                        now = now,
                    )
                    author_archiveQueries.getArchiveSourceWorkById(mutation.sourceWorkId).executeAsOne()
                }
            val currentRow = author_archiveQueries
                .getLatestArchiveWorkDecision(sourceWork._id, workId)
                .executeAsOneOrNull()
            val current = currentRow?.let {
                WorkDecisionContract(
                    state = WorkDecisionState.valueOf(it.state),
                    actor = DecisionActor.valueOf(it.actor),
                    explicit = it.explicit,
                )
            }
            val proposed = WorkDecisionContract(
                state = state.toDecisionState(),
                actor = if (manuallyConfirmed) DecisionActor.USER else DecisionActor.ALGORITHM,
                explicit = manuallyConfirmed,
            )
            val resolved = CreatorArchiveV2Policy.resolveWorkDecision(current, proposed)
            if (resolved != current) {
                author_archiveQueries.upsertArchiveWorkDecision(
                    sourceWorkId = sourceWork._id,
                    workId = workId,
                    state = resolved.state.name,
                    actor = resolved.actor.name,
                    explicit = resolved.explicit,
                    algorithmVersion = if (resolved.actor == DecisionActor.ALGORITHM) {
                        LEGACY_COMPAT_ALGORITHM_VERSION
                    } else {
                        null
                    },
                    score = confidence.coerceIn(0.0, 1.0),
                    evidence = matchReason,
                    decidedAt = now,
                    idempotencyKey = workDecisionIdempotencyKey(
                        mangaId = mangaId,
                        workId = workId,
                        decision = resolved,
                        confidence = confidence,
                        evidence = matchReason,
                        decidedAt = now,
                    ),
                )
                reconcileArchiveCanonicalVersion(sourceWork._id)
            }
            val effective = author_archiveQueries
                .getLatestArchiveWorkDecision(sourceWork._id, workId)
                .executeAsOne()
            mapMangaWorkMatch(
                mangaId = mangaId,
                workId = workId,
                confidence = effective.score ?: confidence,
                matchReason = effective.evidence,
                state = effective.state,
                manuallyConfirmed = effective.actor != DecisionActor.ALGORITHM.name && effective.explicit,
                createdAt = effective.decided_at,
                lastModifiedAt = effective.decided_at,
            )
        }
    }

    private fun Database.upsertSourceWorkRecord(
        sourceId: Long,
        stableSourceUrl: String,
        mangaId: Long?,
        title: String,
        authorText: String?,
        artistText: String?,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
        reviewState: String?,
        now: Long,
    ): SourceWorkMutation {
        val stableUrl = stableSourceUrl.trim()
        val trimmedTitle = title.trim()
        require(stableUrl.isNotBlank()) { "Source work URL must not be blank" }
        require(trimmedTitle.isNotBlank()) { "Source work title must not be blank" }
        val key = SourceWorkNaturalKey(sourceId, stableUrl)
        val existing = author_archiveQueries.getArchiveSourceWorkByKey(sourceId, stableUrl).executeAsOneOrNull()
        if (existing == null) {
            author_archiveQueries.insertArchiveSourceWork(
                sourceId = sourceId,
                stableSourceUrl = stableUrl,
                mangaId = mangaId,
                title = trimmedTitle,
                normalizedTitle = CreatorNameNormalizer.normalize(trimmedTitle),
                authorText = authorText,
                artistText = artistText,
                thumbnailUrl = thumbnailUrl,
                firstSeenAt = now,
                lastSeenAt = now,
                detailsFetchedAt = detailsFetchedAt,
                legacyReviewSnapshot = reviewState,
            )
            val id = author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
            return SourceWorkMutation(id, ArchiveUpsertOutcome.Inserted(key))
        }
        val normalizedTitle = CreatorNameNormalizer.normalize(trimmedTitle)
        val effectiveDetailsFetchedAt = detailsFetchedAt ?: existing.details_fetched_at
        val effectiveMangaId = mangaId ?: existing.manga_id
        val effectiveReviewState = when {
            reviewState == null -> existing.legacy_review_snapshot
            existing.legacy_review_snapshot in setOf("ACCEPTED", "IGNORED", "MERGED") && reviewState == "PENDING" ->
                existing.legacy_review_snapshot
            else -> reviewState
        }
        val changed = existing.manga_id != effectiveMangaId ||
            existing.title != trimmedTitle ||
            existing.normalized_title != normalizedTitle ||
            existing.author_text != authorText ||
            existing.artist_text != artistText ||
            existing.thumbnail_url != thumbnailUrl ||
            existing.details_fetched_at != effectiveDetailsFetchedAt ||
            existing.legacy_review_snapshot != effectiveReviewState
        author_archiveQueries.updateArchiveSourceWorkMetadata(
            mangaId = mangaId,
            title = trimmedTitle,
            normalizedTitle = normalizedTitle,
            authorText = authorText,
            artistText = artistText,
            thumbnailUrl = thumbnailUrl,
            lastSeenAt = now,
            detailsFetchedAt = detailsFetchedAt,
            id = existing._id,
        )
        if (reviewState != null) {
            author_archiveQueries.updateArchiveSourceWorkLegacyReviewSnapshot(
                legacyReviewSnapshot = reviewState,
                id = existing._id,
            )
        }
        return SourceWorkMutation(
            sourceWorkId = existing._id,
            outcome = if (changed) ArchiveUpsertOutcome.Updated(key) else ArchiveUpsertOutcome.Unchanged(key),
        )
    }

    private fun Database.getArchiveCandidate(sourceWorkId: Long): DiscoveryCandidate {
        return author_archiveQueries.getArchiveCandidatesForCreator(
            sourceWorkId = sourceWorkId,
            creatorId = null,
            limit = 1,
            mapper = ::mapDiscoveryCandidate,
        ).executeAsOne()
    }

    private fun Database.resolveLanguageSubject(subject: ArchiveLanguageSubject): Pair<String, String> {
        return when (subject) {
            is ArchiveLanguageSubject.SourceWork -> {
                val key = subject.naturalKey.copy(stableSourceUrl = subject.naturalKey.stableSourceUrl.trim())
                check(
                    author_archiveQueries
                        .getArchiveSourceWorkByKey(key.sourceId, key.stableSourceUrl)
                        .executeAsOneOrNull() != null,
                ) { "Source work does not exist: $key" }
                "SOURCE_WORK" to sourceWorkSubjectKey(key.sourceId, key.stableSourceUrl)
            }
            is ArchiveLanguageSubject.CanonicalWork -> {
                check(
                    author_archiveQueries
                        .getArchiveCanonicalWorkIdByPortableKey(subject.portableKey.value)
                        .executeAsOneOrNull() != null,
                ) { "Canonical work does not exist: ${subject.portableKey.value}" }
                "CANONICAL_WORK" to canonicalWorkSubjectKey(subject.portableKey.value)
            }
            is ArchiveLanguageSubject.Creator -> {
                check(
                    author_archiveQueries
                        .getArchiveCreatorIdByPortableKey(subject.portableKey.value)
                        .executeAsOneOrNull() != null,
                ) { "Creator does not exist: ${subject.portableKey.value}" }
                "CREATOR" to CreatorArchiveSubjectKey.creator(subject.portableKey)
            }
        }
    }

    private fun mapRelationSnapshot(
        role: String,
        creatorOrder: Long,
        origin: String,
        verification: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = RelationSnapshot(
        role = role,
        order = creatorOrder,
        origin = origin,
        verification = verification,
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
    )

    private fun mapWorkDecisionEvent(
        sourceWorkId: Long,
        workId: Long,
        state: String,
        actor: String,
        explicit: Boolean,
        algorithmVersion: String?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
    ) = WorkDecisionEvent(
        sourceWorkId = sourceWorkId,
        workId = workId,
        decision = WorkDecisionContract(
            state = WorkDecisionState.valueOf(state),
            actor = DecisionActor.valueOf(actor),
            explicit = explicit,
        ),
        algorithmVersion = algorithmVersion,
        score = score,
        evidence = evidence,
        decidedAt = decidedAt,
    )

    private fun mapLanguageAssertionEvent(
        subjectType: String,
        subjectKey: String,
        dimension: String,
        languageTag: String,
        confidence: Double,
        evidenceKind: String,
        evidencePayload: String,
        actor: String,
        algorithmVersion: String?,
        withdrawn: Boolean,
        assertedAt: Long,
    ) = LanguageAssertionEvent(
        subjectType = subjectType,
        subjectKey = subjectKey,
        assertion = LanguageAssertionContract(
            dimension = LanguageDimension.valueOf(dimension),
            tag = languageTag,
            confidence = confidence,
            evidenceKind = LanguageEvidenceKind.valueOf(evidenceKind),
            withdrawn = withdrawn,
        ),
        actor = DecisionActor.valueOf(actor),
        evidencePayload = evidencePayload,
        algorithmVersion = algorithmVersion,
        assertedAt = assertedAt,
    )

    private fun mapCreator(
        id: Long,
        displayName: String,
        normalizedName: String,
        sortName: String?,
        aliases: String,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = Creator(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        sortName = sortName,
        aliases = decodeHexStrings(aliases).filter {
            CreatorNameNormalizer.normalize(it) != normalizedName
        },
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapCreatorWatch(
        creatorId: Long,
        enabled: Boolean,
        sourceIds: String,
        languageTags: String,
        lastCheckedAt: Long?,
        lastSuccessAt: Long?,
        lastError: String?,
        createdAt: Long,
    ) = CreatorWatch(
        creatorId = creatorId,
        enabled = enabled,
        sourceIds = decodeStrings(sourceIds).mapNotNull(String::toLongOrNull),
        languageTags = decodeStrings(languageTags),
        lastCheckedAt = lastCheckedAt,
        lastSuccessAt = lastSuccessAt,
        lastError = lastError,
        createdAt = createdAt,
    )

    private fun mapCanonicalWork(
        id: Long,
        primaryTitle: String,
        normalizedTitle: String,
        primaryCreatorId: Long?,
        originalLanguage: String?,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = CanonicalWork(
        id = id,
        primaryTitle = primaryTitle,
        normalizedTitle = normalizedTitle,
        primaryCreatorId = primaryCreatorId,
        originalLanguage = originalLanguage,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = MangaCreator(
        mangaId = mangaId,
        creatorId = creatorId,
        role = CreatorRole.valueOf(role),
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
    )

    private fun mapDiscoveryCandidateCreator(
        candidateId: Long,
        creatorId: Long,
        role: String,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) = DiscoveryCandidateCreator(
        candidateId = candidateId,
        creatorId = creatorId,
        role = CreatorRole.valueOf(role),
        sourceText = sourceText,
        confidence = confidence,
        evidence = evidence,
    )

    private fun mapMangaWorkMatch(
        mangaId: Long,
        workId: Long,
        confidence: Double,
        matchReason: String,
        state: String,
        manuallyConfirmed: Boolean,
        createdAt: Long,
        lastModifiedAt: Long,
    ) = MangaWorkMatch(
        mangaId = mangaId,
        workId = workId,
        confidence = confidence,
        matchReason = matchReason,
        state = when (WorkDecisionState.valueOf(state)) {
            WorkDecisionState.SUGGESTED -> WorkMatchState.CANDIDATE
            WorkDecisionState.CONFIRMED -> WorkMatchState.CONFIRMED
            WorkDecisionState.REJECTED -> WorkMatchState.REJECTED
        },
        manuallyConfirmed = manuallyConfirmed,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
    )

    private fun mapDiscoveryCandidate(
        id: Long,
        source: Long,
        url: String,
        title: String,
        normalizedTitle: String,
        authorText: String?,
        artistText: String?,
        languageTag: String,
        languageConfidence: Double,
        languageEvidence: String,
        thumbnailUrl: String?,
        firstSeenAt: Long,
        lastSeenAt: Long,
        detailsFetchedAt: Long?,
        state: String,
    ) = DiscoveryCandidate(
        id = id,
        source = source,
        url = url,
        title = title,
        normalizedTitle = normalizedTitle,
        authorText = authorText,
        artistText = artistText,
        languageTag = languageTag,
        languageConfidence = languageConfidence,
        languageEvidence = languageEvidence,
        thumbnailUrl = thumbnailUrl,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        detailsFetchedAt = detailsFetchedAt,
        state = when (state) {
            "PENDING" -> DiscoveryCandidateState.NEW
            "ACCEPTED" -> DiscoveryCandidateState.ACCEPTED
            "IGNORED" -> DiscoveryCandidateState.IGNORED
            "MERGED" -> DiscoveryCandidateState.MERGED
            else -> error("Unknown archive review state: $state")
        },
    )

    private fun decodeStrings(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(LIST_SEPARATOR).filter(String::isNotBlank)
    }

    private fun decodeHexStrings(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(',').map { hex ->
            require(hex.length % 2 == 0) { "Invalid archive alias encoding" }
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }.decodeToString()
        }
    }

    private fun WorkMatchState.toDecisionState(): WorkDecisionState = when (this) {
        WorkMatchState.CANDIDATE -> WorkDecisionState.SUGGESTED
        WorkMatchState.CONFIRMED -> WorkDecisionState.CONFIRMED
        WorkMatchState.REJECTED -> WorkDecisionState.REJECTED
    }

    private fun DiscoveryCandidateState.toArchiveReviewState(): String = when (this) {
        DiscoveryCandidateState.NEW -> "PENDING"
        DiscoveryCandidateState.ACCEPTED -> "ACCEPTED"
        DiscoveryCandidateState.IGNORED -> "IGNORED"
        DiscoveryCandidateState.MERGED -> "MERGED"
    }

    private fun String.toArchiveEvidenceKind(): String = when (uppercase()) {
        "EXPLICIT_METADATA", "STRUCTURED_METADATA" -> "STRUCTURED_METADATA"
        "GENRE_TAG", "SOURCE_FILTER_OR_TAG" -> "SOURCE_FILTER_OR_TAG"
        "SOURCE_LANGUAGE", "SINGLE_LANGUAGE_SOURCE" -> "SINGLE_LANGUAGE_SOURCE"
        "TEXT_DETECTED", "TEXT_DETECTION" -> "TEXT_DETECTION"
        "MANUAL" -> "MANUAL"
        else -> "UNKNOWN"
    }

    private fun sourceWorkSubjectKey(sourceId: Long, stableSourceUrl: String): String {
        return CreatorArchiveSubjectKey.sourceWork(SourceWorkNaturalKey(sourceId, stableSourceUrl))
    }

    private fun canonicalWorkSubjectKey(portableKey: String): String {
        return CreatorArchiveSubjectKey.canonicalWork(
            tachiyomi.domain.creator.model.CanonicalWorkPortableKey(portableKey),
        )
    }

    private fun languageAssertionIdempotencyKey(
        sourceWorkId: Long,
        evidenceKind: String,
        languageTag: String,
        confidence: Double,
        evidencePayload: String,
        assertedAt: Long,
    ): String {
        return "legacy-language:$sourceWorkId:reading:$evidenceKind:$languageTag:${confidence.coerceIn(0.0, 1.0)}:" +
            "${evidencePayload.hashCode()}:$assertedAt"
    }

    private fun workDecisionIdempotencyKey(
        mangaId: Long,
        workId: Long,
        decision: WorkDecisionContract,
        confidence: Double,
        evidence: String,
        decidedAt: Long,
    ): String {
        return "legacy-work-decision:$mangaId:$workId:${decision.actor}:${decision.state}:" +
            "${confidence.coerceIn(0.0, 1.0)}:${evidence.hashCode()}:$decidedAt"
    }

    private data class SourceWorkMutation(
        val sourceWorkId: Long,
        val outcome: ArchiveUpsertOutcome<SourceWorkNaturalKey>,
    )

    private data class RelationSnapshot(
        val role: String,
        val order: Long,
        val origin: String,
        val verification: String,
        val sourceText: String?,
        val confidence: Double,
        val evidence: String,
    )

    private data class WorkDecisionEvent(
        val sourceWorkId: Long,
        val workId: Long,
        val decision: WorkDecisionContract,
        val algorithmVersion: String?,
        val score: Double?,
        val evidence: String,
        val decidedAt: Long,
    )

    private data class LanguageAssertionEvent(
        val subjectType: String,
        val subjectKey: String,
        val assertion: LanguageAssertionContract,
        val actor: DecisionActor,
        val evidencePayload: String,
        val algorithmVersion: String?,
        val assertedAt: Long,
    )

    private companion object {
        const val DEFAULT_WATCH_PERIOD_MILLIS = 86_400_000L
        const val LEGACY_COMPAT_ALGORITHM_VERSION = "creator-archive-v2-compat-1"
        const val LIST_SEPARATOR = '\u001F'
    }
}

internal fun Database.reconcileArchiveCanonicalVersion(sourceWorkId: Long) {
    val binding = author_archiveQueries
        .getArchiveWorkDecisionsForBinding(sourceWorkId)
        .executeAsList()
        .distinctBy { it.work_id }
        .firstOrNull { it.state == WorkDecisionState.CONFIRMED.name }
    if (binding == null) {
        author_archiveQueries.deleteArchiveCanonicalVersion(sourceWorkId)
        return
    }
    author_archiveQueries.upsertArchiveCanonicalVersion(
        workId = binding.work_id,
        sourceWorkId = sourceWorkId,
        confirmation = if (binding.actor == DecisionActor.RESTORE.name) "RESTORED" else "CONFIRMED",
        evidence = binding.evidence,
        confirmedAt = binding.decided_at,
    )
}
