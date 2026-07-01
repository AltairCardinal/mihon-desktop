package tachiyomi.data.creator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateCreator
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.MangaWorkMatch
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorNameNormalizer

class CreatorRepositoryImpl(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CreatorRepository {

    override suspend fun upsertCreator(displayName: String, aliases: List<String>): Creator {
        val normalizedName = CreatorNameNormalizer.normalize(displayName)
        val now = clock()
        return handler.await(inTransaction = true) {
            val existing = creatorsQueries.getCreatorByNormalizedName(normalizedName, ::mapCreator).executeAsOneOrNull()
            if (existing != null) {
                val mergedAliases = (existing.aliases + aliases).distinctBy(CreatorNameNormalizer::normalize)
                creatorsQueries.updateCreator(
                    displayName = displayName.trim(),
                    sortName = displayName.trim(),
                    aliases = encodeStrings(mergedAliases),
                    lastModifiedAt = now,
                    id = existing.id,
                )
                creatorsQueries.getCreator(existing.id, ::mapCreator).executeAsOne()
            } else {
                creatorsQueries.insertCreator(
                    displayName = displayName.trim(),
                    normalizedName = normalizedName,
                    sortName = displayName.trim(),
                    aliases = encodeStrings(aliases),
                    createdAt = now,
                    lastModifiedAt = now,
                )
                creatorsQueries.getCreatorByNormalizedName(normalizedName, ::mapCreator).executeAsOne()
            }
        }
    }

    override suspend fun getCreator(id: Long): Creator? {
        return handler.awaitOneOrNull { creatorsQueries.getCreator(id, ::mapCreator) }
    }

    override fun getCreatorsAsFlow(): Flow<List<Creator>> {
        return handler.subscribeToList { creatorsQueries.getCreators(::mapCreator) }
    }

    override suspend fun linkMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ) {
        handler.await {
            creatorsQueries.linkMangaCreator(
                mangaId = mangaId,
                creatorId = creatorId,
                role = role.name.lowercase(),
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
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
        handler.await {
            creatorsQueries.linkDiscoveryCandidateCreator(
                candidateId = candidateId,
                creatorId = creatorId,
                role = role.name.lowercase(),
                sourceText = sourceText,
                confidence = confidence,
                evidence = evidence,
            )
        }
    }

    override suspend fun followCreator(
        creatorId: Long,
        sourceIds: List<Long>,
        languageTags: List<String>,
    ): CreatorWatch {
        val now = clock()
        return handler.await(inTransaction = true) {
            creatorsQueries.followCreator(
                creatorId = creatorId,
                sourceIds = encodeLongs(sourceIds),
                languageTags = encodeStrings(languageTags),
                createdAt = now,
            )
            creatorsQueries.getFollowedCreators(::mapCreatorWatch)
                .executeAsList()
                .first { it.creatorId == creatorId }
        }
    }

    override suspend fun unfollowCreator(creatorId: Long) {
        handler.await { creatorsQueries.unfollowCreator(creatorId) }
    }

    override suspend fun getFollowedCreators(): List<CreatorWatch> {
        return handler.awaitList { creatorsQueries.getFollowedCreators(::mapCreatorWatch) }
    }

    override fun getFollowedCreatorsAsFlow(): Flow<List<CreatorWatch>> {
        return handler.subscribeToList { creatorsQueries.getFollowedCreators(::mapCreatorWatch) }
    }

    override suspend fun updateWatchCheckResult(creatorId: Long, checkedAt: Long, success: Boolean, error: String?) {
        handler.await {
            creatorsQueries.updateWatchCheckResult(
                checkedAt = checkedAt,
                success = success,
                error = error,
                creatorId = creatorId,
            )
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
        val now = clock()
        return handler.awaitOneExecutable(inTransaction = true) {
            creatorsQueries.upsertDiscoveryCandidate(
                source = source,
                url = url,
                title = title,
                normalizedTitle = CreatorNameNormalizer.normalize(title),
                authorText = authorText,
                artistText = artistText,
                languageTag = languageTag,
                languageConfidence = languageConfidence,
                languageEvidence = languageEvidence,
                thumbnailUrl = thumbnailUrl,
                now = now,
                detailsFetchedAt = detailsFetchedAt,
                state = state.name.lowercase(),
                mapper = ::mapDiscoveryCandidate,
            )
        }
    }

    override suspend fun getDiscoveryCandidatesForCreator(creatorId: Long): List<DiscoveryCandidate> {
        return handler.awaitList {
            creatorsQueries.getDiscoveryCandidatesForCreator(creatorId, ::mapDiscoveryCandidate)
        }
    }

    override suspend fun getDiscoveryCandidate(id: Long): DiscoveryCandidate? {
        return handler.awaitOneOrNull { creatorsQueries.getDiscoveryCandidate(id, ::mapDiscoveryCandidate) }
    }

    override suspend fun getMangaCreatorsForCreator(creatorId: Long): List<MangaCreator> {
        return handler.awaitList { creatorsQueries.getMangaCreatorsForCreator(creatorId, ::mapMangaCreator) }
    }

    override suspend fun getDiscoveryCandidateCreatorsForCreator(creatorId: Long): List<DiscoveryCandidateCreator> {
        return handler.awaitList {
            creatorsQueries.getDiscoveryCandidateCreatorsForCreator(creatorId, ::mapDiscoveryCandidateCreator)
        }
    }

    override suspend fun createCanonicalWork(
        primaryTitle: String,
        primaryCreatorId: Long?,
        originalLanguage: String?,
    ): CanonicalWork {
        val now = clock()
        return handler.await(inTransaction = true) {
            creatorsQueries.insertCanonicalWork(
                primaryTitle = primaryTitle,
                normalizedTitle = CreatorNameNormalizer.normalize(primaryTitle),
                primaryCreatorId = primaryCreatorId,
                originalLanguage = originalLanguage,
                createdAt = now,
                lastModifiedAt = now,
            )
            val id = creatorsQueries.selectLastInsertedRowId().executeAsOne()
            creatorsQueries.getCanonicalWorkById(id, ::mapCanonicalWork).executeAsOne()
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
        val now = clock()
        return handler.awaitOneExecutable(inTransaction = true) {
            creatorsQueries.upsertMangaWorkMatch(
                mangaId = mangaId,
                workId = workId,
                confidence = confidence,
                matchReason = matchReason,
                state = state.name.lowercase(),
                manuallyConfirmed = manuallyConfirmed,
                now = now,
                mapper = ::mapMangaWorkMatch,
            )
        }
    }

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
        aliases = decodeStrings(aliases),
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
        sourceIds = decodeLongs(sourceIds),
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
        role = role.toCreatorRole(),
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
        role = role.toCreatorRole(),
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
        state = state.toWorkMatchState(),
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
        state = state.toDiscoveryCandidateState(),
    )

    private fun String.toWorkMatchState(): WorkMatchState {
        return WorkMatchState.valueOf(uppercase())
    }

    private fun String.toCreatorRole(): CreatorRole {
        return CreatorRole.valueOf(uppercase())
    }

    private fun String.toDiscoveryCandidateState(): DiscoveryCandidateState {
        return DiscoveryCandidateState.valueOf(uppercase())
    }

    private fun encodeStrings(values: List<String>): String = values.joinToString(LIST_SEPARATOR)

    private fun decodeStrings(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(LIST_SEPARATOR).filter { it.isNotBlank() }
    }

    private fun encodeLongs(values: List<Long>): String = values.joinToString(LIST_SEPARATOR)

    private fun decodeLongs(value: String): List<Long> {
        if (value.isBlank()) return emptyList()
        return value.split(LIST_SEPARATOR).mapNotNull { it.toLongOrNull() }
    }

    private companion object {
        const val LIST_SEPARATOR = "|"
    }
}
